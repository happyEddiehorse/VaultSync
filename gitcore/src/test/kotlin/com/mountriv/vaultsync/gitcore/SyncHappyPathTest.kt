package com.mountriv.vaultsync.gitcore

import com.mountriv.vaultsync.gitcore.log.InMemorySyncLogger
import com.mountriv.vaultsync.gitcore.model.SecretProvider
import com.mountriv.vaultsync.gitcore.model.SyncOutcome
import com.mountriv.vaultsync.gitcore.model.SyncStage
import com.mountriv.vaultsync.gitcore.repo.RepoInspector
import com.mountriv.vaultsync.gitcore.sync.RepoOnboarding
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 桌面 + 手机往返同步，无冲突场景（S1、S4、S7、S8）。 */
class SyncHappyPathTest {

    @BeforeEach
    fun setUp() = TestEnv.ensure()

    @AfterEach
    fun tearDown() = TestEnv.closeDesktops()

    private fun setup(dir: File): Triple<String, TestEnv.Desktop, File> {
        val url = TestEnv.bareRemote(File(dir, "remote.git"))
        val desktop = TestEnv.cloneDesktop(url, File(dir, "desktop"))
        desktop.write("Inbox/Idea.md", "idea v1\n")
        desktop.write("Daily/2026-09-19.md", "daily\n")
        desktop.commitAll("desktop 1")
        desktop.push()
        val vault = File(dir, "phone-vault")
        assertIs<RepoOnboarding.Result.Ready>(RepoOnboarding.clone(TestEnv.spec(vault, url), SecretProvider.NONE, InMemorySyncLogger()))
        return Triple(url, desktop, vault)
    }

    @Test
    fun `round trip between desktop and phone`(@TempDir dir: File) {
        val (url, desktop, vault) = setup(dir)
        val spec = TestEnv.spec(vault, url)
        val engine = TestEnv.engine()

        // 手机改一个文件、新增一个文件、删除一个文件 → 同步 → 桌面拉取后一致
        vault.writeVaultFile("Inbox/Idea.md", "idea v2 from phone\n")
        vault.writeVaultFile("Mobile/Quick.md", "quick note\n")
        File(vault, "Daily/2026-09-19.md").delete()
        val logger = InMemorySyncLogger()
        val o1 = TestEnv.sync(engine, spec, logger)
        assertIs<SyncOutcome.Success>(o1, logger.toString())
        assertEquals(3, o1.summary.localFilesCommitted)
        assertEquals("ALREADY_UP_TO_DATE", o1.summary.mergeStatus)
        assertEquals("OK", o1.summary.pushStatus)
        assertNotNull(o1.summary.localCommitId)

        desktop.pull()
        assertEquals("idea v2 from phone\n", desktop.read("Inbox/Idea.md"))
        assertEquals("quick note\n", desktop.read("Mobile/Quick.md"))
        assertFalse(File(desktop.dir, "Daily/2026-09-19.md").exists())
        val msg = desktop.git.log().setMaxCount(1).call().first().fullMessage
        assertEquals("vault backup: 2026-09-19 13:05:09", msg)

        // 桌面改动 → 手机同步（快进）
        desktop.write("Inbox/Idea.md", "idea v3 from desktop\n")
        desktop.commitAll("desktop 2")
        desktop.push()
        val o2 = TestEnv.sync(engine, spec)
        assertIs<SyncOutcome.Success>(o2)
        assertNull(o2.summary.localCommitId)
        assertEquals("FAST_FORWARD", o2.summary.mergeStatus)
        assertEquals("UP_TO_DATE", o2.summary.pushStatus)
        assertEquals("idea v3 from desktop\n", vault.readVaultFile("Inbox/Idea.md"))

        // 什么都没变
        val o3 = TestEnv.sync(engine, spec)
        assertIs<SyncOutcome.Success>(o3)
        assertNull(o3.summary.localCommitId)
        assertEquals("ALREADY_UP_TO_DATE", o3.summary.mergeStatus)
        assertEquals("UP_TO_DATE", o3.summary.pushStatus)
        assertTrue(RepoInspector.inspect(vault).residues.isEmpty())
    }

    @Test
    fun `concurrent edits to different files merge automatically`(@TempDir dir: File) {
        val (url, desktop, vault) = setup(dir)
        val spec = TestEnv.spec(vault, url)

        desktop.write("Daily/2026-09-19.md", "daily from desktop\n")
        desktop.commitAll("desktop 2")
        desktop.push()
        vault.writeVaultFile("Inbox/Idea.md", "idea from phone\n")

        val o = TestEnv.sync(TestEnv.engine(), spec)
        assertIs<SyncOutcome.Success>(o)
        assertEquals("MERGED", o.summary.mergeStatus)
        assertEquals("OK", o.summary.pushStatus)
        assertEquals("daily from desktop\n", vault.readVaultFile("Daily/2026-09-19.md"))

        desktop.pull()
        assertEquals("idea from phone\n", desktop.read("Inbox/Idea.md"))
        val head = desktop.git.log().setMaxCount(1).call().first()
        assertEquals(2, head.parentCount, "merge commit expected")
        assertEquals("Merge remote-tracking branch 'origin/main'", head.fullMessage)
    }

    @Test
    fun `same edit on both sides merges cleanly`(@TempDir dir: File) {
        val (url, desktop, vault) = setup(dir)
        val spec = TestEnv.spec(vault, url)
        desktop.write("Inbox/Idea.md", "same content\n")
        desktop.commitAll("desktop 2")
        desktop.push()
        vault.writeVaultFile("Inbox/Idea.md", "same content\n")
        val o = TestEnv.sync(TestEnv.engine(), spec)
        assertIs<SyncOutcome.Success>(o)
        assertEquals("MERGED", o.summary.mergeStatus)
    }

    @Test
    fun `unreachable remote fails at fetch without touching local commit`(@TempDir dir: File) {
        val (url, _, vault) = setup(dir)
        val badSpec = TestEnv.spec(vault, url).copy(remoteUrl = File(dir, "missing.git").toURI().toString())
        vault.writeVaultFile("Mobile/New.md", "new\n")
        val o = TestEnv.sync(TestEnv.engine(), badSpec)
        assertIs<SyncOutcome.Failed>(o)
        assertEquals(SyncStage.FETCH, o.stage)
        // 本地提交已经完成，下次同步只需推送
        val info = RepoInspector.inspect(vault)
        assertEquals(0, info.untracked + info.modified)
        assertTrue(info.residues.isEmpty(), info.residues.toString())

        val ok = TestEnv.sync(TestEnv.engine(), TestEnv.spec(vault, url))
        assertIs<SyncOutcome.Success>(ok)
        assertEquals("OK", ok.summary.pushStatus)
    }

    @Test
    fun `two vaults sync independently`(@TempDir dir: File) {
        val url1 = TestEnv.bareRemote(File(dir, "r1.git"))
        val url2 = TestEnv.bareRemote(File(dir, "r2.git"))
        val v1 = File(dir, "v1").apply { mkdirs(); writeVaultFile("a.md", "1\n") }
        val v2 = File(dir, "v2").apply { mkdirs(); writeVaultFile("b.md", "2\n") }
        val s1 = TestEnv.spec(v1, url1)
        val s2 = TestEnv.spec(v2, url2)
        assertIs<RepoOnboarding.Result.Ready>(RepoOnboarding.initAndPush(s1, SecretProvider.NONE, TestEnv.phone, InMemorySyncLogger()))
        assertIs<RepoOnboarding.Result.Ready>(RepoOnboarding.initAndPush(s2, SecretProvider.NONE, TestEnv.phone, InMemorySyncLogger()))
        val engine = TestEnv.engine()
        v1.writeVaultFile("a.md", "1b\n")
        assertIs<SyncOutcome.Success>(TestEnv.sync(engine, s1))
        assertIs<SyncOutcome.Success>(TestEnv.sync(engine, s2))
        TestEnv.cloneDesktop(url1, File(dir, "d1")).use { assertEquals("1b\n", it.read("a.md")) }
        TestEnv.cloneDesktop(url2, File(dir, "d2")).use { assertEquals("2\n", it.read("b.md")) }
    }
}
