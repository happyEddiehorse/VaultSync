package com.mountriv.vaultsync.platform

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import com.mountriv.vaultsync.BuildConfig
import com.mountriv.vaultsync.data.AppSettings
import com.mountriv.vaultsync.data.BackupStatus
import com.mountriv.vaultsync.data.BackupStatusStore
import com.mountriv.vaultsync.data.RepoStore
import com.mountriv.vaultsync.data.SettingsStore
import com.mountriv.vaultsync.gitcore.auth.knownHostsText
import com.mountriv.vaultsync.gitcore.auth.mergeKnownHosts
import com.mountriv.vaultsync.gitcore.log.SyncLogger
import com.mountriv.vaultsync.gitcore.model.AuthSpec
import com.mountriv.vaultsync.gitcore.model.RepoSpec
import com.mountriv.vaultsync.gitcore.store.BackupCrypto
import com.mountriv.vaultsync.gitcore.store.BackupDocument
import com.mountriv.vaultsync.gitcore.store.BackupException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * 配置导出 / 导入（D-019 第 5 条、D-027、D-039、docs/ANDROID_PLATFORM.md §6）。文件格式与加密在 gitcore（[BackupDocument]、[BackupCrypto]）；
 * 这里负责从各个存储收集内容、写文件并核对、恢复时按用户勾选写回。所有方法阻塞（PBKDF2 需要约一秒），调用方放 IO 线程。
 *
 * 恢复是"合并"：勾选的库与密钥同 id 覆盖，其它的保留；勾了"软件设置"时设置整体替换；已知主机总是只增加本机没有的。
 * [isBusy] 为 true（有任务在同步、克隆、整理或排队）时拒绝恢复：恢复会改写正在同步的库的配置。
 */
class ConfigBackup(
    private val repos: RepoStore,
    private val settings: SettingsStore,
    private val secrets: KeystoreSecretStore,
    private val sshKeys: SshKeyStore,
    private val status: BackupStatusStore,
    private val logger: SyncLogger,
    private val isBusy: () -> Boolean,
) {
    /** 导出了什么；[unreadableTokens]（库名）、[unreadableKeys]（密钥名）是本机解不开、因此没能导出的。 */
    data class ExportResult(
        val repos: Int,
        val keys: Int,
        val tokens: Int,
        val withSecrets: Boolean,
        val unreadableTokens: List<String>,
        val unreadableKeys: List<String>,
    )

    /**
     * 备份里的一个库。[folderMissing]：本机没有这个文件夹（或是空文件夹）；[replacesSameRepo]：本机已有同一个库（同 id），恢复会覆盖它；
     * [folderUsedBy]：本机另一个库（名称）已在使用这个文件夹。
     */
    data class RepoItem(val spec: RepoSpec, val folderMissing: Boolean, val replacesSameRepo: Boolean, val folderUsedBy: String?) {
        /** 默认勾选：文件夹在本机不存在、或被本机另一个库占用的不勾（D-039）。 */
        val selectedByDefault: Boolean get() = !folderMissing && folderUsedBy == null
    }

    /** 备份里的一把密钥；[replacesSameKey]：本机已有同一把（同 id），恢复会覆盖它。 */
    data class KeyItem(val meta: SshKeyStore.KeyMeta, val replacesSameKey: Boolean)

    /** 导入前展示的摘要。 */
    data class Preview(
        val document: BackupDocument,
        val repos: List<RepoItem>,
        val keys: List<KeyItem>,
        private val keyIndex: SshKeyStore.Index?,
    ) {
        val hasSecrets: Boolean get() = document.secrets != null
        val hasSettings: Boolean get() = document.settings != null

        /** 备份时间（毫秒）；文件里的时间读不出来时为 null。 */
        val createdAtMs: Long? get() = runCatching { OffsetDateTime.parse(document.createdAt).toInstant().toEpochMilli() }.getOrNull()

        fun defaultSelection() = Selection(
            repoIds = repos.filter { it.selectedByDefault }.map { it.spec.id }.toSet(),
            keyIds = keys.map { it.meta.id }.toSet(),
            settings = hasSettings,
        )

        internal fun keyIndexFor(ids: Set<String>): SshKeyStore.Index? =
            keyIndex?.let { idx -> idx.copy(keys = idx.keys.filter { it.id in ids }) }
    }

    /** 用户勾选要恢复的内容。 */
    data class Selection(val repoIds: Set<String>, val keyIds: Set<String>, val settings: Boolean) {
        val isEmpty: Boolean get() = repoIds.isEmpty() && keyIds.isEmpty() && !settings
    }

    data class RestoreResult(
        val repos: Int,
        val keys: Int,
        val tokens: Int,
        val knownHosts: Int,
        val secretsSkipped: Boolean,
        /** 恢复的库里文件夹在本机不存在的个数（可到库详情页重新克隆）。 */
        val missingFolders: Int,
        val settingsRestored: Boolean,
    )

    /** 有任务在同步、克隆、整理或排队，不能恢复。 */
    class BusyException : IllegalStateException("a sync is running; restore refused")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 导出到 [uri] 并核对（D-039）：写完读回来逐字节比对、按备份格式解析一遍；加密部分在 [BackupCrypto.seal] 里已解过一次。
     * 核对不过抛 [IllegalStateException]。成功后记下这次导出（[BackupStatusStore]）。[passphrase] 为 null 时不含令牌与私钥。
     */
    fun exportTo(resolver: ContentResolver, uri: Uri, passphrase: CharArray?): ExportResult {
        val (text, result) = build(passphrase)
        val bytes = text.toByteArray(Charsets.UTF_8)
        openForWrite(resolver, uri).use { it.write(bytes) }
        val back = resolver.openInputStream(uri)?.use { it.readBytes() }
        BackupDocument.verifyWritten(bytes, back)
        status.update {
            BackupStatus(System.currentTimeMillis(), result.withSecrets, result.unreadableTokens, result.unreadableKeys)
        }
        logger.info("backup exported and verified: $result (${bytes.size} bytes)")
        return result
    }

    /** "wt" 会先截断旧内容（覆盖一个更长的旧文件时，"w" 在有的存储服务上会留下旧文件的尾巴）；不支持 "wt" 的退回 "w"，核对会发现问题。 */
    private fun openForWrite(resolver: ContentResolver, uri: Uri): OutputStream {
        val stream = try {
            resolver.openOutputStream(uri, "wt")
        } catch (e: FileNotFoundException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        return stream ?: resolver.openOutputStream(uri, "w") ?: error("cannot open $uri")
    }
    /** 生成备份文本与导出结果。 */
    private fun build(passphrase: CharArray?): Pair<String, ExportResult> {
        val repoList = repos.state.value
        val keyIndex = sshKeys.snapshot()
        val unreadableTokens = ArrayList<String>()
        val unreadableKeys = ArrayList<String>()
        var tokens = 0
        var keys = 0
        val sealed = passphrase?.let { pass ->
            val payload = LinkedHashMap<String, String>()
            repoList.forEach { spec ->
                val auth = spec.auth
                if (auth is AuthSpec.HttpsToken) {
                    when (secrets.status(auth.secretId)) {
                        KeystoreSecretStore.Status.PRESENT -> {
                            val token = readSecret(auth.secretId)
                            if (token != null) {
                                payload[auth.secretId] = token
                                tokens++
                            } else {
                                unreadableTokens.add(spec.name)
                            }
                        }
                        KeystoreSecretStore.Status.UNREADABLE -> {
                            unreadableTokens.add(spec.name)
                            logger.warn("backup: token of '${spec.name}' cannot be decrypted; skipped")
                        }
                        // 本来就没保存令牌（例如用户还没填），不算没能导出
                        KeystoreSecretStore.Status.MISSING -> Unit
                    }
                }
            }
            keyIndex.keys.forEach { meta ->
                val text = sshKeys.privateKeyText(meta.id)
                if (text != null) {
                    payload[meta.id] = text
                    keys++
                } else {
                    unreadableKeys.add(meta.name)
                    logger.warn("backup: private key '${meta.id}' cannot be read; skipped")
                }
            }
            BackupCrypto.seal(payload, pass).also { logger.info("backup: sealed ${payload.size} secret(s)") }
        }
        val doc = BackupDocument(
            createdAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
            appVersion = BuildConfig.VERSION_NAME,
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            settings = json.encodeToJsonElement(AppSettings.serializer(), settings.state.value),
            repos = repoList,
            sshKeys = json.encodeToJsonElement(SshKeyStore.Index.serializer(), keyIndex),
            knownHosts = runCatching { knownHostsText(logger) }.getOrNull(),
            secrets = sealed,
        )
        val result = ExportResult(repoList.size, if (sealed != null) keys else 0, tokens, sealed != null, unreadableTokens, unreadableKeys)
        return BackupDocument.encode(doc) to result
    }

    /** 解析备份并生成摘要；不是备份文件等情况抛 [BackupException]。 */
    fun preview(text: String): Preview {
        val doc = BackupDocument.decode(text)
        val keyIndex = doc.sshKeys?.let { runCatching { json.decodeFromJsonElement(SshKeyStore.Index.serializer(), it) }.getOrNull() }
        val local = repos.state.value
        val localKeyIds = sshKeys.snapshot().keys.map { it.id }.toSet()
        val repoItems = doc.repos.map { spec ->
            val path = normalize(spec.localPath)
            RepoItem(
                spec = spec,
                folderMissing = !hasContent(File(spec.localPath)),
                replacesSameRepo = local.any { it.id == spec.id },
                // sdcard 上的路径不区分大小写
                folderUsedBy = local.firstOrNull { it.id != spec.id && normalize(it.localPath).equals(path, ignoreCase = true) }?.name,
            )
        }
        return Preview(
            document = doc,
            repos = repoItems,
            keys = keyIndex?.keys.orEmpty().map { KeyItem(it, it.id in localKeyIds) },
            keyIndex = keyIndex,
        )
    }

    /**
     * 按 [selection] 恢复。[passphrase] 为 null 时跳过令牌与私钥（这些库之后需要重新填写令牌或选择密钥，密钥不会恢复）；
     * 口令错时抛 [BackupException]（[BackupException.Reason.WRONG_PASSPHRASE]），有任务在跑时抛 [BusyException]，这两种情况都什么都不写入。
     */
    fun restore(preview: Preview, passphrase: CharArray?, selection: Selection): RestoreResult {
        if (isBusy()) throw BusyException()
        val doc = preview.document
        val payload = if (passphrase != null && doc.secrets != null) BackupCrypto.open(doc.secrets!!, passphrase) else emptyMap()

        var settingsRestored = false
        if (selection.settings) {
            doc.settings?.let { element ->
                runCatching { json.decodeFromJsonElement(AppSettings.serializer(), element) }
                    .onSuccess { restored -> settings.update { restored }; settingsRestored = true }
                    .onFailure { logger.warn("backup: settings not restored", it) }
            }
        }
        var tokens = 0
        val chosen = preview.repos.filter { it.spec.id in selection.repoIds }
        chosen.forEach { item ->
            val spec = item.spec
            repos.put(spec)
            val auth = spec.auth
            if (auth is AuthSpec.HttpsToken) {
                payload[auth.secretId]?.let { token ->
                    val chars = token.toCharArray()
                    try {
                        secrets.put(auth.secretId, chars)
                        tokens++
                    } finally {
                        chars.fill('\u0000')
                    }
                }
            }
        }
        val keyIndex = preview.keyIndexFor(selection.keyIds)
        val keys = if (keyIndex != null && keyIndex.keys.isNotEmpty()) sshKeys.restore(keyIndex, payload.filterKeys { it.startsWith("ssh-") }) else 0
        val hosts = doc.knownHosts?.let { runCatching { mergeKnownHosts(logger, it) }.getOrDefault(0) } ?: 0
        val result = RestoreResult(
            repos = chosen.size,
            keys = keys,
            tokens = tokens,
            knownHosts = hosts,
            secretsSkipped = doc.secrets != null && passphrase == null,
            missingFolders = chosen.count { it.folderMissing },
            settingsRestored = settingsRestored,
        )
        logger.info("backup restored: $result (selected ${selection.repoIds.size} repo(s), ${selection.keyIds.size} key(s), settings=${selection.settings})")
        return result
    }

    private fun readSecret(id: String): String? = try {
        secrets.get(id)?.let { chars -> String(chars).also { chars.fill('\u0000') } }
    } catch (e: KeystoreSecretStore.UnreadableSecretException) {
        logger.warn("backup: secret '$id' cannot be decrypted; skipped")
        null
    }

    companion object {
        /** 默认文件名：`vaultsync-backup-20260923-1715.json`。 */
        fun defaultFileName(): String =
            "vaultsync-backup-" + java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".json"

        /** 文件夹存在且不是空的（与库详情页"重新克隆"的条件相反，D-039）。 */
        fun hasContent(dir: File): Boolean = dir.isDirectory && dir.list()?.isNotEmpty() == true

        private fun normalize(path: String): String = File(path).absoluteFile.normalize().path.trimEnd('/')
    }
}
