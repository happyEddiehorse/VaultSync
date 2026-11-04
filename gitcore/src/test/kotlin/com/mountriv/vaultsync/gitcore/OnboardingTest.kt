package com.mountriv.vaultsync.gitcore

import com.mountriv.vaultsync.gitcore.log.InMemorySyncLogger
import com.mountriv.vaultsync.gitcore.model.FailureKind
import com.mountriv.vaultsync.gitcore.model.SecretProvider
import com.mountriv.vaultsync.gitcore.model.SyncOutcome
import com.mountriv.vaultsync.gitcore.model.SyncStage
import com.mountriv.vaultsync.gitcore.repo.RepoInspector
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 三种接入流程（docs/SYNC_ENGINE.md §4）。 */
class OnboardingTest {

    @BeforeEach
    fun setUp() = TestEnv.ensure()

    @AfterEach
    fun tearDown() = TestEnv.closeDesktops()

    @Test
    fun `clone existing remote into empty folder`(@TempDir dir: File) {
        val url = TestEnv.bareRemote(File(dir, "remote.git"))
        TestEnv.cloneDesktop(url, File(dir, "desktop")).use { d ->
            d.write("Notes/Hello.md", "# hello\n")
            d.commitAll("desktop 1")
            d.push()
        }

        val vault = File(dir, "phone-vault")
        val spec = TestEnv.spec(vault, url)
        val logger = InMemorySyncLogger()
        val result = RepoOnboarding.clone(spec, SecretProvider.NONE, logger)
        assertIs<RepoOnboarding.Result.Ready>(result, logger.toString())
        assertEquals("# hello\n", vault.readVaultFile("Notes/Hello.md"))

        val info = RepoInspector.inspect(vault)
        assertEquals("main", info.branch)
        assertEquals("0", info.configGcAuto)
        assertEquals("false", info.configFileMode)
        assertTrue(info.residues.isEmpty(), info.residues.toString())

        // 同步一次：无变化
        val outcome = TestEnv.sync(TestEnv.engine(), spec)
        assertIs<SyncOutcome.Success>(outcome)
        assertEquals("ALREADY_UP_TO_DATE", outcome.summary.mergeStatus)
        assertEquals("UP_TO_DATE", outcome.summary.pushStatus)
    }

    @Test
    fun `clone refuses non-empty folder and unknown branch`(@TempDir dir: File) {
        val url = TestEnv.bareRemote(File(dir, "remote.git"))
        TestEnv.cloneDesktop(url, File(dir, "desktop")).use { d ->
            d.write("A.md", "a\n")
            d.commitAll("desktop 1")
            d.push()
        }
        val occupied = File(dir, "occupied").apply { mkdirs(); writeVaultFile("x.md", "x") }
        val r1 = RepoOnboarding.clone(TestEnv.spec(occupied, url), SecretProvider.NONE, InMemorySyncLogger())
        assertIs<RepoOnboarding.Result.Failed>(r1)
        assertTrue(r1.message.contains("not empty"), r1.message)
        assertEquals(FailureKind.DIRECTORY_NOT_EMPTY, r1.kind)

        val r2 = RepoOnboarding.clone(TestEnv.spec(File(dir, "v2"), url, branch = "develop"), SecretProvider.NONE, InMemorySyncLogger())
        assertIs<RepoOnboarding.Result.Failed>(r2)
        assertTrue(r2.message.contains("develop"), r2.message)
        assertEquals(FailureKind.BRANCH_NOT_FOUND, r2.kind)
        assertFalse(File(dir, "v2").exists(), "failed clone must not leave a directory behind")
    }

    @Test
    fun `clone from empty remote then first sync pushes initial commit`(@TempDir dir: File) {
        val url = TestEnv.bareRemote(File(dir, "remote.git"))
        val vault = File(dir, "phone-vault")
        val spec = TestEnv.spec(vault, url)
        val result = RepoOnboarding.clone(spec, SecretProvider.NONE, InMemorySyncLogger())
        assertIs<RepoOnboarding.Result.Ready>(result)
        assertTrue(RepoOnboarding.Note.RemoteEmpty in result.notes, result.notes.toString())
        assertTrue(RepoInspector.inspect(vault).residues.contains(RepoInspector.Residue.UNBORN_BRANCH))

        vault.writeVaultFile("First.md", "first\n")
        val outcome = TestEnv.sync(TestEnv.engine(), spec)
        assertIs<SyncOutcome.Success>(outcome)
        assertNotNull(outcome.summary.localCommitId)
        assertEquals(null, outcome.summary.mergeStatus)
        assertEquals("OK", outcome.summary.pushStatus)

        TestEnv.cloneDesktop(url, File(dir, "desktop")).use { d ->
            assertEquals("first\n", d.read("First.md"))
        }
    }

    @Test
    fun `unborn local branch adopts remote history on first sync`(@TempDir dir: File) {
        val url = TestEnv.bareRemote(File(dir, "remote.git"))
        val vault = File(dir, "phone-vault")
        val spec = TestEnv.spec(vault, url)
        assertIs<RepoOnboarding.Result.Ready>(RepoOnboarding.clone(spec, SecretProvider.NONE, InMemorySyncLogger()))

        // 桌面端先推送了内容，手机端还没有任何提交
        TestEnv.cloneDesktop(url, File(dir, "desktop")).use { d ->
            d.write("Desk.md", "desk\n")
            d.commitAll("desktop 1")
            d.push()
        }
        val outcome = TestEnv.sync(TestEnv.engine(), spec)
        assertIs<SyncOutcome.Success>(outcome)
        assertEquals("FAST_FORWARD", outcome.summary.mergeStatus)
        assertEquals("desk\n", vault.readVaultFile("Desk.md"))
        assertEquals(RepositoryState.SAFE, RepoInspector.inspect(vault).state)
    }

    @Test
    fun `init existing vault and push to empty remote`(@TempDir dir: File) {
        val url = TestEnv.bareRemote(File(dir, "remote.git"))
        val vault = File(dir, "vault").apply {
            mkdirs()
            writeVaultFile("Note.md", "note\n")
            writeVaultFile(".obsidian/workspace.json", "{}")
            writeVaultFile(".obsidian/app.json", "{}")
        }
        val spec = TestEnv.spec(vault, url)
        val logger = InMemorySyncLogger()
        val result = RepoOnboarding.initAndPush(spec, SecretProvider.NONE, TestEnv.phone, logger, clock = TestEnv.fixedClock)
        assertIs<RepoOnboarding.Result.Ready>(result, logger.toString())
        assertTrue(RepoOnboarding.Note.GitignoreCreated in result.notes, result.notes.toString())
        assertTrue(File(vault, ".gitignore").readText().contains("workspace.json"))

        TestEnv.cloneDesktop(url, File(dir, "desktop")).use { d ->
            assertEquals("note\n", d.read("Note.md"))
            assertTrue(File(d.dir, ".obsidian/app.json").exists())
            assertFalse(File(d.dir, ".obsidian/workspace.json").exists(), "workspace.json must be ignored")
            val msg = d.git.log().setMaxCount(1).call().first().fullMessage
            assertEquals("vault backup: 2026-09-19 13:05:09", msg)
        }
    }

    @Test
    fun `init refuses non-empty remote and existing repository`(@TempDir dir: File) {
        val url = TestEnv.bareRemote(File(dir, "remote.git"))
        TestEnv.cloneDesktop(url, File(dir, "desktop")).use { d ->
            d.write("A.md", "a\n")
            d.commitAll("desktop 1")
            d.push()
        }
        val vault = File(dir, "vault").apply { mkdirs(); writeVaultFile("B.md", "b\n") }
        val r1 = RepoOnboarding.initAndPush(TestEnv.spec(vault, url), SecretProvider.NONE, TestEnv.phone, InMemorySyncLogger())
        assertIs<RepoOnboarding.Result.Failed>(r1)
        assertTrue(r1.message.contains("already has branches"), r1.message)
        assertEquals(FailureKind.REMOTE_NOT_EMPTY, r1.kind)
        assertFalse(File(vault, ".git").exists(), "must not initialize when refusing")

        val r2 = RepoOnboarding.initAndPush(TestEnv.spec(File(dir, "desktop"), url), SecretProvider.NONE, TestEnv.phone, InMemorySyncLogger())
        assertIs<RepoOnboarding.Result.Failed>(r2)
        assertTrue(r2.message.contains("adopt"), r2.message)
        assertEquals(FailureKind.ALREADY_A_REPOSITORY, r2.kind)
    }

    @Test
    fun `adopt existing repository corrects branch and remote`(@TempDir dir: File) {
        val url = TestEnv.bareRemote(File(dir, "remote.git"), initialBranch = "notes")
        val desktop = File(dir, "desktop")
        TestEnv.cloneDesktop(url, desktop, branch = "notes").use { d ->
            d.write("A.md", "a\n")
            d.commitAll("desktop 1")
            d.push("notes")
        }
        // 用户填的分支/远端与仓库实际不一致 → 以仓库为准并给出提示
        val given = TestEnv.spec(desktop, url = "", branch = "main")
        val result = RepoOnboarding.adopt(given, InMemorySyncLogger())
        assertIs<RepoOnboarding.Result.Ready>(result)
        assertEquals("notes", result.spec.branch)
        assertTrue(result.spec.remoteUrl.endsWith("/remote.git/"), result.spec.remoteUrl)
        assertTrue(result.notes.any { it is RepoOnboarding.Note.BranchAdjusted && it.actual == "notes" }, result.notes.toString())

        val info = RepoInspector.inspect(desktop)
        assertEquals("0", info.configGcAuto)
        assertEquals("true", VaultRepository.open(desktop, InMemorySyncLogger()).use { it.repository.config.getString("core", null, "ignorecase") })

        val outcome = TestEnv.sync(TestEnv.engine(), result.spec)
        assertIs<SyncOutcome.Success>(outcome)

        val notRepo = File(dir, "plain").apply { mkdirs() }
        val r2 = RepoOnboarding.adopt(TestEnv.spec(notRepo, url), InMemorySyncLogger())
        assertIs<RepoOnboarding.Result.Failed>(r2)
        assertEquals(SyncStage.PRECHECK, r2.stage)
    }
}
