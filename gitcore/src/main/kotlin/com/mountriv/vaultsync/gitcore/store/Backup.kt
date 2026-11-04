package com.mountriv.vaultsync.gitcore.store

import com.mountriv.vaultsync.gitcore.model.RepoSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 配置备份文件（D-019 第 5 条、docs/ANDROID_PLATFORM.md §6）。
 *
 * 仓库列表、设置、SSH 密钥清单（只有公钥信息）、已知主机以明文 JSON 保存，导入前就能展示摘要；
 * 令牌与私钥用用户口令加密后放在 [secrets]（见 [BackupCrypto]）。[settings] 与 [sshKeys] 的结构属于 app 层，这里只当 JSON 透传。
 */
@Serializable
data class BackupDocument(
    val format: String = FORMAT,
    val version: Int = VERSION,
    /** ISO-8601 时间。 */
    val createdAt: String,
    val appVersion: String = "",
    val device: String = "",
    val settings: JsonElement? = null,
    val repos: List<RepoSpec> = emptyList(),
    val sshKeys: JsonElement? = null,
    /** 已知主机文件全文（OpenSSH 格式）。 */
    val knownHosts: String? = null,
    /** 加密的秘密（秘密 id → 明文）；导出时选择不含秘密则为 null。 */
    val secrets: SealedSecrets? = null,
) {
    companion object {
        const val FORMAT = "vaultsync-backup"
        const val VERSION = 1

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }

        fun encode(doc: BackupDocument): String = json.encodeToString(serializer(), doc)

        /**
         * 导出后的核对（D-039）：[actual] 是写完文件后读回来的内容，必须与 [expected] 逐字节相同，并且能按备份格式解析。
         * 不对时抛 [IllegalStateException]（说明哪里不对，进日志）；有的存储服务写入失败也不报错，或不截断旧文件的内容。
         */
        fun verifyWritten(expected: ByteArray, actual: ByteArray?) {
            check(actual != null) { "the written file cannot be read back" }
            check(actual.size == expected.size) { "the written file has ${actual.size} bytes, expected ${expected.size}" }
            check(actual.contentEquals(expected)) { "the written file differs from what was exported" }
            decode(String(actual, Charsets.UTF_8))
        }

        /** 解析备份文本；不是本应用的备份、版本比本应用新、或内容损坏时抛 [BackupException]。 */
        fun decode(text: String): BackupDocument {
            val root = try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw BackupException(BackupException.Reason.NOT_A_BACKUP, "not a JSON document", e)
            }
            val format = runCatching { root["format"]?.jsonPrimitive?.content }.getOrNull()
            if (format != FORMAT) throw BackupException(BackupException.Reason.NOT_A_BACKUP, "format is '$format', expected '$FORMAT'")
            val version = runCatching { root["version"]?.jsonPrimitive?.intOrNull }.getOrNull()
                ?: throw BackupException(BackupException.Reason.CORRUPT, "missing version")
            if (version > VERSION) throw BackupException(BackupException.Reason.TOO_NEW, "backup version $version is newer than supported $VERSION")
            return try {
                json.decodeFromJsonElement(serializer(), root)
            } catch (e: Exception) {
                throw BackupException(BackupException.Reason.CORRUPT, "cannot read backup: ${e.message}", e)
            }
        }
    }
}

/** 口令加密后的秘密。所有二进制字段都是 base64。 */
@Serializable
data class SealedSecrets(
    val kdf: String,
    val iterations: Int,
    val salt: String,
    val cipher: String,
    val iv: String,
    val data: String,
)

class BackupException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason {
        /** 不是 VaultSync 的备份文件。 */
        NOT_A_BACKUP,

        /** 由更新版本的应用导出，本版本读不了。 */
        TOO_NEW,

        /** 口令不对（或秘密部分被改动过）。 */
        WRONG_PASSPHRASE,

        /** 结构损坏。 */
        CORRUPT,
    }
}

/**
 * 备份里秘密的加解密：PBKDF2WithHmacSHA256 由口令派生 256 位密钥（盐 16 字节，迭代次数写进文件），
 * AES/GCM/NoPadding（IV 12 字节、标签 128 位），附加数据固定为 [AAD]。Android API 26+ 与桌面 JVM 都自带这两个算法。
 */
object BackupCrypto {
    const val KDF = "PBKDF2WithHmacSHA256"
    const val CIPHER = "AES/GCM/NoPadding"
    const val DEFAULT_ITERATIONS = 300_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private val AAD = "${BackupDocument.FORMAT}/v${BackupDocument.VERSION}".toByteArray(Charsets.UTF_8)
    private val payloadSerializer = MapSerializer(String.serializer(), String.serializer())
    private val random = SecureRandom()

    /**
     * 加密。加密完立即用同一把密钥解一次、与原文比对（D-039），不一致抛 [IllegalStateException]；
     * 不重新跑 PBKDF2（手机上要一秒左右），口令到密钥这一步由测试覆盖。
     */
    fun seal(secrets: Map<String, String>, passphrase: CharArray, iterations: Int = DEFAULT_ITERATIONS): SealedSecrets {
        require(passphrase.isNotEmpty()) { "passphrase must not be empty" }
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val plain = Json.encodeToString(payloadSerializer, secrets).toByteArray(Charsets.UTF_8)
        var check: ByteArray? = null
        try {
            val key = deriveKey(passphrase, salt, iterations)
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            val data = cipher.doFinal(plain)
            val decipher = Cipher.getInstance(CIPHER)
            decipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            decipher.updateAAD(AAD)
            check = decipher.doFinal(data)
            check(check.contentEquals(plain)) { "encrypted secrets do not decrypt back to the original" }
            return SealedSecrets(KDF, iterations, b64(salt), CIPHER, b64(iv), b64(data))
        } finally {
            plain.fill(0)
            check?.fill(0)
        }
    }

    /** 解密；口令错或数据被改动时抛 [BackupException]（[BackupException.Reason.WRONG_PASSPHRASE]）。 */
    fun open(sealed: SealedSecrets, passphrase: CharArray): Map<String, String> {
        if (sealed.kdf != KDF || sealed.cipher != CIPHER) {
            throw BackupException(BackupException.Reason.CORRUPT, "unsupported encryption ${sealed.kdf} / ${sealed.cipher}")
        }
        if (sealed.iterations !in 1..10_000_000) throw BackupException(BackupException.Reason.CORRUPT, "bad iteration count ${sealed.iterations}")
        val salt = unb64(sealed.salt)
        val iv = unb64(sealed.iv)
        val data = unb64(sealed.data)
        val plain = try {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, sealed.iterations), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(AAD)
            cipher.doFinal(data)
        } catch (e: AEADBadTagException) {
            throw BackupException(BackupException.Reason.WRONG_PASSPHRASE, "wrong passphrase", e)
        } catch (e: GeneralSecurityException) {
            throw BackupException(BackupException.Reason.CORRUPT, "cannot decrypt: ${e.message}", e)
        }
        try {
            return Json.decodeFromString(payloadSerializer, String(plain, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupException(BackupException.Reason.CORRUPT, "decrypted secrets are not readable", e)
        } finally {
            plain.fill(0)
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded
            try {
                return SecretKeySpec(bytes, "AES")
            } finally {
                bytes.fill(0)
            }
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun unb64(text: String): ByteArray = try {
        Base64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        throw BackupException(BackupException.Reason.CORRUPT, "bad base64", e)
    }
}
