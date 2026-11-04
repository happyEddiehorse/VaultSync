package com.mountriv.vaultsync.gitcore

import com.mountriv.vaultsync.gitcore.log.InMemorySyncLogger
import com.mountriv.vaultsync.gitcore.model.ProgressSink
import com.mountriv.vaultsync.gitcore.model.SecretProvider
import com.mountriv.vaultsync.gitcore.model.SyncOutcome
import com.mountriv.vaultsync.gitcore.model.SyncStage
import com.mountriv.vaultsync.gitcore.repo.VaultRepository
import com.mountriv.vaultsync.gitcore.sync.JGitProgress
import com.mountriv.vaultsync.gitcore.sync.RepoOnboarding
import org.eclipse.jgit.lib.ObjectId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L-039：`commitAll` 只暂存变了的路径（不再 `add .` 把整个库读两遍），读文件时报进度、可以中途取消。
 */
class CommitAllTest {

    @BeforeEach
    fun setUp() = TestEnv.ensure()

    @AfterEach
    fun tearDown() = TestEnv.closeDesktops()

    private fun cloned(dir: File): File {
        val url = TestEnv.bareRemote(File(dir, "remote.git"))
        val desktop = TestEnv.cloneDesktop(url, File(dir, "desktop"))
        desktop.write("a.md", "a\n")
        desktop.write("Notes/b.md", "b\n")
        desktop.write("old/x.md", "x\n")
        desktop.writeBytes("big.bin", ByteArray(3 shl 20) { (it * 7).toByte() })
        desktop.commitAll("desktop 1")
        desktop.push()
        val vault = File(dir, "phone-vault")
        assertIs<RepoOnboarding.Result.Ready>(RepoOnboarding.clone(TestEnv.spec(vault, url), SecretProvider.NONE, InMemorySyncLogger()))
        return vault
    }

    private fun open(vault: File) = VaultRepository.open(vault, InMemorySyncLogger())

    /** 记下"读取变更的文件"任务的总量与累计进度。 */
    private class Recorder : ProgressSink {
        var total = -1
        var completed = 0

        override fun onTask(title: String, totalWork: Int) {
            if (title == VaultRepository.TASK_STAGING) total = totalWork
        }

        override fun onProgress(completed: Int) {
            if (total >= 0) this.completed = completed
        }
    }

    @Test
    fun `nothing changed reads no file and commits nothing`(@TempDir dir: File) {
        val vault = cloned(dir)
        open(vault).use { repo ->
            val rec = Recorder()
            assertNull(repo.commitAll(TestEnv.phone, JGitProgress(rec) { false }) { "x" })
            assertEquals(-1, rec.total, "no staging task when nothing changed")
        }
    }

    @Test
    fun `stages added, modified, deleted and file-to-directory changes only`(@TempDir dir: File) {
        val vault = cloned(dir)
        vault.writeVaultFile("a.md", "a2\n")
        vault.writeVaultFile("new/c.md", "c\n")
        assertTrue(File(vault, "Notes/b.md").delete())
        // old 原来是目录，现在换成同名文件
        assertTrue(File(vault, "old").deleteRecursively())
        vault.writeVaultFile("old", "now a file\n")
        open(vault).use { repo ->
            val rec = Recorder()
            var paths: List<String> = emptyList()
            assertNotNull(repo.commitAll(TestEnv.phone, JGitProgress(rec) { false }) { paths = it; "phone" })
            assertEquals(listOf("Notes/b.md", "a.md", "new/c.md", "old", "old/x.md"), paths)
            assertTrue(repo.status().isClean, "everything committed: ${repo.status().uncommittedChanges}")
            // 只读了改动的几个小文件，没有读 3 MB 的 big.bin
            assertTrue(rec.total in 0..1, "total KiB = ${rec.total}")
        }
    }

    @Test
    fun `reports progress in KiB while reading a large new file`(@TempDir dir: File) {
        val vault = cloned(dir)
        File(vault, "m7-big").mkdirs()
        File(vault, "m7-big/new.bin").writeBytes(ByteArray(2 shl 20) { (it * 13).toByte() })
        open(vault).use { repo ->
            val rec = Recorder()
            assertNotNull(repo.commitAll(TestEnv.phone, JGitProgress(rec) { false }) { "phone" })
            assertEquals(2048, rec.total)
            assertTrue(rec.completed >= 2048, "completed = ${rec.completed}")
        }
    }

    @Test
    fun `cancel while reading a large file stops the sync and leaves the repo usable`(@TempDir dir: File) {
        val vault = cloned(dir)
        File(vault, "big2.bin").writeBytes(ByteArray(4 shl 20) { (it * 3).toByte() })
        val spec = TestEnv.spec(vault, File(dir, "remote.git").toURI().toString())
        val cancel = AtomicBoolean(false)
        // 读到 1 MB 时请求取消
        val sink = object : ProgressSink {
            var staging = false
            override fun onTask(title: String, totalWork: Int) {
                staging = title == VaultRepository.TASK_STAGING
            }

            override fun onProgress(completed: Int) {
                if (staging && completed >= 1024) cancel.set(true)
            }
        }
        val engine = TestEnv.engine()
        val logger = InMemorySyncLogger()
        val outcome = engine.sync(spec, SecretProvider.NONE, TestEnv.phone, logger, sink) { cancel.get() }
        assertEquals(SyncOutcome.Cancelled(SyncStage.COMMIT_LOCAL), outcome, logger.toString())
        val head: ObjectId?
        open(vault).use { repo ->
            assertFalse(repo.hasIndexLock())
            assertEquals(setOf("big2.bin"), repo.status().untracked)
            head = repo.headId()
        }
        // 下一次同步正常提交并推送
        assertIs<SyncOutcome.Success>(TestEnv.sync(engine, spec))
        open(vault).use { repo ->
            assertTrue(repo.headId() != head)
            assertTrue(repo.status().isClean)
        }
    }
}
