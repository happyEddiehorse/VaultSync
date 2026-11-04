package com.mountriv.vaultsync.gitcore.auth

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Slf4jLogger
import java.security.MessageDigest
import java.util.Base64

/**
 * JSch 的进程级设置（docs/SYNC_ENGINE.md §8），由 [com.mountriv.vaultsync.gitcore.GitEnvironment.install] 调用一次。
 *
 * - JSch 自己的日志（连接、算法协商、主机密钥检查、认证结果）经 SLF4J 进入应用日志；
 * - ed25519 / X25519 统一用 BouncyCastle 实现：Android 上 JSch 的 `JavaVersion` 基线类固定返回 8，本来就只能走
 *   `com.jcraft.jsch.bc.*`（L-003）；桌面 JVM 上强制一致，这样 JVM 测试覆盖的就是真机的代码路径。
 */
object SshSupport {

    @Volatile
    private var installed = false

    @Synchronized
    fun install() {
        if (installed) return
        JSch.setLogger(Slf4jLogger())
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
        JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
        installed = true
    }

    /** SSH 公钥 blob（wire 格式：`uint32 长度 + 类型名 + …`）里的密钥类型名，如 `ssh-ed25519`；格式不对返回 null。 */
    fun keyTypeOf(blob: ByteArray): String? {
        if (blob.size < 4) return null
        val len = ((blob[0].toInt() and 0xff) shl 24) or ((blob[1].toInt() and 0xff) shl 16) or
            ((blob[2].toInt() and 0xff) shl 8) or (blob[3].toInt() and 0xff)
        if (len <= 0 || len > 64 || 4 + len > blob.size) return null
        val name = String(blob, 4, len, Charsets.US_ASCII)
        return if (name.all { it.isLetterOrDigit() || it == '-' || it == '@' || it == '.' }) name else null
    }

    /** OpenSSH 风格指纹：`SHA256:` + 公钥 blob 的 SHA-256（无填充 base64），与 `ssh-keygen -l` / GitHub 显示一致。 */
    fun fingerprint(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    /** 公钥的 base64（带填充，known_hosts / authorized_keys 用的形式）。 */
    fun base64(blob: ByteArray): String = Base64.getEncoder().encodeToString(blob)

    fun decodeBase64(text: String): ByteArray? = try {
        Base64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        null
    }
}
