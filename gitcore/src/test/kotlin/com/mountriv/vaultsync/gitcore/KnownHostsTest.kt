package com.mountriv.vaultsync.gitcore

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.mountriv.vaultsync.gitcore.auth.SshSupport
import com.mountriv.vaultsync.gitcore.auth.VaultKnownHosts
import com.mountriv.vaultsync.gitcore.log.InMemorySyncLogger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 应用自己的 known_hosts：预置 GitHub、TOFU、密钥变化拒绝、忘记主机（docs/SYNC_ENGINE.md §8）。 */
class KnownHostsTest {

    private fun blob(type: String, payload: ByteArray): ByteArray {
        val name = type.toByteArray(Charsets.US_ASCII)
        val out = ByteArray(4 + name.size + 4 + payload.size)
        out[3] = name.size.toByte()
        System.arraycopy(name, 0, out, 4, name.size)
        out[4 + name.size + 3] = payload.size.toByte()
        System.arraycopy(payload, 0, out, 8 + name.size, payload.size)
        return out
    }

    private val ed1 = blob("ssh-ed25519", ByteArray(32) { it.toByte() })
    private val ed2 = blob("ssh-ed25519", ByteArray(32) { (it + 1).toByte() })
    private val ec1 = blob("ecdsa-sha2-nistp256", ByteArray(65) { 7 })

    @Test
    fun `fresh file is seeded with github keys`(@TempDir dir: File) {
        val logger = InMemorySyncLogger()
        val kh = VaultKnownHosts(File(dir, "ssh/known_hosts"), logger)
        val hosts = kh.hosts()
        assertEquals(9, hosts.size, hosts.toString())
        assertEquals(setOf("github.com", "ssh.github.com", "[ssh.github.com]:443"), hosts.map { it.host }.toSet())
        assertEquals(
            "SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU",
            hosts.single { it.host == "[ssh.github.com]:443" && it.type == "ssh-ed25519" }.fingerprint,
        )
        val text = kh.file.readText()
        assertTrue(text.startsWith("# VaultSync known_hosts"), text.take(80))
        assertEquals(3, kh.getHostKey("github.com", null).size)
        assertEquals(1, kh.getHostKey("[ssh.github.com]:443", "ssh-ed25519").size)
        assertEquals("ssh-ed25519", kh.getHostKey("[ssh.github.com]:443", "ssh-ed25519").single().type)
        assertEquals(9, kh.getHostKey().size)

        // 预置的密钥能校验通过；改过的密钥被拒绝
        val seed = SshSupport.decodeBase64(VaultKnownHosts.GITHUB_SEED_LINES[0].split(' ')[2])!!
        assertEquals(HostKeyRepository.OK, kh.check("github.com", seed))
        assertEquals(HostKeyRepository.OK, kh.check("[ssh.github.com]:443", seed))
        val tampered = seed.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertEquals(HostKeyRepository.CHANGED, kh.check("github.com", tampered))
        assertTrue(logger.lines.any { it.contains("HOST KEY CHANGED for 'github.com'") }, logger.toString())
    }

    @Test
    fun `trust on first use then refuse changes until forgotten`(@TempDir dir: File) {
        val logger = InMemorySyncLogger()
        val kh = VaultKnownHosts(File(dir, "known_hosts"), logger)
        val host = "[nas.local]:2222"

        assertEquals(HostKeyRepository.OK, kh.check(host, ed1))
        assertEquals(1, logger.lines.count { it.contains("first connection to '$host'") }, logger.toString())
        assertEquals(HostKeyRepository.OK, kh.check(host, ed1))
        assertEquals(1, logger.lines.count { it.contains("first connection") })
        assertEquals(SshSupport.fingerprint(ed1), kh.hosts().single { it.host == host }.fingerprint)

        // 同类型不同密钥 → CHANGED；已知主机换了类型 → CHANGED
        assertEquals(HostKeyRepository.CHANGED, kh.check(host, ed2))
        assertEquals(HostKeyRepository.CHANGED, kh.check(host, ec1))
        assertTrue(logger.lines.any { it.contains("is known with ssh-ed25519 but presented a ecdsa-sha2-nistp256") }, logger.toString())
        // 裸主机名与带端口的是两个不同的主机
        assertEquals(HostKeyRepository.OK, kh.check("nas.local", ed2))
        assertEquals(2, kh.hosts().count { it.host.contains("nas.local") })

        assertEquals(1, kh.forget(host))
        assertEquals(0, kh.forget(host))
        assertEquals(HostKeyRepository.OK, kh.check(host, ed2))
        assertEquals(SshSupport.fingerprint(ed2), kh.hosts().single { it.host == host }.fingerprint)

        // JSch 接口：remove / add
        kh.remove(host, "ssh-ed25519")
        assertTrue(kh.hosts().none { it.host == host })
        kh.add(HostKey(host, ed1), null)
        assertEquals(HostKeyRepository.OK, kh.check(host, ed1))
        assertEquals(HostKeyRepository.NOT_INCLUDED, kh.check(host, byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `unknown lines survive rewrites and forgetting one host keeps the others`(@TempDir dir: File) {
        val file = File(dir, "known_hosts")
        file.writeText(
            "# my comment\n" +
                "|1|abc=|def= ssh-ed25519 ${SshSupport.base64(ed1)}\n" +
                "garbage line\n" +
                "a.example.com,b.example.com ssh-ed25519 ${SshSupport.base64(ed1)} shared\n",
        )
        val kh = VaultKnownHosts(file, InMemorySyncLogger())
        assertEquals(listOf("a.example.com", "b.example.com"), kh.hosts().map { it.host })
        assertEquals(HostKeyRepository.OK, kh.check("c.example.com", ec1))
        val text = file.readText()
        assertTrue(text.contains("# my comment\n"))
        assertTrue(text.contains("|1|abc=|def= ssh-ed25519"))
        assertTrue(text.contains("garbage line\n"))
        assertTrue(text.contains("a.example.com,b.example.com ssh-ed25519 ${SshSupport.base64(ed1)} shared\n"))
        assertTrue(text.contains("c.example.com ecdsa-sha2-nistp256 ${SshSupport.base64(ec1)}\n"))
        assertFalse(File(dir, "known_hosts.tmp").exists())

        assertEquals(1, kh.forget("A.EXAMPLE.COM"))
        assertEquals(listOf("b.example.com", "c.example.com"), kh.hosts().map { it.host })
        assertEquals(HostKeyRepository.OK, kh.check("b.example.com", ed1))
    }
}
