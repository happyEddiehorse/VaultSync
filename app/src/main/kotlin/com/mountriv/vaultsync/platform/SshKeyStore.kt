package com.mountriv.vaultsync.platform

import com.mountriv.vaultsync.gitcore.auth.SshKeys
import com.mountriv.vaultsync.gitcore.log.SyncLogger
import com.mountriv.vaultsync.gitcore.store.JsonFileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/**
 * SSH 密钥列表（docs/ANDROID_PLATFORM.md §5、D-023）：可以有多把带名称的密钥，其中一把是"默认密钥"（新库默认用它）；
 * 每个库在 `RepoSpec.auth` 里引用其中一把（`AuthSpec.SshKey(keyId)`），一把密钥可以被多个库共用。
 *
 * - 私钥（OpenSSH v1 未加密文本）以"秘密 id = 密钥 id"存在 [KeystoreSecretStore]；
 * - 名称、公钥、指纹等存 `config/ssh-keys.json`（[JsonFileStore] 信封），列表页不必解密私钥；
 * - 升级兼容：M4 的单把应用密钥（id [LEGACY_KEY_ID]，信息在 `config/ssh-app-key.json`）第一次读取时迁移成默认密钥，id 不变，
 *   已有库的 `auth` 不用改；
 * - 清单丢失或损坏时，从 `secrets/` 里 `ssh-` 开头的秘密重建条目（D-019：不因为配置文件出问题而丢密钥）。
 *
 * 所有方法都是阻塞的，调用方放 IO 线程；结果通过 [state] 通知界面。
 */
class SshKeyStore(
    private val secrets: KeystoreSecretStore,
    private val configDir: File,
    private val logger: SyncLogger,
    private val names: Names,
) {
    /** 自动命名用的文案（来自字符串资源，由 AppGraph 传入）。 */
    data class Names(val migrated: String, val recovered: String)

    @Serializable
    data class KeyMeta(
        val id: String,
        val name: String,
        val keyType: String,
        val publicKeyLine: String,
        val fingerprint: String,
        val comment: String = "",
        val createdAt: Long,
        /** [SOURCE_GENERATED] / [SOURCE_IMPORTED] */
        val source: String,
    )

    @Serializable
    data class Index(val keys: List<KeyMeta> = emptyList(), val defaultId: String? = null)

    /** 一把密钥；[unreadable] 表示私钥缺失或无法解密（系统密钥库变了），需要删除后重新生成或导入。 */
    data class Key(val meta: KeyMeta, val unreadable: Boolean) {
        val id: String get() = meta.id
    }

    sealed interface State {
        /** 还没读过。 */
        data object Unknown : State

        data class Loaded(val keys: List<Key>, val defaultId: String?) : State {
            val default: Key? get() = keys.firstOrNull { it.id == defaultId }

            fun find(id: String?): Key? = if (id == null) null else keys.firstOrNull { it.id == id }
        }
    }

    /** M4 单密钥时代的信息文件结构（只用于迁移）。 */
    @Serializable
    private data class LegacyInfo(
        val keyType: String,
        val publicKeyLine: String,
        val fingerprint: String,
        val comment: String = "",
        val createdAt: Long,
        val source: String,
    )

    private val indexStore = JsonFileStore(File(configDir, "ssh-keys.json"), Index.serializer(), VERSION)

    private val _state = MutableStateFlow<State>(State.Unknown)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 已读取的列表；还没读过时为 null。 */
    val loaded: State.Loaded? get() = _state.value as? State.Loaded

    /** 重新读取清单与每把私钥的可用状态。 */
    @Synchronized
    fun refresh(): State.Loaded = publish(loadIndex(), write = false)

    /** 生成一把新的 ed25519 密钥加入列表；列表原本为空时它成为默认密钥。 */
    @Synchronized
    fun generate(name: String, comment: String): KeyMeta = add(name, SshKeys.generateEd25519(comment), SOURCE_GENERATED)

    /** 导入用户粘贴的私钥（见 [SshKeys.import]）加入列表；失败抛 [SshKeys.SshKeyException]。 */
    @Synchronized
    fun import(name: String, privateKeyText: String, passphrase: CharArray?): KeyMeta =
        add(name, SshKeys.import(privateKeyText, passphrase), SOURCE_IMPORTED)

    @Synchronized
    fun rename(id: String, name: String) {
        val index = loadIndex()
        publish(index.copy(keys = index.keys.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it }))
    }

    @Synchronized
    fun setDefault(id: String) {
        val index = loadIndex()
        if (index.keys.none { it.id == id }) return
        publish(index.copy(defaultId = id))
        logger.info("ssh key $id is now the default")
    }

    /** 删除密钥（私钥与清单条目）。它是默认密钥时，默认改为剩下的第一把。 */
    @Synchronized
    fun delete(id: String) {
        val index = loadIndex()
        secrets.delete(id)
        val keys = index.keys.filter { it.id != id }
        val defaultId = index.defaultId.takeIf { it != id && keys.any { k -> k.id == it } } ?: keys.firstOrNull()?.id
        publish(Index(keys, defaultId))
        logger.info("ssh key $id deleted")
    }

    /** 当前清单（配置备份用）。 */
    @Synchronized
    fun snapshot(): Index = loadIndex()

    /** 读取私钥文本（配置备份用）；缺失或无法解密返回 null。 */
    fun privateKeyText(id: String): String? = try {
        secrets.get(id)?.let { chars -> String(chars).also { chars.fill('\u0000') } }
    } catch (e: KeystoreSecretStore.UnreadableSecretException) {
        null
    }

    /**
     * 从备份恢复：[privateKeys] 里有私钥的条目写入（同 id 覆盖），没有私钥的跳过。本机没有默认密钥时采用备份里的默认。
     * 返回恢复的密钥数。
     */
    @Synchronized
    fun restore(backup: Index, privateKeys: Map<String, String>): Int {
        var index = loadIndex()
        var count = 0
        for (meta in backup.keys) {
            val text = privateKeys[meta.id] ?: continue
            if (!meta.id.matches(ID_PATTERN)) continue
            val chars = text.toCharArray()
            try {
                secrets.put(meta.id, chars)
            } finally {
                chars.fill('\u0000')
            }
            index = index.copy(keys = index.keys.filter { it.id != meta.id } + meta)
            count++
        }
        if (index.defaultId == null || index.keys.none { it.id == index.defaultId }) {
            index = index.copy(defaultId = backup.defaultId?.takeIf { id -> index.keys.any { it.id == id } } ?: index.keys.firstOrNull()?.id)
        }
        publish(index)
        logger.info("restored $count ssh key(s) from backup")
        return count
    }

    // ---- 内部 ----

    private fun add(name: String, material: SshKeys.KeyMaterial, source: String): KeyMeta {
        // 先读清单再写私钥：否则 loadIndex() 的孤儿自愈会把刚写入的私钥当成孤儿再补一条（同 id 重复条目）
        val index = loadIndex()
        val id = "ssh-key-" + UUID.randomUUID().toString().replace("-", "").take(12)
        val chars = String(material.privateKey, Charsets.UTF_8).toCharArray()
        try {
            secrets.put(id, chars)
        } finally {
            chars.fill('\u0000')
            material.privateKey.fill(0)
        }
        val meta = KeyMeta(
            id = id,
            name = name.trim().ifEmpty { material.publicKey.comment.ifBlank { material.publicKey.keyType } },
            keyType = material.publicKey.keyType,
            publicKeyLine = material.publicKey.line,
            fingerprint = material.publicKey.fingerprint,
            comment = material.publicKey.comment,
            createdAt = System.currentTimeMillis(),
            source = source,
        )
        publish(Index(index.keys + meta, index.defaultId ?: id))
        logger.info("ssh key $source: $id ${meta.keyType} ${meta.fingerprint}")
        return meta
    }

    /** 写清单（[write]）并刷新 [state]：逐把检查私钥能否解密。 */
    private fun publish(index: Index, write: Boolean = true): State.Loaded {
        if (write) indexStore.write(index)
        val keys = index.keys.map { Key(it, unreadable = secrets.status(it.id) != KeystoreSecretStore.Status.PRESENT) }
        val loaded = State.Loaded(keys, index.defaultId?.takeIf { id -> keys.any { it.id == id } })
        _state.value = loaded
        return loaded
    }

    private fun loadIndex(): Index = when (val r = indexStore.read()) {
        is JsonFileStore.ReadResult.Ok -> withOrphans(r.value).also { if (it != r.value) indexStore.write(it) }
        JsonFileStore.ReadResult.Missing -> withOrphans(migrateLegacy()).also { if (it.keys.isNotEmpty()) indexStore.write(it) }
        is JsonFileStore.ReadResult.Corrupt -> {
            logger.error("ssh key list is corrupt; kept as ${r.backup?.name}; rebuilding from stored keys", r.error)
            withOrphans(Index()).also { indexStore.write(it) }
        }
    }

    /** M4 的单把应用密钥 → 默认密钥（id 不变）。 */
    private fun migrateLegacy(): Index {
        val legacyFile = File(configDir, LEGACY_INFO_FILE)
        val secretStatus = secrets.status(LEGACY_KEY_ID)
        if (secretStatus == KeystoreSecretStore.Status.MISSING) return Index()
        val info = (JsonFileStore(legacyFile, LegacyInfo.serializer(), 1).read() as? JsonFileStore.ReadResult.Ok)?.value
        val meta = if (info != null) {
            KeyMeta(LEGACY_KEY_ID, names.migrated, info.keyType, info.publicKeyLine, info.fingerprint, info.comment, info.createdAt, info.source)
        } else {
            metaFromSecret(LEGACY_KEY_ID, names.migrated) ?: return Index()
        }
        logger.info("migrated the single app ssh key to the key list as the default key")
        return Index(listOf(meta), LEGACY_KEY_ID)
    }

    /** 补上 `secrets/` 里有、清单里没有的 SSH 私钥（清单写入失败或损坏后的自愈）。 */
    private fun withOrphans(raw: Index): Index {
        // 同 id 只保留最后一条（0.2.0 开发版的 add() 会留下重复条目，后一条才是用户起的名字）
        val index = raw.copy(keys = raw.keys.associateBy { it.id }.values.toList())
        val known = index.keys.map { it.id }.toSet()
        val orphans = secrets.ids().filter { it.startsWith("ssh-") && it !in known }
        if (orphans.isEmpty()) return index
        val recovered = orphans.mapNotNull { id -> metaFromSecret(id, names.recovered) }
        if (recovered.isNotEmpty()) logger.warn("recovered ${recovered.size} ssh key(s) missing from the key list: ${recovered.map { it.id }}")
        val keys = index.keys + recovered
        return Index(keys, index.defaultId ?: keys.firstOrNull()?.id)
    }

    private fun metaFromSecret(id: String, fallbackName: String): KeyMeta? {
        val chars = try {
            secrets.get(id)
        } catch (e: KeystoreSecretStore.UnreadableSecretException) {
            null
        } ?: return null
        val bytes = String(chars).toByteArray(Charsets.UTF_8)
        chars.fill('\u0000')
        val pub = SshKeys.publicKeyOf(bytes)
        bytes.fill(0)
        if (pub == null) {
            logger.warn("stored ssh key $id cannot be parsed; skipped")
            return null
        }
        val name = if (id == LEGACY_KEY_ID) fallbackName else pub.comment.ifBlank { fallbackName }
        return KeyMeta(id, name, pub.keyType, pub.line, pub.fingerprint, pub.comment, System.currentTimeMillis(), SOURCE_IMPORTED)
    }

    companion object {
        const val VERSION = 1

        /** M4 的单把应用密钥的 id；迁移后仍是这把密钥的 id。 */
        const val LEGACY_KEY_ID = "ssh-app-key"
        private const val LEGACY_INFO_FILE = "ssh-app-key.json"
        private val ID_PATTERN = Regex("ssh-[A-Za-z0-9._-]+")

        const val SOURCE_GENERATED = "generated"
        const val SOURCE_IMPORTED = "imported"

        /** 生成密钥时的注释：`vaultsync@<机型>`（在 GitHub 上作为密钥标题的默认值）。 */
        fun defaultComment(model: String?): String {
            val m = (model ?: "").trim().replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-')
            return if (m.isEmpty()) "vaultsync@android" else "vaultsync@$m"
        }
    }
}
