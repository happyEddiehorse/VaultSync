package com.mountriv.vaultsync.gitcore

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.mountriv.vaultsync.gitcore.auth.SshKeys
import com.mountriv.vaultsync.gitcore.auth.SshSupport
import com.mountriv.vaultsync.gitcore.auth.VaultKnownHosts
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SSH 密钥的生成 / 导入 / 导出（docs/SYNC_ENGINE.md §8）。全部走 BouncyCastle 路径（SshSupport 强制）。 */
class SshKeysTest {

    @BeforeEach
    fun setUp() = TestEnv.ensure()

    @Test
    fun `generate ed25519 key and read it back`() {
        val m = SshKeys.generateEd25519("phone@vaultsync")
        val text = String(m.privateKey, Charsets.UTF_8)
        assertTrue(text.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----\n"), text.take(60))
        assertTrue(text.trimEnd().endsWith("-----END OPENSSH PRIVATE KEY-----"))
        assertEquals("ssh-ed25519", m.publicKey.keyType)
        assertEquals("phone@vaultsync", m.publicKey.comment)
        assertTrue(m.publicKey.line.startsWith("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI"), m.publicKey.line)
        assertTrue(m.publicKey.line.endsWith(" phone@vaultsync"))
        assertTrue(Regex("^SHA256:[A-Za-z0-9+/]{43}$").matches(m.publicKey.fingerprint), m.publicKey.fingerprint)

        val again = SshKeys.publicKeyOf(m.privateKey)
        assertNotNull(again)
        assertEquals(m.publicKey, again)

        // 导入自己导出的文本得到同一把公钥
        val imported = SshKeys.import(text, null)
        assertEquals(m.publicKey, imported.publicKey)
        assertContentEquals(m.publicKey.blob, imported.publicKey.blob)
    }

    @Test
    fun `import passphrase protected openssh key`() {
        val jsch = JSch()
        val kp = KeyPair.genKeyPair(jsch, KeyPair.ED25519)
        kp.publicKeyComment = "laptop"
        val out = ByteArrayOutputStream()
        kp.writeOpenSSHv1PrivateKey(out, "s3cret".toByteArray())
        val text = out.toString("UTF-8")
        val expectedBlob = kp.publicKeyBlob.copyOf()
        kp.dispose()

        val noPass = assertThrows<SshKeys.SshKeyException> { SshKeys.import(text, null) }
        assertEquals(SshKeys.SshKeyException.Reason.PASSPHRASE_REQUIRED, noPass.reason)
        val wrong = assertThrows<SshKeys.SshKeyException> { SshKeys.import(text, "nope".toCharArray()) }
        assertEquals(SshKeys.SshKeyException.Reason.WRONG_PASSPHRASE, wrong.reason)

        val pass = "s3cret".toCharArray()
        val m = SshKeys.import(text, pass)
        assertTrue(pass.all { it == '\u0000' }, "passphrase must be wiped")
        assertContentEquals(expectedBlob, m.publicKey.blob)
        assertEquals("laptop", m.publicKey.comment)
        // 重新导出的私钥不再有口令
        assertNotNull(SshKeys.publicKeyOf(m.privateKey))
        assertNull(SshKeys.publicKeyOf(text.toByteArray()), "encrypted key has no readable public key without passphrase")
    }

    @Test
    fun `import traditional pem rsa and ecdsa keys`() {
        val jsch = JSch()
        for ((type, size, expectedType) in listOf(
            Triple(KeyPair.RSA, 2048, "ssh-rsa"),
            Triple(KeyPair.ECDSA, 256, "ecdsa-sha2-nistp256"),
        )) {
            val kp = KeyPair.genKeyPair(jsch, type, size)
            val out = ByteArrayOutputStream()
            kp.writePrivateKey(out)
            val pem = out.toString("UTF-8")
            assertTrue(pem.contains("PRIVATE KEY-----"), pem.take(40))
            val expectedBlob = kp.publicKeyBlob.copyOf()
            kp.dispose()

            val m = SshKeys.import(pem, null)
            assertEquals(expectedType, m.publicKey.keyType)
            assertContentEquals(expectedBlob, m.publicKey.blob)
            assertEquals("", m.publicKey.comment)
            assertTrue(String(m.privateKey, Charsets.UTF_8).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
            assertEquals(m.publicKey, SshKeys.publicKeyOf(m.privateKey))
        }
    }

    @Test
    fun `garbage is rejected`() {
        assertEquals(SshKeys.SshKeyException.Reason.INVALID, assertThrows<SshKeys.SshKeyException> { SshKeys.import("", null) }.reason)
        assertEquals(
            SshKeys.SshKeyException.Reason.INVALID,
            assertThrows<SshKeys.SshKeyException> { SshKeys.import("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl", null) }.reason,
        )
        assertEquals(
            SshKeys.SshKeyException.Reason.INVALID,
            assertThrows<SshKeys.SshKeyException> { SshKeys.import("-----BEGIN OPENSSH PRIVATE KEY-----\nnot base64!!\n-----END OPENSSH PRIVATE KEY-----", null) }.reason,
        )
        assertNull(SshKeys.publicKeyOf("hello".toByteArray()))
    }

    @Test
    fun `fingerprints match the values github publishes`() {
        val published = mapOf(
            "ssh-ed25519" to "SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU",
            "ecdsa-sha2-nistp256" to "SHA256:p2QAMXNIC1TJYWeIOttrVc98/R1BUFWu3/LiyKgUfQM",
            "ssh-rsa" to "SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s",
        )
        val seen = HashMap<String, String>()
        for (line in VaultKnownHosts.GITHUB_SEED_LINES) {
            val parts = line.split(' ')
            val blob = SshSupport.decodeBase64(parts[2])!!
            assertEquals(parts[1], SshSupport.keyTypeOf(blob))
            seen[parts[1]] = SshSupport.fingerprint(blob)
        }
        assertEquals(published, seen)
        assertNull(SshSupport.keyTypeOf(ByteArray(3)))
        assertNull(SshSupport.keyTypeOf(byteArrayOf(0, 0, 0, 9, 1, 2)))
    }
}
