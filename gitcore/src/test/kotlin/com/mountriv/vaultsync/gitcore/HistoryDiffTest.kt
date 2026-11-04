package com.mountriv.vaultsync.gitcore

import com.mountriv.vaultsync.gitcore.log.InMemorySyncLogger
import com.mountriv.vaultsync.gitcore.repo.RepoHistory
import com.mountriv.vaultsync.gitcore.repo.RepoHistory.DiffStatus
import com.mountriv.vaultsync.gitcore.repo.RepoHistory.LineType
import com.mountriv.vaultsync.gitcore.repo.VaultRepository
import org.eclipse.jgit.lib.Constants
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M6（D-031）：提交详情里单个文件的改动（[RepoHistory.fileDiff]）。 */
class HistoryDiffTest {

    @BeforeEach
    fun setUp() = TestEnv.ensure()

    private fun <T> withRepo(dir: File, block: (VaultRepository) -> T): T =
        VaultRepository.init(File(dir, "vault").apply { mkdirs() }, "main", InMemorySyncLogger()).use(block)

    private fun VaultRepository.blob(text: String): String = blob(text.toByteArray(Charsets.UTF_8))

    private fun VaultRepository.blob(bytes: ByteArray): String = repository.newObjectInserter().use { ins ->
        ins.insert(Constants.OBJ_BLOB, bytes).also { ins.flush() }.name
    }

    private fun lines(n: Int, prefix: String = "line"): String = (1..n).joinToString("") { "$prefix $it\n" }

    /** 一段的文本形式："-删除 / +新增 /  上下文"，便于断言。 */
    private fun RepoHistory.Hunk.render(): List<String> = lines.map {
        when (it.type) {
            LineType.CONTEXT -> " " + it.text
            LineType.DELETED -> "-" + it.text
            LineType.ADDED -> "+" + it.text
        }
    }

    @Test
    fun `modified file gives one hunk with three lines of context and word ranges`(@TempDir dir: File) = withRepo(dir) { repo ->
        val old = lines(10).replace("line 5\n", "今天借了三本书。\n")
        val new = lines(10).replace("line 5\n", "今天借了两本书。\n")
        val diff = RepoHistory.fileDiff(repo, repo.blob(old), repo.blob(new))
        assertEquals(DiffStatus.TEXT, diff.status)
        assertFalse(diff.truncated)
        val hunk = diff.hunks.single()
        assertEquals(2, hunk.oldStart)
        assertEquals(2, hunk.newStart)
        assertEquals(
            listOf(" line 2", " line 3", " line 4", "-今天借了三本书。", "+今天借了两本书。", " line 6", " line 7", " line 8"),
            hunk.render(),
        )
        val deleted = hunk.lines.first { it.type == LineType.DELETED }
        val added = hunk.lines.first { it.type == LineType.ADDED }
        assertEquals(listOf(4..4), deleted.changed)
        assertEquals(listOf(4..4), added.changed)
        assertTrue(hunk.lines.filter { it.type == LineType.CONTEXT }.all { it.changed.isEmpty() })
        assertEquals(old.toByteArray(Charsets.UTF_8).size.toLong(), diff.oldSize)
    }

    @Test
    fun `nearby changes share a hunk, distant ones get their own with correct start lines`(@TempDir dir: File) = withRepo(dir) { repo ->
        val old = lines(40)
        // 第 5 行与第 11 行之间隔 5 行（≤ 6，上下文重叠，并成一段）；第 30 行单独一段；另在第 20 行后插入一行
        val new = old.replace("line 5\n", "line five\n").replace("line 11\n", "line eleven\n")
            .replace("line 20\n", "line 20\ninserted\n").replace("line 30\n", "")
        val diff = RepoHistory.fileDiff(repo, repo.blob(old), repo.blob(new))
        val starts = diff.hunks.map { it.oldStart to it.newStart }
        assertEquals(listOf(2 to 2, 18 to 18, 27 to 28), starts)
        assertEquals(" line 2", diff.hunks[0].render().first())
        assertEquals(" line 14", diff.hunks[0].render().last())
        assertEquals(listOf(" line 18", " line 19", " line 20", "+inserted", " line 21", " line 22", " line 23"), diff.hunks[1].render())
        assertEquals(listOf(" line 27", " line 28", " line 29", "-line 30", " line 31", " line 32", " line 33"), diff.hunks[2].render())
        // 删除和新增的行不成对时没有字词区间；"line 5"→"line five" 相同部分过半，给区间
        val five = diff.hunks[0].lines.first { it.type == LineType.ADDED }
        assertEquals("line five", five.text)
        assertEquals(listOf(5..8), five.changed)
        assertTrue(diff.hunks[1].lines.single { it.type == LineType.ADDED }.changed.isEmpty())

        // 折叠起来的没改的行：第一段之前、两段之间、最后一段之后，接上后正好是整篇文件
        assertEquals(listOf("line 1"), diff.hunks[0].before)
        assertEquals((15..17).map { "line $it" }, diff.hunks[1].before)
        assertEquals((24..26).map { "line $it" }, diff.hunks[2].before)
        assertEquals((34..40).map { "line $it" }, diff.after)
    }

    @Test
    fun `folded lines plus hunks rebuild the new file`(@TempDir dir: File) = withRepo(dir) { repo ->
        val old = lines(30).trimEnd('\n')
        val new = lines(30).replace("line 3\n", "line three\n").replace("line 29\n", "")
        val diff = RepoHistory.fileDiff(repo, repo.blob(old), repo.blob(new))
        val rebuilt = diff.hunks.flatMap { h -> h.before + h.lines.filter { it.type != LineType.DELETED }.map { it.text } } + diff.after
        assertEquals(new.trimEnd('\n').split('\n'), rebuilt)
        assertTrue(diff.hunks[0].before.isEmpty())
        assertTrue(diff.after.isEmpty())

        val added = RepoHistory.fileDiff(repo, null, repo.blob("a\nb\n"))
        assertTrue(added.hunks.single().before.isEmpty() && added.after.isEmpty())
    }

    @Test
    fun `added and deleted files show every line`(@TempDir dir: File) = withRepo(dir) { repo ->
        val id = repo.blob("第一行\n第二行\n")
        val added = RepoHistory.fileDiff(repo, null, id)
        assertEquals(DiffStatus.TEXT, added.status)
        assertEquals(listOf("+第一行", "+第二行"), added.hunks.single().render())
        assertEquals(1, added.hunks.single().newStart)
        assertEquals(0L, added.oldSize)

        val deleted = RepoHistory.fileDiff(repo, id, null)
        assertEquals(listOf("-第一行", "-第二行"), deleted.hunks.single().render())
        assertEquals(1, deleted.hunks.single().oldStart)
        assertEquals(0L, deleted.newSize)
    }

    @Test
    fun `rename without content change, binary and oversized files are reported without lines`(@TempDir dir: File) = withRepo(dir) { repo ->
        val id = repo.blob("same\n")
        assertEquals(DiffStatus.UNCHANGED, RepoHistory.fileDiff(repo, id, id).status)

        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)
        val binary = RepoHistory.fileDiff(repo, repo.blob(png), repo.blob(png + byteArrayOf(1, 2, 3)))
        assertEquals(DiffStatus.BINARY, binary.status)
        assertTrue(binary.hunks.isEmpty())
        assertEquals(png.size.toLong(), binary.oldSize)
        assertEquals(png.size + 3L, binary.newSize)
        assertEquals(DiffStatus.BINARY, RepoHistory.fileDiff(repo, null, repo.blob(png)).status)

        val big = "x".repeat(100) + "\n"
        val large = RepoHistory.fileDiff(repo, repo.blob("small\n"), repo.blob(big.repeat((RepoHistory.MAX_DIFF_BYTES / big.length + 1).toInt())))
        assertEquals(DiffStatus.TOO_LARGE, large.status)
        assertTrue(large.newSize > RepoHistory.MAX_DIFF_BYTES)
    }

    @Test
    fun `long diffs are cut at the line limit`(@TempDir dir: File) = withRepo(dir) { repo ->
        val diff = RepoHistory.fileDiff(repo, null, repo.blob(lines(RepoHistory.MAX_DIFF_LINES + 200)))
        assertTrue(diff.truncated)
        assertEquals(RepoHistory.MAX_DIFF_LINES, diff.hunks.sumOf { it.lines.size })

        val exact = RepoHistory.fileDiff(repo, null, repo.blob(lines(RepoHistory.MAX_DIFF_LINES)))
        assertFalse(exact.truncated)
        assertEquals(RepoHistory.MAX_DIFF_LINES, exact.hunks.single().lines.size)

        // 很多段改动：到上限后不再给出后面的段
        val old = lines(3000)
        val new = (1..3000).joinToString("") { if (it % 10 == 0) "changed $it\n" else "line $it\n" }
        val many = RepoHistory.fileDiff(repo, repo.blob(old), repo.blob(new))
        assertTrue(many.truncated)
        assertTrue(many.hunks.sumOf { it.lines.size } <= RepoHistory.MAX_DIFF_LINES)
    }

    @Test
    fun `crlf is stripped and a changed newline at end of file is not shown as a change`(@TempDir dir: File) = withRepo(dir) { repo ->
        val crlf = RepoHistory.fileDiff(repo, repo.blob("a\r\nb\r\nc\r\n"), repo.blob("a\r\nB\r\nc\r\n"))
        assertEquals(listOf(" a", "-b", "+B", " c"), crlf.hunks.single().render())

        // 只差末尾换行（手机上 Obsidian 保存的文件不带末尾换行）
        val onlyNewline = RepoHistory.fileDiff(repo, repo.blob("标题\n正文"), repo.blob("标题\n正文\n"))
        assertEquals(DiffStatus.UNCHANGED, onlyNewline.status)
        assertTrue(onlyNewline.newlineAtEndChanged)

        // 末尾换行变了、别处也改了：最后一行不显示成"删掉又加上"
        val both = RepoHistory.fileDiff(repo, repo.blob("第一行\n第二行\n最后一行"), repo.blob("第一行\n第二行改了\n最后一行\n"))
        assertEquals(DiffStatus.TEXT, both.status)
        assertEquals(listOf(" 第一行", "-第二行", "+第二行改了", " 最后一行"), both.hunks.single().render())
    }

    @Test
    fun `appending to a file without newline at end does not show its last line as changed`(@TempDir dir: File) = withRepo(dir) { repo ->
        // 两侧末尾都不带换行：旧的最后一行到了新文件里变成中间行
        val append = RepoHistory.fileDiff(repo, repo.blob("第一行\n第二行\n最后一行"), repo.blob("第一行\n第二行\n最后一行\n新增一\n新增二"))
        assertEquals(listOf(" 第一行", " 第二行", " 最后一行", "+新增一", "+新增二"), append.hunks.single().render())
        assertFalse(append.newlineAtEndChanged)

        // 旧的不带、新的带末尾换行
        val withNewline = RepoHistory.fileDiff(repo, repo.blob("a\nb"), repo.blob("a\nb\nc\n"))
        assertEquals(listOf(" a", " b", "+c"), withNewline.hunks.single().render())
        assertTrue(withNewline.newlineAtEndChanged)

        // 删掉末尾几行、新文件末尾不带换行
        val cut = RepoHistory.fileDiff(repo, repo.blob("a\nb\nc\n"), repo.blob("a\nb"))
        assertEquals(listOf(" a", " b", "-c"), cut.hunks.single().render())
    }

    @Test
    fun `commit detail carries blob ids for each side`(@TempDir dir: File) {
        val vault = File(dir, "vault").apply { mkdirs() }
        VaultRepository.init(vault, "main", InMemorySyncLogger()).use { repo ->
            vault.writeVaultFile("A.md", "a\n")
            vault.writeVaultFile("B.md", "b\n".repeat(30))
            repo.commitAll("first", TestEnv.phone)
            vault.writeVaultFile("A.md", "a changed\n")
            File(vault, "B.md").renameTo(File(vault, "C.md"))
            vault.writeVaultFile("D.md", "d\n")
            val second = repo.commitAll("second", TestEnv.phone)!!
            File(vault, "D.md").delete()
            val third = repo.commitAll("third", TestEnv.phone)!!

            val detail = RepoHistory.detail(repo, "main", second.name).changes.associateBy { it.path }
            val a = detail.getValue("A.md")
            assertEquals(listOf("-a", "+a changed"), RepoHistory.fileDiff(repo, a.oldBlobId, a.newBlobId).hunks.single().lines.map {
                (if (it.type == LineType.DELETED) "-" else "+") + it.text
            })
            val c = detail.getValue("C.md")
            assertEquals(c.oldBlobId, c.newBlobId)
            assertEquals(DiffStatus.UNCHANGED, RepoHistory.fileDiff(repo, c.oldBlobId, c.newBlobId).status)
            val d = detail.getValue("D.md")
            assertNull(d.oldBlobId)
            assertEquals(listOf("d"), RepoHistory.fileDiff(repo, d.oldBlobId, d.newBlobId).hunks.single().lines.map { it.text })

            val removed = RepoHistory.detail(repo, "main", third.name).changes.single()
            assertEquals(RepoHistory.ChangeType.DELETED, removed.type)
            assertEquals(d.newBlobId, removed.oldBlobId)
            assertNull(removed.newBlobId)
        }
    }
}
