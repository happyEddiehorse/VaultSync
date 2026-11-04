package com.mountriv.vaultsync.gitcore

import com.mountriv.vaultsync.gitcore.log.InMemorySyncLogger
import com.mountriv.vaultsync.gitcore.merge.Block
import com.mountriv.vaultsync.gitcore.merge.Choice
import com.mountriv.vaultsync.gitcore.merge.ConflictFile
import com.mountriv.vaultsync.gitcore.merge.ConflictKind
import com.mountriv.vaultsync.gitcore.merge.MergeModel
import com.mountriv.vaultsync.gitcore.merge.Side
import com.mountriv.vaultsync.gitcore.model.SecretProvider
import com.mountriv.vaultsync.gitcore.model.SyncException
import com.mountriv.vaultsync.gitcore.model.SyncOutcome
import com.mountriv.vaultsync.gitcore.repo.VaultRepository
import com.mountriv.vaultsync.gitcore.sync.RepoOnboarding
import org.eclipse.jgit.lib.RepositoryState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M3 冲突页面依赖的 gitcore 支持：摘要、原始清单、批量取舍、删除解决、编辑器预填、行差异。 */
class ConflictUiSupportTest {

    @BeforeEach
    fun setUp() = TestEnv.ensure()

    @AfterEach
    fun tearDown() = TestEnv.closeDesktops()

    /** 远端与手机各改一批文件，制造 3 个冲突：A.md 双方修改、B.md 远端删除本地修改、C.md 本地删除远端修改。 */
    private fun threeConflicts(dir: File): Triple<String, TestEnv.Desktop, File> {
        val url = TestEnv.bareRemote(File(dir, "remote.git"))
        val desktop = TestEnv.cloneDesktop(url, File(dir, "desktop"))
        desktop.write("A.md", "a1\na2\na3\n")
        desktop.write("B.md", "b\n")
        desktop.write("C.md", "c\n")
        desktop.write("Same.md", "same\n")
        desktop.commitAll("desktop 1")
        desktop.push()
        val vault = File(dir, "phone-vault")
        assertIs<RepoOnboarding.Result.Ready>(RepoOnboarding.clone(TestEnv.spec(vault, url), SecretProvider.NONE, InMemorySyncLogger()))

        desktop.write("A.md", "a1\na2 desktop\na3\n")
        desktop.delete("B.md")
        desktop.write("C.md", "c desktop\n")
        desktop.commitAll("desktop 2")
        desktop.push()

        vault.writeVaultFile("A.md", "a1\na2 phone\na3\n")
        vault.writeVaultFile("B.md", "b phone\n")
        File(vault, "C.md").delete()
        return Triple(url, desktop, vault)
    }

    @Test
    fun `summaries and original conflict list survive partial resolution`(@TempDir dir: File) {
        val (url, _, vault) = threeConflicts(dir)
        val spec = TestEnv.spec(vault, url)
        val o = TestEnv.sync(TestEnv.engine(), spec)
        assertIs<SyncOutcome.Conflicts>(o)
        assertEquals(listOf("A.md", "B.md", "C.md"), o.files)

        VaultRepository.open(vault, InMemorySyncLogger()).use { repo ->
            val summaries = MergeModel.summaries(repo)
            assertEquals(listOf("A.md", "B.md", "C.md"), summaries.map { it.path })
            assertEquals(ConflictKind.BOTH_MODIFIED, summaries[0].kind)
            assertEquals(ConflictKind.DELETED_BY_THEM, summaries[1].kind)
            assertEquals(ConflictKind.DELETED_BY_US, summaries[2].kind)
            assertTrue(summaries.none { it.isBinary })
            assertEquals(listOf("A.md", "B.md", "C.md"), MergeModel.originalConflictPaths(repo))

            // 解决 A 之后：未解决列表少了 A，但原始清单仍包含 A（供列表页显示"已解决"）
            MergeModel.resolveTakeSide(repo, "A.md", Side.THEIRS)
            assertEquals(listOf("B.md", "C.md"), MergeModel.unresolvedPaths(repo))
            assertEquals(listOf("A.md", "B.md", "C.md"), MergeModel.originalConflictPaths(repo))
            assertEquals("a1\na2 desktop\na3\n", vault.readVaultFile("A.md"))
            assertNull(MergeModel.loadConflict(repo, "A.md"))
            assertEquals(RepositoryState.MERGING, repo.state)
        }
    }

    @Test
    fun `take side refuses a missing side and delete removes every stage`(@TempDir dir: File) {
        val (url, desktop, vault) = threeConflicts(dir)
        val spec = TestEnv.spec(vault, url)
        assertIs<SyncOutcome.Conflicts>(TestEnv.sync(TestEnv.engine(), spec))

        VaultRepository.open(vault, InMemorySyncLogger()).use { repo ->
            // B.md：远端删除。没有远端版本可保留
            val e = assertThrows<SyncException> { MergeModel.resolveTakeSide(repo, "B.md", Side.THEIRS) }
            assertTrue(e.message!!.contains("B.md"))
            assertEquals("b phone\n", MergeModel.readSideText(repo, "B.md", Side.OURS))
            assertNull(MergeModel.readSideText(repo, "B.md", Side.THEIRS))

            // 用"删除"解决 B（本地文件存在，要被删掉）；C 本地已不存在，"删除"也要能解决
            MergeModel.resolveDelete(repo, "B.md")
            assertFalse(File(vault, "B.md").exists())
            MergeModel.resolveDelete(repo, "C.md")
            assertEquals(listOf("A.md"), MergeModel.unresolvedPaths(repo))

            // A：整文件用本地
            MergeModel.resolveTakeSide(repo, "A.md", Side.OURS)
            assertTrue(MergeModel.unresolvedPaths(repo).isEmpty())
            assertEquals(RepositoryState.MERGING_RESOLVED, repo.state)
            assertTrue(repo.conflictingPaths().isEmpty())
        }
        assertIs<SyncOutcome.Success>(TestEnv.sync(TestEnv.engine(), spec))
        desktop.pull()
        assertEquals("a1\na2 phone\na3\n", desktop.read("A.md"))
        assertFalse(File(desktop.dir, "B.md").exists())
        assertFalse(File(desktop.dir, "C.md").exists())
        assertEquals("same\n", desktop.read("Same.md"))
    }

    @Test
    fun `resolve all with one side deletes files that side does not have`(@TempDir dir: File) {
        val (url, desktop, vault) = threeConflicts(dir)
        val spec = TestEnv.spec(vault, url)
        assertIs<SyncOutcome.Conflicts>(TestEnv.sync(TestEnv.engine(), spec))

        VaultRepository.open(vault, InMemorySyncLogger()).use { repo ->
            val handled = MergeModel.resolveAll(repo, Side.THEIRS)
            assertEquals(listOf("A.md", "B.md", "C.md"), handled)
            assertTrue(MergeModel.unresolvedPaths(repo).isEmpty())
            assertEquals(RepositoryState.MERGING_RESOLVED, repo.state)
        }
        assertEquals("a1\na2 desktop\na3\n", vault.readVaultFile("A.md"))
        assertFalse(File(vault, "B.md").exists(), "remote deleted B, taking remote deletes it locally too")
        assertEquals("c desktop\n", vault.readVaultFile("C.md"))

        assertIs<SyncOutcome.Success>(TestEnv.sync(TestEnv.engine(), spec))
        desktop.pull()
        assertEquals("a1\na2 desktop\na3\n", desktop.read("A.md"))
        assertFalse(File(desktop.dir, "B.md").exists())
        assertEquals("c desktop\n", desktop.read("C.md"))
    }

    @Test
    fun `resolve all with ours keeps local files and drops what we deleted`(@TempDir dir: File) {
        val (url, desktop, vault) = threeConflicts(dir)
        val spec = TestEnv.spec(vault, url)
        assertIs<SyncOutcome.Conflicts>(TestEnv.sync(TestEnv.engine(), spec))
        VaultRepository.open(vault, InMemorySyncLogger()).use { repo -> MergeModel.resolveAll(repo, Side.OURS) }
        assertEquals("a1\na2 phone\na3\n", vault.readVaultFile("A.md"))
        assertEquals("b phone\n", vault.readVaultFile("B.md"))
        assertFalse(File(vault, "C.md").exists())
        assertIs<SyncOutcome.Success>(TestEnv.sync(TestEnv.engine(), spec))
        desktop.pull()
        assertEquals("b phone\n", desktop.read("B.md"))
        assertFalse(File(desktop.dir, "C.md").exists())
    }

    @Test
    fun `editor prefill wraps undecided blocks in markers and marker check works`() {
        val blocks = listOf(
            Block.Common(listOf("head")),
            Block.Conflict(listOf("ours 1"), listOf("theirs 1"), Choice.Ours),
            Block.Common(listOf("mid")),
            Block.Conflict(listOf("ours 2"), listOf("theirs 2")),
        )
        val file = ConflictFile("n.md", ConflictKind.BOTH_MODIFIED, false, 1, 1, blocks, lineEnding = "\r\n")
        assertNull(file.render())
        assertEquals(2, file.conflictCount)
        assertEquals(1, file.decidedCount)
        assertFalse(file.allDecided)
        assertEquals(3, file.firstUndecidedBlockIndex)
        assertEquals(listOf("ours 2"), file.conflictAt(1)!!.ours)
        assertNull(file.conflictAt(2))

        val text = file.renderForEditing("本地", "远端")!!
        assertEquals(
            "head\nours 1\nmid\n<<<<<<< 本地\nours 2\n=======\ntheirs 2\n>>>>>>> 远端\n",
            text,
        )
        assertTrue(MergeModel.containsConflictMarkers(text))
        assertFalse(MergeModel.containsConflictMarkers("head\nours 1\nmid\nours 2 edited\n"))
        assertEquals(listOf(4, 6, 8), MergeModel.conflictMarkerLines(text))
        // 正文里的 "=======" 前后有其它字符时不算标记（Markdown 的分隔线写法是 "---"，但用户可能写 "======= 标题"）
        assertEquals(emptyList(), MergeModel.conflictMarkerLines("a\n======= x\n"))

        val decided = file.withChoice(1, Choice.Custom("custom\n"))
        assertTrue(decided.allDecided)
        assertNull(decided.firstUndecidedBlockIndex)
        assertEquals("head\r\nours 1\r\nmid\r\ncustom\r\n", decided.render())
        // 编辑器用 \n，写回时保持文件原有行尾
        assertEquals("head\nours 1\nmid\ncustom\n", decided.renderForEditing())
    }

    @Test
    fun `changed lines highlight only the differing lines`() {
        val d = MergeModel.changedLines(listOf("same", "ours only", "tail"), listOf("same", "theirs only", "tail", "extra"))
        assertEquals(setOf(1), d.oursChanged)
        assertEquals(setOf(1, 3), d.theirsChanged)
        val empty = MergeModel.changedLines(emptyList(), listOf("x", "y"))
        assertEquals(emptySet(), empty.oursChanged)
        assertEquals(setOf(0, 1), empty.theirsChanged)
        val same = MergeModel.changedLines(listOf("a"), listOf("a"))
        assertTrue(same.oursChanged.isEmpty() && same.theirsChanged.isEmpty())
    }

    @Test
    fun `trailing newline is kept when either side has it`(@TempDir dir: File) {
        val url = TestEnv.bareRemote(File(dir, "remote.git"))
        val desktop = TestEnv.cloneDesktop(url, File(dir, "desktop"))
        desktop.write("n.md", "title\nbody\n")
        desktop.commitAll("desktop 1")
        desktop.push()
        val vault = File(dir, "phone-vault")
        assertIs<RepoOnboarding.Result.Ready>(RepoOnboarding.clone(TestEnv.spec(vault, url), SecretProvider.NONE, InMemorySyncLogger()))
        desktop.write("n.md", "title\nbody desktop\n")
        desktop.commitAll("desktop 2")
        desktop.push()
        // Obsidian 手机端写文件不带末尾换行
        vault.writeVaultFile("n.md", "title\nbody phone")
        val spec = TestEnv.spec(vault, url)
        assertIs<SyncOutcome.Conflicts>(TestEnv.sync(TestEnv.engine(), spec))
        VaultRepository.open(vault, InMemorySyncLogger()).use { repo ->
            val f = MergeModel.loadConflict(repo, "n.md")!!
            assertTrue(f.endsWithNewline, "theirs has a trailing newline, so the result keeps it")
            assertEquals("title\nbody desktop\n", f.withAll(Choice.Theirs).render())
            assertEquals("title\nbody phone\n", f.withAll(Choice.Ours).render())
        }
    }

    @Test
    fun `binary summary and side text`(@TempDir dir: File) {
        val url = TestEnv.bareRemote(File(dir, "remote.git"))
        val desktop = TestEnv.cloneDesktop(url, File(dir, "desktop"))
        val png = ByteArray(1024) { i -> if (i % 5 == 0) 0 else (i and 0xff).toByte() }
        desktop.writeBytes("pic.png", png)
        desktop.commitAll("desktop 1")
        desktop.push()
        val vault = File(dir, "phone-vault")
        assertIs<RepoOnboarding.Result.Ready>(RepoOnboarding.clone(TestEnv.spec(vault, url), SecretProvider.NONE, InMemorySyncLogger()))
        desktop.writeBytes("pic.png", png.reversedArray())
        desktop.commitAll("desktop 2")
        desktop.push()
        File(vault, "pic.png").writeBytes(png.copyOf(900))
        val spec = TestEnv.spec(vault, url)
        assertIs<SyncOutcome.Conflicts>(TestEnv.sync(TestEnv.engine(), spec))
        VaultRepository.open(vault, InMemorySyncLogger()).use { repo ->
            val s = MergeModel.summaries(repo).single()
            assertTrue(s.isBinary)
            assertEquals(ConflictKind.BOTH_MODIFIED, s.kind)
            val f = MergeModel.loadConflict(repo, "pic.png")!!
            assertNotNull(f.oursId)
            assertNotNull(f.theirsId)
            assertEquals(7, f.oursId!!.length)
            assertEquals(900L, f.oursSize)
            assertEquals(1024L, f.theirsSize)
            assertNull(MergeModel.readSideText(repo, "pic.png", Side.OURS))
            MergeModel.resolveTakeSide(repo, "pic.png", Side.OURS)
        }
        assertIs<SyncOutcome.Success>(TestEnv.sync(TestEnv.engine(), spec))
    }
}
