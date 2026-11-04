package com.mountriv.vaultsync.gitcore

import com.mountriv.vaultsync.gitcore.auth.VaultKnownHosts
import com.mountriv.vaultsync.gitcore.log.InMemorySyncLogger
import com.mountriv.vaultsync.gitcore.model.AuthSpec
import com.mountriv.vaultsync.gitcore.model.GitIdentity
import com.mountriv.vaultsync.gitcore.model.RepoSpec
import com.mountriv.vaultsync.gitcore.store.BackupCrypto
import com.mountriv.vaultsync.gitcore.store.BackupDocument
import com.mountriv.vaultsync.gitcore.store.BackupException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 配置备份（D-019 第 5 条）：文档格式、口令加密、known_hosts 合并。 */
class BackupTest {

    /** 测试里用较少的迭代次数，避免拖慢构建；真实导出用 [BackupCrypto.DEFAULT_ITERATIONS]。 */
    private val iterations = 10_000

    @Test
    fun `document round trip keeps repositories and opaque app data`() {
        val repos = listOf(
            RepoSpec("r1", "Vault", "/storage/emulated/0/Documents/Vault", "https://github.com/o/v.git", auth = AuthSpec.HttpsToken("o", "https-r1")),
            RepoSpec(
                "r2", "Work", "/storage/emulated/0/Documents/Work", "ssh://git@ssh.github.com:443/o/w.git",
                auth = AuthSpec.SshKey("ssh-key-1"), identity = GitIdentity("Me", "me@example.com"),
            ),
        )
        val sealed = BackupCrypto.seal(mapOf("https-r1" to "ghp_token", "ssh-key-1" to "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n"), "correct horse".toCharArray(), iterations)
        val doc = BackupDocument(
            createdAt = "2026-09-23T17:00:00+08:00",
            appVersion = "0.2.0",
            settings = buildJsonObject { put("authorName", JsonPrimitive("Me")) },
            repos = repos,
            knownHosts = "github.com ssh-ed25519 AAAA\n",
            secrets = sealed,
        )
        val text = BackupDocument.encode(doc)
        assertTrue(!text.contains("ghp_token"), "secrets must not appear in clear text")
        val back = BackupDocument.decode(text)
        assertEquals(doc, back)
        assertEquals(AuthSpec.SshKey("ssh-key-1"), back.repos[1].auth)

        val secrets = BackupCrypto.open(back.secrets!!, "correct horse".toCharArray())
        assertEquals("ghp_token", secrets["https-r1"])
        assertTrue(secrets.getValue("ssh-key-1").startsWith("-----BEGIN OPENSSH"))
    }

    @Test
    fun `wrong passphrase and tampering are detected`() {
        val sealed = BackupCrypto.seal(mapOf("a" to "b"), "right".toCharArray(), iterations)
        val wrong = assertFailsWith<BackupException> { BackupCrypto.open(sealed, "wrong".toCharArray()) }
        assertEquals(BackupException.Reason.WRONG_PASSPHRASE, wrong.reason)

        val bytes = java.util.Base64.getDecoder().decode(sealed.data)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        val tampered = sealed.copy(data = java.util.Base64.getEncoder().encodeToString(bytes))
        assertEquals(BackupException.Reason.WRONG_PASSPHRASE, assertFailsWith<BackupException> { BackupCrypto.open(tampered, "right".toCharArray()) }.reason)

        // 同样的内容每次加密结果不同（随机盐与 IV）
        assertNotEquals(sealed.data, BackupCrypto.seal(mapOf("a" to "b"), "right".toCharArray(), iterations).data)
    }

    @Test
    fun `export check accepts the same bytes and refuses short, changed or unreadable files`() {
        val text = BackupDocument.encode(BackupDocument(createdAt = "2026-10-31T10:00:00+08:00", appVersion = "0.4.0"))
        val bytes = text.toByteArray(Charsets.UTF_8)
        BackupDocument.verifyWritten(bytes, bytes.copyOf())

        // 写入被截断、内容被改、读不回来、旧文件的尾巴没被截掉
        assertFailsWith<IllegalStateException> { BackupDocument.verifyWritten(bytes, bytes.copyOf(bytes.size - 10)) }
        val changed = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertFailsWith<IllegalStateException> { BackupDocument.verifyWritten(bytes, changed) }
        assertFailsWith<IllegalStateException> { BackupDocument.verifyWritten(bytes, null) }
        assertFailsWith<IllegalStateException> { BackupDocument.verifyWritten(bytes, bytes + "garbage".toByteArray()) }
        // 字节相同但不是备份文件（导出了别的东西）也不算通过
        val notBackup = "hello".toByteArray()
        assertFailsWith<BackupException> { BackupDocument.verifyWritten(notBackup, notBackup.copyOf()) }
    }

    @Test
    fun `sealed secrets open with the passphrase after the built-in check`() {
        val secrets = mapOf("https-r1" to "ghp_token", "ssh-key-1" to "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n")
        val sealed = BackupCrypto.seal(secrets, "pass phrase".toCharArray(), iterations)
        assertEquals(secrets, BackupCrypto.open(sealed, "pass phrase".toCharArray()))
    }

    @Test
    fun `foreign, newer and broken files are refused with a reason`() {
        assertEquals(BackupException.Reason.NOT_A_BACKUP, assertFailsWith<BackupException> { BackupDocument.decode("hello") }.reason)
        assertEquals(
            BackupException.Reason.NOT_A_BACKUP,
            assertFailsWith<BackupException> { BackupDocument.decode("""{"format":"something-else","version":1}""") }.reason,
        )
        assertEquals(
            BackupException.Reason.TOO_NEW,
            assertFailsWith<BackupException> { BackupDocument.decode("""{"format":"vaultsync-backup","version":99,"createdAt":"x"}""") }.reason,
        )
        assertEquals(
            BackupException.Reason.CORRUPT,
            assertFailsWith<BackupException> { BackupDocument.decode("""{"format":"vaultsync-backup","version":1,"repos":"oops"}""") }.reason,
        )
        // 未来版本加的字段被忽略
        val ok = BackupDocument.decode("""{"format":"vaultsync-backup","version":1,"createdAt":"x","futureField":{"a":1}}""")
        assertEquals("x", ok.createdAt)
    }

    @Test
    fun `known hosts merge adds new hosts and never replaces a local key`(@TempDir dir: File) {
        val logger = InMemorySyncLogger()
        val local = VaultKnownHosts(File(dir, "known_hosts"), logger)
        val seeded = local.hosts()
        assertTrue(seeded.any { it.host == "github.com" })

        val otherKey = VaultKnownHosts(File(dir, "other_known_hosts"), logger)
        val keyA = java.util.Base64.getDecoder().decode("AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl")
        val keyB = keyA.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        otherKey.putForTest("[git.example.com]:2222", "ssh-ed25519", keyA)
        otherKey.putForTest("github.com", "ssh-ed25519", keyB)

        val added = local.mergeFrom(otherKey.exportText())
        assertEquals(1, added)
        assertTrue(local.hosts().any { it.host == "[git.example.com]:2222" })
        val githubEd = local.hosts().filter { it.host == "github.com" && it.type == "ssh-ed25519" }
        assertEquals(1, githubEd.size)
        assertEquals(seeded.first { it.host == "github.com" && it.type == "ssh-ed25519" }.fingerprint, githubEd.single().fingerprint)
        assertEquals(0, local.mergeFrom(otherKey.exportText()), "merging twice adds nothing")
    }
}
