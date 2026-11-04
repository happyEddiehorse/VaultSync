package com.mountriv.vaultsync.gitcore.auth

import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream

/**
 * SSH 密钥的生成、导入与公钥导出（docs/SYNC_ENGINE.md §8）。纯 JVM，不涉及存储：
 * 私钥字节由 app 层放进 SecretStore 加密保存。
 *
 * 存储形式统一为 **OpenSSH v1 格式、不加口令** 的私钥文本（`-----BEGIN OPENSSH PRIVATE KEY-----`）：
 * 导入时先用用户给的口令解密再重新导出，所以运行时不需要口令（秘密本身已由 Keystore 加密，见 D-022）。
 */
object SshKeys {

    /** 一把可用的密钥：[privateKey] 是 OpenSSH v1 未加密的 PEM 文本（UTF-8 字节）。 */
    class KeyMaterial(
        val privateKey: ByteArray,
        val publicKey: PublicKeyInfo,
    )

    /** 公钥信息，可以放心展示与记录。 */
    data class PublicKeyInfo(
        /** `ssh-ed25519` / `ssh-rsa` / `ecdsa-sha2-nistp256` … */
        val keyType: String,
        /** wire 格式公钥 blob。 */
        val blob: ByteArray,
        val comment: String,
    ) {
        val fingerprint: String get() = SshSupport.fingerprint(blob)

        /** `authorized_keys` 里的一行：`类型 base64 [注释]`。 */
        val line: String get() = buildString {
            append(keyType).append(' ').append(SshSupport.base64(blob))
            if (comment.isNotBlank()) append(' ').append(comment.trim())
        }

        override fun equals(other: Any?): Boolean =
            other is PublicKeyInfo && other.keyType == keyType && other.blob.contentEquals(blob) && other.comment == comment

        override fun hashCode(): Int = blob.contentHashCode()
    }

    class SshKeyException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
        enum class Reason {
            /** 不是能识别的私钥格式。 */
            INVALID,

            /** 私钥有口令保护但没有提供口令。 */
            PASSPHRASE_REQUIRED,

            /** 口令不对。 */
            WRONG_PASSPHRASE,

            /** 能解析但无法转换/使用（例如不支持的密钥类型）。 */
            UNSUPPORTED,
        }
    }

    private const val NO_COMMENT = "no comment"

    /** 生成一把新的 ed25519 密钥。[comment] 会写进公钥行与私钥文件（GitHub 上显示为密钥标题的默认值）。 */
    fun generateEd25519(comment: String): KeyMaterial {
        val jsch = JSch()
        val kp = try {
            KeyPair.genKeyPair(jsch, KeyPair.ED25519)
        } catch (e: JSchException) {
            throw SshKeyException(SshKeyException.Reason.UNSUPPORTED, "cannot generate ed25519 key: ${e.message}", e)
        }
        try {
            kp.publicKeyComment = comment.trim()
            return export(jsch, kp)
        } finally {
            kp.dispose()
        }
    }

    /**
     * 导入用户粘贴的私钥：支持 OpenSSH v1（`BEGIN OPENSSH PRIVATE KEY`，含口令保护）、传统 PEM（`BEGIN RSA/EC/DSA PRIVATE KEY`）、
     * PKCS#8 与 PuTTY `.ppk`。成功后返回重新导出的（未加密 OpenSSH v1）材料；[passphrase] 用完即清零。
     */
    fun import(privateKeyText: String, passphrase: CharArray?): KeyMaterial {
        val text = privateKeyText.trim().replace("\r\n", "\n")
        if (text.isEmpty()) throw SshKeyException(SshKeyException.Reason.INVALID, "private key is empty")
        val jsch = JSch()
        val kp = try {
            KeyPair.load(jsch, (text + "\n").toByteArray(Charsets.UTF_8), null)
        } catch (e: JSchException) {
            throw SshKeyException(SshKeyException.Reason.INVALID, "not a supported private key: ${e.message}", e)
        } catch (e: RuntimeException) {
            throw SshKeyException(SshKeyException.Reason.INVALID, "not a supported private key: ${e.message}", e)
        } ?: throw SshKeyException(SshKeyException.Reason.INVALID, "not a supported private key")
        try {
            if (kp.isEncrypted) {
                val pass = passphrase?.takeIf { it.isNotEmpty() }
                    ?: throw SshKeyException(SshKeyException.Reason.PASSPHRASE_REQUIRED, "the private key is protected by a passphrase")
                val bytes = utf8(pass)
                val ok = try {
                    kp.decrypt(bytes)
                } finally {
                    bytes.fill(0)
                }
                if (!ok) throw SshKeyException(SshKeyException.Reason.WRONG_PASSPHRASE, "wrong passphrase")
            }
            if (kp.publicKeyBlob == null) throw SshKeyException(SshKeyException.Reason.UNSUPPORTED, "cannot derive the public key")
            return export(jsch, kp)
        } finally {
            kp.dispose()
            passphrase?.fill('\u0000')
        }
    }

    /**
     * 从已存储的私钥文本读出公钥信息（用于展示指纹、写日志）。口令保护或无法解析时返回 null。
     * 传入的字节不会被修改。
     */
    fun publicKeyOf(privateKey: ByteArray): PublicKeyInfo? {
        val kp = try {
            KeyPair.load(JSch(), privateKey.copyOf(), null)
        } catch (e: Exception) {
            return null
        } ?: return null
        try {
            if (kp.isEncrypted) return null
            val blob = kp.publicKeyBlob ?: return null
            return PublicKeyInfo(kp.keyTypeString, blob.copyOf(), commentOf(kp))
        } finally {
            kp.dispose()
        }
    }

    private fun export(jsch: JSch, kp: KeyPair): KeyMaterial {
        val out = ByteArrayOutputStream()
        try {
            kp.writeOpenSSHv1PrivateKey(out, null)
        } catch (e: Exception) {
            throw SshKeyException(SshKeyException.Reason.UNSUPPORTED, "cannot export ${kp.keyTypeString} key: ${e.message}", e)
        }
        val exported = out.toByteArray()
        val blob = kp.publicKeyBlob ?: throw SshKeyException(SshKeyException.Reason.UNSUPPORTED, "public key unavailable")
        val info = PublicKeyInfo(kp.keyTypeString, blob.copyOf(), commentOf(kp))
        // 自检：导出的文本必须能重新读回并得到同一把公钥
        val check = publicKeyOf(exported)
        if (check == null || !check.blob.contentEquals(blob)) {
            throw SshKeyException(SshKeyException.Reason.UNSUPPORTED, "exported ${kp.keyTypeString} key does not round-trip")
        }
        return KeyMaterial(exported, info)
    }

    private fun commentOf(kp: KeyPair): String {
        val c = kp.publicKeyComment ?: return ""
        return if (c == NO_COMMENT) "" else c.trim()
    }

    private fun utf8(chars: CharArray): ByteArray {
        val bb = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars))
        val bytes = ByteArray(bb.remaining())
        bb.get(bytes)
        if (bb.hasArray()) bb.array().fill(0)
        return bytes
    }
}
