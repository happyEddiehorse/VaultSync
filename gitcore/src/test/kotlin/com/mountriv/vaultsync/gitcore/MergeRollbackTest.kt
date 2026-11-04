package com.mountriv.vaultsync.gitcore

import com.mountriv.vaultsync.gitcore.log.InMemorySyncLogger
import com.mountriv.vaultsync.gitcore.model.ProgressSink
import com.mountriv.vaultsync.gitcore.model.SecretProvider
import com.mountriv.vaultsync.gitcore.model.SyncOutcome
import com.mountriv.vaultsync.gitcore.model.SyncStage
import com.mountriv.vaultsync.gitcore.repo.VaultRepository
import com.mountriv.vaultsync.gitcore.sync.RepoOnboarding
import org.eclipse.jgit.lib.RepositoryState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * L-036：合并中途出错（不是冲突）时回到合并前——不留 MERGE_HEAD（否则像"冲突已全部处理"）、不留 JGit 的检出临时文件。
 * 用进度回调在"Checking out files"时抛异常来模拟 Android 上检出大文件时的崩溃，并在那一刻放一个 JGit 式的临时文件。
 */
class MergeRollbackTest {

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

    /** 合并阶段第一次检出文件时：在 [tempDir] 放一个检出临时文件，然后抛异常。只发作一次。 */
    private class FailingCheckout(private val tempDir: File) : ProgressSink {
        private var stage: SyncStage? = null
        var fired = false

        override fun onStage(stage: SyncStage) {
            this.stage = stage
        }

        override fun onTask(title: String, totalWork: Int) {
            if (!fired && stage == SyncStage.MERGE && title == "Checking out files") {
                fired = true
                File(tempDir, "._2026-09-19.md5308921148253916399.tmp").writeText("half written")
                throw IllegalStateException("simulated checkout failure")
            }
        }
    }

    private fun VaultRepository.Companion.state(vault: File) = open(vault, InMemorySyncLogger()).use { it.state to it.headId()!!.name }

    @Test
    fun `failed three-way merge is rolled back instead of left half done`(@TempDir dir: File) {
        val (url, desktop, vault) = setup(dir)
        val spec = TestEnv.spec(vault, url)
        desktop.write("Daily/2026-09-19.md", "daily from desktop\n")
        desktop.commitAll("desktop 2")
        desktop.push()
        vault.writeVaultFile("Inbox/Idea.md", "idea from phone\n")
        // 用户自己的、看起来像但不是 JGit 临时文件的文件不能被删
        vault.writeVaultFile("Daily/._notes.md", "mine\n")

        val engine = TestEnv.engine()
        val sink = FailingCheckout(File(vault, "Daily"))
        val logger = InMemorySyncLogger()
        val failed = engine.sync(spec, SecretProvider.NONE, TestEnv.phone, logger, sink)
        assertTrue(sink.fired)
        assertIs<SyncOutcome.Failed>(failed, logger.toString())
        assertEquals(SyncStage.MERGE, failed.stage)

        assertEquals(RepositoryState.SAFE, VaultRepository.state(vault).first)
        assertFalse(File(vault, ".git/MERGE_HEAD").exists())
        assertFalse(File(vault, "Daily/._2026-09-19.md5308921148253916399.tmp").exists())
        assertEquals("mine\n", vault.readVaultFile("Daily/._notes.md"))
        // 手机的本地提交还在，桌面的改动还没进来
        assertEquals("idea from phone\n", vault.readVaultFile("Inbox/Idea.md"))
        assertEquals("daily\n", vault.readVaultFile("Daily/2026-09-19.md"))

        // 下一次同步正常合并，不会把检出了一半的结果或临时文件提交上去
        val ok = TestEnv.sync(engine, spec)
        assertIs<SyncOutcome.Success>(ok)
        assertEquals(null, ok.summary.localCommitId) // 手机的提交上一次已经做了，这次没有新的本地改动（临时文件已删）
        assertEquals("MERGED", ok.summary.mergeStatus)
        desktop.pull()
        assertEquals("idea from phone\n", desktop.read("Inbox/Idea.md"))
        assertEquals("daily from desktop\n", vault.readVaultFile("Daily/2026-09-19.md"))
        assertFalse(File(desktop.dir, "Daily/._2026-09-19.md5308921148253916399.tmp").exists())
    }

    @Test
    fun `failed fast-forward is rolled back`(@TempDir dir: File) {
        val (url, desktop, vault) = setup(dir)
        val spec = TestEnv.spec(vault, url)
        val before = VaultRepository.state(vault).second
        desktop.write("Daily/2026-09-19.md", "daily from desktop\n")
        desktop.commitAll("desktop 2")
        desktop.push()

        val engine = TestEnv.engine()
        val sink = FailingCheckout(File(vault, "Daily"))
        val failed = engine.sync(spec, SecretProvider.NONE, TestEnv.phone, InMemorySyncLogger(), sink)
        assertIs<SyncOutcome.Failed>(failed)
        assertEquals(RepositoryState.SAFE to before, VaultRepository.state(vault))
        assertFalse(File(vault, "Daily/._2026-09-19.md5308921148253916399.tmp").exists())
        assertEquals("daily\n", vault.readVaultFile("Daily/2026-09-19.md"))

        val ok = TestEnv.sync(engine, spec)
        assertIs<SyncOutcome.Success>(ok)
        assertEquals("FAST_FORWARD", ok.summary.mergeStatus)
        assertEquals("daily from desktop\n", vault.readVaultFile("Daily/2026-09-19.md"))
    }
}
