package com.mountriv.vaultsync.gitcore

import com.mountriv.vaultsync.gitcore.auth.TransportAuth
import com.mountriv.vaultsync.gitcore.log.InMemorySyncLogger
import com.mountriv.vaultsync.gitcore.model.GitIdentity
import com.mountriv.vaultsync.gitcore.repo.RepoInspector
import com.mountriv.vaultsync.gitcore.repo.VaultRepository
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.RepositoryState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M0 冒烟测试：验证 JGit 5.13 + 环境初始化 + 仓库基本操作在 JVM 上可用。
 */
class VaultRepositorySmokeTest {

    private val identity = GitIdentity("Tester", "tester@example.com")

    @BeforeEach
    fun setUp() = TestEnv.ensure()

    @AfterEach
    fun tearDown() = TestEnv.closeDesktops()

    @Test
    fun `init writes shared storage config and commits files`(@TempDir dir: File) {
        val vault = File(dir, "vault").apply { mkdirs() }
        File(vault, "Note.md").writeText("# hello\n")
        val logger = InMemorySyncLogger()

        VaultRepository.init(vault, "main", logger).use { repo ->
            repo.ensureSharedStorageConfig(remoteUrl = "https://example.com/owner/vault.git", branch = "main")

            val config = repo.repository.config
            assertEquals("false", config.getString("core", null, "filemode"))
            assertEquals("false", config.getString("core", null, "symlinks"))
            assertEquals("true", config.getString("core", null, "ignorecase"))
            assertEquals("false", config.getString("core", null, "autocrlf"))
            assertEquals(0, config.getInt("gc", "auto", -1))
            assertEquals(0, config.getInt("gc", "autopacklimit", -1))
            assertEquals("https://example.com/owner/vault.git", config.getString("remote", "origin", "url"))
            assertEquals("refs/heads/main", config.getString("branch", "main", "merge"))

            val first = repo.commitAll("vault backup: test", identity)
            assertNotNull(first)
            assertEquals("main", repo.currentBranch)
            assertEquals(RepositoryState.SAFE, repo.state)
            assertTrue(repo.status().isClean)

            // 第二次没有变更 → 不提交
            assertNull(repo.commitAll("noop", identity))

            // 修改 + 删除 + 新增都能被 add -A 等价流程捕获
            File(vault, "Note.md").writeText("# hello world\n")
            File(vault, "Other.md").writeText("other\n")
            val second = repo.commitAll("vault backup: 2", identity)
            assertNotNull(second)
            File(vault, "Other.md").delete()
            val third = repo.commitAll("vault backup: 3", identity)
            assertNotNull(third)
            assertTrue(repo.status().isClean)
        }

        // 配置写入是幂等的：再次打开并调用不应再触发保存日志
        val logger2 = InMemorySyncLogger()
        VaultRepository.open(vault, logger2).use { repo ->
            repo.ensureSharedStorageConfig(remoteUrl = "https://example.com/owner/vault.git", branch = "main")
        }
        assertFalse(logger2.lines.any { it.contains("config updated") }, logger2.toString())
    }

    @Test
    fun `push to bare remote and clone back`(@TempDir dir: File) {
        val remote = File(dir, "remote.git")
        Git.init().setBare(true).setDirectory(remote).setInitialBranch("main").call().close()

        val vault = File(dir, "vault").apply { mkdirs() }
        File(vault, "A.md").writeText("a\n")
        val remoteUri = remote.toURI().toString()

        VaultRepository.init(vault, "main", InMemorySyncLogger()).use { repo ->
            repo.ensureSharedStorageConfig(remoteUrl = remoteUri, branch = "main")
            repo.commitAll("initial", identity)
            val outcome = repo.push("main", TransportAuth.NONE)
            assertEquals("OK", outcome.status.name, outcome.message)
        }

        val clone = File(dir, "clone")
        Git.cloneRepository().setURI(remoteUri).setDirectory(clone).setBranch("main").call().close()
        assertEquals("a\n", File(clone, "A.md").readText())
        assertTrue(VaultRepository.isRepository(clone))
        assertFalse(VaultRepository.isRepository(File(dir, "nowhere")))

        // 只读检查：克隆出来的仓库干净、分支正确、远端可读
        File(clone, "B.md").writeText("b\n")
        val info = RepoInspector.inspect(clone)
        assertTrue(info.isRepository)
        assertEquals("main", info.branch)
        assertEquals(RepositoryState.SAFE, info.state)
        // JGit 会把 file:/C:/... 规范化为 file:///C:/...，只比较尾部
        assertTrue(info.remoteUrl!!.endsWith("/remote.git/"), info.remoteUrl)
        assertEquals(1, info.untracked)
        assertEquals(0, info.modified)
        assertFalse(info.hasIndexLock)
        assertFalse(RepoInspector.inspect(File(dir, "nowhere")).dirExists)
        assertFalse(RepoInspector.inspect(dir).isRepository)
    }
}
