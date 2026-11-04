package com.mountriv.vaultsync.gitcore

import com.mountriv.vaultsync.gitcore.log.InMemorySyncLogger
import com.mountriv.vaultsync.gitcore.merge.MergeModel
import com.mountriv.vaultsync.gitcore.model.SecretProvider
import com.mountriv.vaultsync.gitcore.model.SyncOutcome
import com.mountriv.vaultsync.gitcore.model.SyncStage
import com.mountriv.vaultsync.gitcore.repo.GitIgnoreTemplate
import com.mountriv.vaultsync.gitcore.repo.RepoInspector
import com.mountriv.vaultsync.gitcore.repo.RepoInspector.Residue
import com.mountriv.vaultsync.gitcore.repo.VaultRepository
import com.mountriv.vaultsync.gitcore.sync.CommitMessageTemplate
import com.mountriv.vaultsync.gitcore.sync.RepoOnboarding
import org.eclipse.jgit.lib.RepositoryState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 残留状态检测与修复（S6）、提交信息模板、.gitignore 模板。 */
class InspectorAndTemplateTest {

    @BeforeEach
    fun setUp() = TestEnv.ensure()

    @AfterEach
    fun tearDown() = TestEnv.closeDesktops()

    private fun setup(dir: File): Triple<String, TestEnv.Desktop, File> {
        val url = TestEnv.bareRemote(File(dir, "remote.git"))
        val desktop = TestEnv.cloneDesktop(url, File(dir, "desktop"))
        desktop.write("Note.md", "v1\n")
        desktop.commitAll("desktop 1")
        desktop.push()
        val vault = File(dir, "phone-vault")
        assertIs<RepoOnboarding.Result.Ready>(RepoOnboarding.clone(TestEnv.spec(vault, url), SecretProvider.NONE, InMemorySyncLogger()))
        return Triple(url, desktop, vault)
    }

    @Test
    fun `stale index lock is reported and removed by sync`(@TempDir dir: File) {
        val (url, _, vault) = setup(dir)
        File(vault, ".git/index.lock").writeText("")
        assertTrue(RepoInspector.inspect(vault).residues.contains(Residue.STALE_INDEX_LOCK))
        vault.writeVaultFile("Note.md", "v2\n")
        val logger = InMemorySyncLogger()
        val o = TestEnv.sync(TestEnv.engine(), TestEnv.spec(vault, url), logger)
        assertIs<SyncOutcome.Success>(o, logger.toString())
        assertFalse(File(vault, ".git/index.lock").exists())
        assertTrue(logger.lines.any { it.contains("stale index.lock") })
    }

    @Test
    fun `resolved but uncommitted merge is completed by the next sync`(@TempDir dir: File) {
        val (url, desktop, vault) = setup(dir)
        val spec = TestEnv.spec(vault, url)
        desktop.write("Note.md", "desktop\n")
        desktop.commitAll("d2")
        desktop.push()
        vault.writeVaultFile("Note.md", "phone\n")
        assertIs<SyncOutcome.Conflicts>(TestEnv.sync(TestEnv.engine(), spec))
        VaultRepository.open(vault, InMemorySyncLogger()).use { MergeModel.resolveTakeOurs(it, "Note.md") }
        val info = RepoInspector.inspect(vault)
        assertEquals(RepositoryState.MERGING_RESOLVED, info.state)
        assertTrue(info.residues.contains(Residue.MERGE_RESOLVED_UNCOMMITTED))
        assertIs<SyncOutcome.Success>(TestEnv.sync(TestEnv.engine(), spec))
        assertEquals(RepositoryState.SAFE, RepoInspector.inspect(vault).state)
    }

    @Test
    fun `detached head and wrong branch are refused`(@TempDir dir: File) {
        val (url, _, vault) = setup(dir)
        VaultRepository.open(vault, InMemorySyncLogger()).use { repo ->
            repo.git.checkout().setName(repo.headId()!!.name).call()
        }
        assertTrue(RepoInspector.inspect(vault).residues.contains(Residue.DETACHED_HEAD))
        val o = TestEnv.sync(TestEnv.engine(), TestEnv.spec(vault, url))
        assertIs<SyncOutcome.Failed>(o)
        assertEquals(SyncStage.PRECHECK, o.stage)

        VaultRepository.open(vault, InMemorySyncLogger()).use { repo ->
            repo.git.checkout().setName("main").call()
        }
        val o2 = TestEnv.sync(TestEnv.engine(), TestEnv.spec(vault, url, branch = "develop"))
        assertIs<SyncOutcome.Failed>(o2)
        assertTrue(o2.message.contains("develop"), o2.message)
    }

    @Test
    fun `missing remote and unsupported state are reported`(@TempDir dir: File) {
        val (url, _, vault) = setup(dir)
        VaultRepository.open(vault, InMemorySyncLogger()).use { repo ->
            repo.repository.config.unsetSection("remote", "origin")
            repo.repository.config.save()
        }
        assertTrue(RepoInspector.inspect(vault).residues.contains(Residue.MISSING_REMOTE))
        // 同步会重新写入 origin（RepoSpec 里有 URL）
        assertIs<SyncOutcome.Success>(TestEnv.sync(TestEnv.engine(), TestEnv.spec(vault, url)))

        File(vault, ".git/rebase-merge").mkdirs()
        File(vault, ".git/rebase-merge/interactive").writeText("")
        val info = RepoInspector.inspect(vault)
        assertTrue(info.residues.contains(Residue.UNSUPPORTED_STATE), info.residues.toString())
        val o = TestEnv.sync(TestEnv.engine(), TestEnv.spec(vault, url))
        assertIs<SyncOutcome.Failed>(o)
        assertEquals(SyncStage.PRECHECK, o.stage)
    }

    @Test
    fun `commit message template placeholders`() {
        val now = ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, ZoneId.of("Asia/Shanghai"))
        assertEquals(
            "vault backup: 2026-01-02 03:04:05",
            CommitMessageTemplate.render("vault backup: {{date}}", "YYYY-MM-DD HH:mm:ss", now, "phone", listOf("a.md")),
        )
        val many = (1..12).map { "f$it.md" }
        assertEquals(
            "phone: 12 files (f1.md, f2.md, f3.md, f4.md, f5.md, f6.md, f7.md, f8.md, f9.md, f10.md …) at 02.01.2026",
            CommitMessageTemplate.render("{{hostname}}: {{numFiles}} files ({{files}}) at {{date}}", "DD.MM.YYYY", now, "phone", many),
        )
        // 非法格式回退到默认
        assertEquals("2026-01-02 03:04:05", CommitMessageTemplate.render("{{date}}", "YYYY-MM-DD {bad", now, "x", emptyList()))
        assertEquals("yyyy-MM-dd 'D'", CommitMessageTemplate.momentToJava("YYYY-MM-DD 'D'"))
    }

    @Test
    fun `gitignore template only written when missing`(@TempDir dir: File) {
        assertTrue(GitIgnoreTemplate.writeIfMissing(dir))
        assertFalse(GitIgnoreTemplate.writeIfMissing(dir))
        val text = File(dir, ".gitignore").readText()
        assertTrue(text.contains(".obsidian/workspace.json"))
        assertTrue(text.contains(".trash/"))
    }
}
