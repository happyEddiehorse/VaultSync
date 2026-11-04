package com.mountriv.vaultsync.gitcore

import com.mountriv.vaultsync.gitcore.log.InMemorySyncLogger
import com.mountriv.vaultsync.gitcore.merge.Block
import com.mountriv.vaultsync.gitcore.merge.Choice
import com.mountriv.vaultsync.gitcore.merge.ConflictKind
import com.mountriv.vaultsync.gitcore.merge.MergeModel
import com.mountriv.vaultsync.gitcore.model.SecretProvider
import com.mountriv.vaultsync.gitcore.model.SyncOutcome
import com.mountriv.vaultsync.gitcore.repo.RepoInspector
import com.mountriv.vaultsync.gitcore.repo.VaultRepository
import com.mountriv.vaultsync.gitcore.sync.RepoOnboarding
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.lib.RepositoryState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 冲突：检测、块模型、四种取舍、写回、完成合并（S5）。 */
class ConflictTest {

    @BeforeEach
    fun setUp() = TestEnv.ensure()

    @AfterEach
    fun tearDown() = TestEnv.closeDesktops()

    private val original = """
        # Title
        line 1
        line 2
        line 3
        line 4
        line 5
        line 6
        line 7
        line 8
        line 9
    """.trimIndent() + "\n"

    private fun setup(dir: File): Triple<String, TestEnv.Desktop, File> {
        val url = TestEnv.bareRemote(File(dir, "remote.git"))
        val desktop = TestEnv.cloneDesktop(url, File(dir, "desktop"))
        desktop.write("Note.md", original)
        desktop.commitAll("desktop 1")
        desktop.push()
        val vault = File(dir, "phone-vault")
        assertIs<RepoOnboarding.Result.Ready>(RepoOnboarding.clone(TestEnv.spec(vault, url), SecretProvider.NONE, InMemorySyncLogger()))
        return Triple(url, desktop, vault)
    }

    @Test
    fun `block model splits non-adjacent conflicts and renders every choice`() {
        val base = RawText("a\nb\nc\nd\ne\nf\ng\n".toByteArray())
        val ours = RawText("a\nB1\nc\nd\ne\nF1\ng\n".toByteArray())
        val theirs = RawText("a\nB2\nc\nd\ne\nF2\ng\nh\n".toByteArray())
        val blocks = MergeModel.buildBlocks(base, ours, theirs)
        val conflicts = blocks.filterIsInstance<Block.Conflict>()
        assertEquals(2, conflicts.size, blocks.toString())
        assertEquals(listOf("B1"), conflicts[0].ours)
        assertEquals(listOf("B2"), conflicts[0].theirs)
        assertEquals(listOf("F1"), conflicts[1].ours)
        assertEquals(listOf("F2"), conflicts[1].theirs)
        assertEquals(listOf("a"), (blocks[0] as Block.Common).lines)
        assertEquals(listOf("c", "d", "e"), (blocks[2] as Block.Common).lines)
        assertEquals(listOf("g", "h"), (blocks[4] as Block.Common).lines, "their non-conflicting addition is kept")

        val file = com.mountriv.vaultsync.gitcore.merge.ConflictFile("Note.md", ConflictKind.BOTH_MODIFIED, false, 1, 1, blocks)
        assertNull(file.render())
        assertEquals("a\nB1\nc\nd\ne\nF1\ng\nh\n", file.withAll(Choice.Ours).render())
        assertEquals("a\nB2\nc\nd\ne\nF2\ng\nh\n", file.withAll(Choice.Theirs).render())
        assertEquals("a\nB1\nB2\nc\nd\ne\nF2\nF1\ng\nh\n", file.withChoice(0, Choice.BothOursFirst).withChoice(1, Choice.BothTheirsFirst).render())
        assertEquals(
            "a\nB-custom\nc\nd\ne\nF1\ng\nh\n",
            file.withChoice(0, Choice.Custom("B-custom\n")).withChoice(1, Choice.Ours).render(),
        )
        assertEquals(2, file.conflictCount)
        assertEquals(1, file.withChoice(1, Choice.Theirs).decidedCount)
    }

    @Test
    fun `crlf files keep their line endings`() {
        val base = RawText("a\r\nb\r\n".toByteArray())
        val ours = RawText("a\r\nb-ours\r\n".toByteArray())
        val theirs = RawText("a\r\nb-theirs\r\n".toByteArray())
        val blocks = MergeModel.buildBlocks(base, ours, theirs)
        val c = blocks.filterIsInstance<Block.Conflict>().single()
        assertEquals(listOf("b-ours"), c.ours)
        val file = com.mountriv.vaultsync.gitcore.merge.ConflictFile("x.md", ConflictKind.BOTH_MODIFIED, false, 1, 1, blocks, lineEnding = "\r\n")
        assertEquals("a\r\nb-theirs\r\n", file.withAll(Choice.Theirs).render())
        assertEquals(listOf("x", "y"), MergeModel.splitLines("x\r\ny\r\n"))
        assertEquals(listOf("x", "", "y"), MergeModel.splitLines("x\n\ny"))
        assertEquals("", MergeModel.joinLines(emptyList(), "\n", true))
    }

    @Test
    fun `conflicting edits stop the sync and can be resolved block by block`(@TempDir dir: File) {
        val (url, desktop, vault) = setup(dir)
        val spec = TestEnv.spec(vault, url)
        val engine = TestEnv.engine()

        desktop.write("Note.md", original.replace("line 2", "line 2 DESKTOP").replace("line 8", "line 8 DESKTOP"))
        desktop.commitAll("desktop 2")
        desktop.push()
        vault.writeVaultFile("Note.md", original.replace("line 2", "line 2 PHONE").replace("line 8", "line 8 PHONE"))
        vault.writeVaultFile("Other.md", "unrelated\n")

        val logger = InMemorySyncLogger()
        val o1 = TestEnv.sync(engine, spec, logger)
        assertIs<SyncOutcome.Conflicts>(o1, logger.toString())
        assertEquals(listOf("Note.md"), o1.files)
        assertEquals(RepositoryState.MERGING, RepoInspector.inspect(vault).state)
        assertTrue(RepoInspector.inspect(vault).residues.contains(RepoInspector.Residue.MERGE_WITH_CONFLICTS))
        assertTrue(vault.readVaultFile("Note.md").contains("<<<<<<<"), "JGit writes conflict markers to the working tree")

        // 再同步一次：不做任何事，直接报告冲突
        val o2 = TestEnv.sync(engine, spec)
        assertIs<SyncOutcome.Conflicts>(o2)
        assertEquals(listOf("Note.md"), o2.files)

        VaultRepository.open(vault, InMemorySyncLogger()).use { repo ->
            val files = MergeModel.listConflicts(repo)
            assertEquals(1, files.size)
            val file = files[0]
            assertEquals(ConflictKind.BOTH_MODIFIED, file.kind)
            assertFalse(file.isBinary)
            assertEquals(2, file.conflictCount)
            val conflicts = file.blocks!!.filterIsInstance<Block.Conflict>()
            assertEquals(listOf("line 2 PHONE"), conflicts[0].ours)
            assertEquals(listOf("line 2 DESKTOP"), conflicts[0].theirs)
            assertEquals(listOf("line 8 PHONE"), conflicts[1].ours)
            assertEquals(listOf("line 8 DESKTOP"), conflicts[1].theirs)

            // 第一块用远端，第二块两者都要（本地在前），然后用户在整文件上再补一行
            val chosen = file.withChoice(0, Choice.Theirs).withChoice(1, Choice.BothOursFirst)
            val rendered = chosen.render()!!
            val edited = rendered + "added after merge\n"
            MergeModel.resolveWithText(repo, "Note.md", edited)
            assertTrue(repo.conflictingPaths().isEmpty())
            assertEquals(RepositoryState.MERGING_RESOLVED, repo.state)
            assertTrue(MergeModel.listConflicts(repo).isEmpty())
        }

        // 下一次同步完成合并并推送
        val o3 = TestEnv.sync(engine, spec)
        assertIs<SyncOutcome.Success>(o3)
        assertEquals("OK", o3.summary.pushStatus)
        assertEquals(RepositoryState.SAFE, RepoInspector.inspect(vault).state)

        desktop.pull()
        val expected = original
            .replace("line 2", "line 2 DESKTOP")
            .replace("line 8\n", "line 8 PHONE\nline 8 DESKTOP\n") + "added after merge\n"
        assertEquals(expected, desktop.read("Note.md"))
        assertEquals("unrelated\n", desktop.read("Other.md"))
        val head = desktop.git.log().setMaxCount(1).call().first()
        assertEquals(2, head.parentCount)
    }

    @Test
    fun `whole-file shortcuts and abort`(@TempDir dir: File) {
        val (url, desktop, vault) = setup(dir)
        val spec = TestEnv.spec(vault, url)
        val engine = TestEnv.engine()

        desktop.write("Note.md", "desktop version\n")
        desktop.commitAll("desktop 2")
        desktop.push()
        vault.writeVaultFile("Note.md", "phone version\n")
        assertIs<SyncOutcome.Conflicts>(TestEnv.sync(engine, spec))

        // 放弃合并：回到本地提交，不丢数据
        VaultRepository.open(vault, InMemorySyncLogger()).use { repo ->
            repo.abortMerge()
            assertEquals(RepositoryState.SAFE, repo.state)
            assertTrue(repo.status().isClean)
        }
        assertEquals("phone version\n", vault.readVaultFile("Note.md"))

        // 再同步 → 再冲突 → 整文件用远端
        assertIs<SyncOutcome.Conflicts>(TestEnv.sync(engine, spec))
        VaultRepository.open(vault, InMemorySyncLogger()).use { repo ->
            MergeModel.resolveTakeTheirs(repo, "Note.md")
            assertEquals(RepositoryState.MERGING_RESOLVED, repo.state)
        }
        assertEquals("desktop version\n", vault.readVaultFile("Note.md"))
        val o = TestEnv.sync(engine, spec)
        assertIs<SyncOutcome.Success>(o)
        desktop.pull()
        assertEquals("desktop version\n", desktop.read("Note.md"))

        // 反过来：整文件用本地
        desktop.write("Note.md", "desktop v3\n")
        desktop.commitAll("desktop 3")
        desktop.push()
        vault.writeVaultFile("Note.md", "phone v3\n")
        assertIs<SyncOutcome.Conflicts>(TestEnv.sync(engine, spec))
        VaultRepository.open(vault, InMemorySyncLogger()).use { repo -> MergeModel.resolveTakeOurs(repo, "Note.md") }
        assertIs<SyncOutcome.Success>(TestEnv.sync(engine, spec))
        desktop.pull()
        assertEquals("phone v3\n", desktop.read("Note.md"))
    }

    @Test
    fun `both added the same new file`(@TempDir dir: File) {
        val (url, desktop, vault) = setup(dir)
        val spec = TestEnv.spec(vault, url)
        desktop.write("New.md", "from desktop\n")
        desktop.commitAll("desktop 2")
        desktop.push()
        vault.writeVaultFile("New.md", "from phone\n")
        assertIs<SyncOutcome.Conflicts>(TestEnv.sync(TestEnv.engine(), spec))
        VaultRepository.open(vault, InMemorySyncLogger()).use { repo ->
            val file = MergeModel.listConflicts(repo).single()
            assertEquals(ConflictKind.BOTH_ADDED, file.kind)
            val c = file.blocks!!.filterIsInstance<Block.Conflict>().single()
            assertEquals(listOf("from phone"), c.ours)
            assertEquals(listOf("from desktop"), c.theirs)
            MergeModel.resolveWithText(repo, "New.md", file.withAll(Choice.BothTheirsFirst).render()!!)
        }
        assertIs<SyncOutcome.Success>(TestEnv.sync(TestEnv.engine(), spec))
        desktop.pull()
        assertEquals("from desktop\nfrom phone\n", desktop.read("New.md"))
    }
}
