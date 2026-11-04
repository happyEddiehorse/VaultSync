package com.mountriv.vaultsync.gitcore

import com.mountriv.vaultsync.gitcore.log.InMemorySyncLogger
import com.mountriv.vaultsync.gitcore.model.SecretProvider
import com.mountriv.vaultsync.gitcore.model.SyncOutcome
import com.mountriv.vaultsync.gitcore.repo.GitIgnoreTemplate
import com.mountriv.vaultsync.gitcore.repo.RepoHistory
import com.mountriv.vaultsync.gitcore.repo.RepoMaintenance
import com.mountriv.vaultsync.gitcore.repo.VaultRepository
import com.mountriv.vaultsync.gitcore.sync.RepoOnboarding
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** M5：仓库维护（repack）、提交历史、.gitignore 追加推荐规则。 */
class MaintenanceAndHistoryTest {

    @BeforeEach
    fun setUp() = TestEnv.ensure()

    @AfterEach
    fun tearDown() = TestEnv.closeDesktops()

    @Test
    fun `maintenance packs loose objects and the repository keeps working`(@TempDir dir: File) {
        val url = TestEnv.bareRemote(File(dir, "remote.git"))
        val vault = File(dir, "phone")
        val spec = TestEnv.spec(vault, url)
        assertIs<RepoOnboarding.Result.Ready>(RepoOnboarding.clone(spec, SecretProvider.NONE, InMemorySyncLogger()))
        val engine = TestEnv.engine()
        repeat(6) { i ->
            vault.writeVaultFile("Note$i.md", "note $i\n".repeat(20))
            vault.writeVaultFile("Journal.md", "day $i\n")
            assertIs<SyncOutcome.Success>(TestEnv.sync(engine, spec))
        }
        val logger = InMemorySyncLogger()
        val result = VaultRepository.open(vault, logger).use { repo ->
            val before = RepoMaintenance.stats(repo)
            assertTrue(before.looseObjects > 0, before.toString())
            RepoMaintenance.run(repo, logger)
        }
        assertEquals(0L, result.after.looseObjects, result.toString())
        assertEquals(1L, result.after.packFiles, result.toString())
        assertTrue(result.after.packedObjects >= result.before.looseObjects, result.toString())
        assertFalse(result.after.recommended)
        assertTrue(File(vault, ".git/packed-refs").isFile)

        // 维护后内容完整、还能继续同步
        assertEquals("day 5\n", vault.readVaultFile("Journal.md"))
        vault.writeVaultFile("After.md", "after maintenance\n")
        assertIs<SyncOutcome.Success>(TestEnv.sync(engine, spec))
        TestEnv.cloneDesktop(url, File(dir, "desktop")).use { d ->
            assertEquals("after maintenance\n", d.read("After.md"))
            assertEquals("note 3\n".repeat(20), d.read("Note3.md"))
        }
    }

    @Test
    fun `history lists commits with pushed flags and file changes`(@TempDir dir: File) {
        val url = TestEnv.bareRemote(File(dir, "remote.git"))
        val desktop = TestEnv.cloneDesktop(url, File(dir, "desktop"))
        desktop.write("A.md", "a\n")
        desktop.write("B.md", "b\n".repeat(30))
        desktop.commitAll("desktop: add A and B")
        desktop.push()
        val vault = File(dir, "phone")
        val spec = TestEnv.spec(vault, url)
        assertIs<RepoOnboarding.Result.Ready>(RepoOnboarding.clone(spec, SecretProvider.NONE, InMemorySyncLogger()))

        // 手机上：改 A、删 B 的同时新建内容相同的 C（识别为重命名）、新增 D —— 只提交不推送
        VaultRepository.open(vault, InMemorySyncLogger()).use { repo ->
            vault.writeVaultFile("A.md", "a changed\n")
            File(vault, "B.md").renameTo(File(vault, "C.md"))
            vault.writeVaultFile("sub/D.md", "d\n")
            repo.commitAll("phone: local work", TestEnv.phone)

            val page = RepoHistory.recent(repo, "main")
            assertEquals(2, page.commits.size)
            assertFalse(page.hasMore)
            val (local, first) = page.commits
            assertEquals("phone: local work", local.summary)
            assertFalse(local.pushed, "local commit is not on origin/main yet")
            assertTrue(first.pushed)
            assertEquals("Desktop", first.authorName)

            val detail = RepoHistory.detail(repo, "main", local.id)
            val byPath = detail.changes.associateBy { it.path }
            assertEquals(RepoHistory.ChangeType.MODIFIED, byPath.getValue("A.md").type)
            assertEquals(RepoHistory.ChangeType.RENAMED, byPath.getValue("C.md").type)
            assertEquals("B.md", byPath.getValue("C.md").oldPath)
            assertEquals(RepoHistory.ChangeType.ADDED, byPath.getValue("sub/D.md").type)
            assertEquals(3, detail.totalChanges)

            val root = RepoHistory.detail(repo, "main", first.id)
            assertEquals(listOf("A.md", "B.md"), root.changes.map { it.path })
            assertTrue(root.changes.all { it.type == RepoHistory.ChangeType.ADDED })

            val paged = RepoHistory.recent(repo, "main", skip = 1, limit = 1)
            assertEquals(listOf(first.id), paged.commits.map { it.id })
            assertEquals(1, RepoHistory.recent(repo, "main", limit = 1).commits.size)
            assertTrue(RepoHistory.recent(repo, "main", limit = 1).hasMore)
        }

        // 推送后本地提交也显示为已推送
        assertIs<SyncOutcome.Success>(TestEnv.sync(TestEnv.engine(), spec))
        VaultRepository.open(vault, InMemorySyncLogger()).use { repo ->
            assertTrue(RepoHistory.recent(repo, "main").commits.all { it.pushed })
        }
    }

    @Test
    fun `gitignore recommended rules are appended only when missing`(@TempDir dir: File) {
        val vault = File(dir, "vault").apply { mkdirs() }
        File(vault, ".gitignore").writeText("private/\n.trash/\n")
        val missing = GitIgnoreTemplate.missingRules(vault)
        assertFalse(".trash/" in missing)
        assertTrue(".obsidian/workspace.json" in missing)

        val added = GitIgnoreTemplate.appendMissing(vault)
        assertEquals(missing, added)
        val text = File(vault, ".gitignore").readText()
        assertTrue(text.startsWith("private/\n.trash/\n\n${GitIgnoreTemplate.HEADER}\n"), text)
        assertEquals(1, text.lines().count { it == ".trash/" })
        assertTrue(GitIgnoreTemplate.missingRules(vault).isEmpty())
        assertTrue(GitIgnoreTemplate.appendMissing(vault).isEmpty(), "second call adds nothing")

        // 没有换行结尾、CRLF 的文件也能正确追加
        val crlf = File(dir, "crlf").apply { mkdirs() }
        File(crlf, ".gitignore").writeText("a\r\nb")
        GitIgnoreTemplate.appendMissing(crlf)
        assertTrue(File(crlf, ".gitignore").readText().startsWith("a\r\nb\r\n\r\n${GitIgnoreTemplate.HEADER}\r\n"))

        // 没有 .gitignore 时新建完整模板
        val fresh = File(dir, "fresh").apply { mkdirs() }
        assertEquals(GitIgnoreTemplate.RULES, GitIgnoreTemplate.appendMissing(fresh))
        assertEquals(GitIgnoreTemplate.RECOMMENDED, File(fresh, ".gitignore").readText())
    }
}
