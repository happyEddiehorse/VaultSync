package com.mountriv.vaultsync.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.mountriv.vaultsync.gitcore.log.SyncLogger
import com.mountriv.vaultsync.gitcore.model.FailureKind
import com.mountriv.vaultsync.gitcore.model.SecretProvider
import com.mountriv.vaultsync.gitcore.model.SyncException
import com.mountriv.vaultsync.gitcore.model.SyncStage
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 秘密存储（docs/ANDROID_PLATFORM.md §5、D-019）：AndroidKeyStore 里的 AES-256 密钥 + AES/GCM 加密，
 * 密文写 `filesDir/secrets/<id>.bin`（格式：1 字节 IV 长度 + IV + 密文）。
 *
 * 解密失败只把**该秘密**报告为 [Status.UNREADABLE]，不删除任何文件，不影响其它秘密与仓库绑定。
 */
class KeystoreSecretStore(private val dir: File, private val logger: SyncLogger) {

    enum class Status { PRESENT, MISSING, UNREADABLE }

    class UnreadableSecretException(id: String, cause: Throwable) :
        Exception("secret '$id' cannot be decrypted (${cause.javaClass.simpleName}); please enter it again", cause)

    /** gitcore 用的提供者：缺失返回 null；解密失败抛 [SyncException]，让同步在 PRECHECK 阶段给出明确提示。 */
    val provider: SecretProvider = SecretProvider { id ->
        try {
            get(id)
        } catch (e: UnreadableSecretException) {
            throw SyncException(SyncStage.PRECHECK, e.message ?: "secret unreadable", e, FailureKind.CREDENTIAL_UNREADABLE)
        }
    }

    fun status(id: String): Status {
        if (!fileFor(id).isFile) return Status.MISSING
        return try {
            get(id)?.fill('\u0000')
            Status.PRESENT
        } catch (e: UnreadableSecretException) {
            Status.UNREADABLE
        }
    }

    /** 读取并解密；文件不存在返回 null；解密失败抛 [UnreadableSecretException]。 */
    fun get(id: String): CharArray? {
        val file = fileFor(id)
        if (!file.isFile) return null
        val bytes = file.readBytes()
        try {
            val ivLen = bytes[0].toInt() and 0xFF
            val iv = bytes.copyOfRange(1, 1 + ivLen)
            val cipherText = bytes.copyOfRange(1 + ivLen, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            val plain = cipher.doFinal(cipherText)
            return decodeAndWipe(plain)
        } catch (e: AEADBadTagException) {
            throw UnreadableSecretException(id, e)
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw UnreadableSecretException(id, e)
        } catch (e: java.security.GeneralSecurityException) {
            throw UnreadableSecretException(id, e)
        } catch (e: IndexOutOfBoundsException) {
            throw UnreadableSecretException(id, e)
        }
    }

    /** 加密并原子写入。 */
    fun put(id: String, value: CharArray) {
        dir.mkdirs()
        val plain = encode(value)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain)
        plain.fill(0)
        val out = ByteArray(1 + iv.size + cipherText.size)
        out[0] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(cipherText, 0, out, 1 + iv.size, cipherText.size)
        val file = fileFor(id)
        val tmp = File(dir, "$id.tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "cannot write secret file ${file.name}" }
        }
        logger.info("secret '$id' stored")
    }

    fun delete(id: String) {
        if (fileFor(id).delete()) logger.info("secret '$id' deleted")
    }

    /** 已保存的全部秘密 id（按文件名，不解密）。 */
    fun ids(): List<String> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".bin") }?.map { it.name.removeSuffix(".bin") }?.sorted() ?: emptyList()

    private fun fileFor(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9._-]+"))) { "bad secret id: $id" }
        return File(dir, "$id.bin")
    }

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        logger.info("generated keystore master key '$ALIAS'")
        return generator.generateKey()
    }

    private fun encode(chars: CharArray): ByteArray {
        val bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(bb.remaining())
        bb.get(bytes)
        return bytes
    }

    private fun decodeAndWipe(bytes: ByteArray): CharArray {
        val cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes))
        val chars = CharArray(cb.remaining())
        cb.get(chars)
        bytes.fill(0)
        return chars
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "vaultsync-master"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128

        /** HTTPS 令牌的秘密 id 约定。 */
        fun httpsTokenId(repoId: String) = "https-$repoId"
    }
}
