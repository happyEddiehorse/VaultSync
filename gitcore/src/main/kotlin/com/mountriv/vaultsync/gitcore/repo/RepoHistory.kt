package com.mountriv.vaultsync.gitcore.repo

import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.AbbreviatedObjectId
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectLoader
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.util.io.DisabledOutputStream

/**
 * 只读的提交历史（docs/UI.md §4.2、docs/SYNC_ENGINE.md §10.1）：当前分支最近的提交，某个提交相对第一父提交改动了哪些文件，
 * 以及单个文件的改动内容（[fileDiff]，带字词级对比，D-031）。
 */
object RepoHistory {

    data class CommitInfo(
        val id: String,
        val shortId: String,
        /** 提交信息第一行。 */
        val summary: String,
        val message: String,
        val authorName: String,
        val authorEmail: String,
        /** 作者时间（epoch 毫秒）。 */
        val time: Long,
        val parentCount: Int,
        /** 是否已在远端跟踪分支里（上次获取时的状态）；远端还没有该分支时为 false。 */
        val pushed: Boolean,
    ) {
        val isMerge: Boolean get() = parentCount > 1
    }

    data class Page(val commits: List<CommitInfo>, val hasMore: Boolean)

    enum class ChangeType { ADDED, MODIFIED, DELETED, RENAMED, COPIED }

    /** 一个改动的文件。[oldBlobId] / [newBlobId] 是两侧内容的 blob id（新增时旧的一侧为 null，删除时新的一侧为 null），供 [fileDiff] 用。 */
    data class FileChange(
        val type: ChangeType,
        val path: String,
        val oldPath: String? = null,
        val oldBlobId: String? = null,
        val newBlobId: String? = null,
    )

    data class CommitDetail(
        val commit: CommitInfo,
        /** 相对第一父提交的改动（根提交相对空树），按路径排序；最多 [maxFiles] 个。 */
        val changes: List<FileChange>,
        val totalChanges: Int,
        /** 合并提交：[changes] 是相对第一父提交（合并前的本地）而言的。 */
        val comparedToFirstParent: Boolean,
    )

    /** 当前分支从 HEAD 往回的第 [skip] 个起、最多 [limit] 个提交。分支还没有提交时返回空页。 */
    fun recent(repo: VaultRepository, branch: String, skip: Int = 0, limit: Int = 50): Page {
        val head = repo.headId() ?: return Page(emptyList(), false)
        val unpushed = unpushed(repo, head, branch)
        RevWalk(repo.repository).use { walk ->
            walk.markStart(walk.parseCommit(head))
            val out = ArrayList<CommitInfo>(limit)
            var index = 0
            var hasMore = false
            for (c in walk) {
                if (index++ < skip) continue
                if (out.size == limit) {
                    hasMore = true
                    break
                }
                out += info(c, pushed = c.id !in unpushed.ids && !unpushed.truncated)
            }
            return Page(out, hasMore)
        }
    }

    fun detail(repo: VaultRepository, branch: String, commitId: String, maxFiles: Int = 500): CommitDetail {
        val id = ObjectId.fromString(commitId)
        RevWalk(repo.repository).use { walk ->
            val commit = walk.parseCommit(id)
            val head = repo.headId()
            val unpushed = head?.let { unpushed(repo, it, branch) }
            val pushed = unpushed != null && commit.id !in unpushed.ids && !unpushed.truncated
            val entries = DiffFormatter(DisabledOutputStream.INSTANCE).use { fmt ->
                fmt.setRepository(repo.repository)
                fmt.setDetectRenames(true)
                if (commit.parentCount > 0) {
                    val parent = walk.parseCommit(commit.getParent(0))
                    fmt.scan(parent.tree, commit.tree)
                } else {
                    repo.repository.newObjectReader().use { reader ->
                        fmt.scan(EmptyTreeIterator(), CanonicalTreeParser(null, reader, commit.tree))
                    }
                }
            }
            val changes = entries.map { e ->
                val oldId = e.oldId.blobIdOrNull()
                val newId = e.newId.blobIdOrNull()
                when (e.changeType) {
                    DiffEntry.ChangeType.ADD -> FileChange(ChangeType.ADDED, e.newPath, newBlobId = newId)
                    DiffEntry.ChangeType.MODIFY -> FileChange(ChangeType.MODIFIED, e.newPath, oldBlobId = oldId, newBlobId = newId)
                    DiffEntry.ChangeType.DELETE -> FileChange(ChangeType.DELETED, e.oldPath, oldBlobId = oldId)
                    DiffEntry.ChangeType.RENAME -> FileChange(ChangeType.RENAMED, e.newPath, e.oldPath, oldId, newId)
                    DiffEntry.ChangeType.COPY -> FileChange(ChangeType.COPIED, e.newPath, e.oldPath, oldId, newId)
                    null -> FileChange(ChangeType.MODIFIED, e.newPath, oldBlobId = oldId, newBlobId = newId)
                }
            }.sortedBy { it.path }
            return CommitDetail(info(commit, pushed), changes.take(maxFiles), changes.size, commit.parentCount > 1)
        }
    }

    /** 扫描树得到的 id 是完整的；新增 / 删除时对应一侧是全零 id。 */
    private fun AbbreviatedObjectId?.blobIdOrNull(): String? =
        this?.toObjectId()?.takeIf { it != ObjectId.zeroId() }?.name

    // ---- 单个文件的改动（D-031） ----------------------------------------------------------------

    enum class DiffStatus {
        /** 文本改动，见 [FileDiff.hunks]。 */
        TEXT,

        /** 任一侧是二进制（例如图片），不显示内容。 */
        BINARY,

        /** 任一侧超过 [MAX_DIFF_BYTES]，不显示改动。 */
        TOO_LARGE,

        /** 内容没有变化（只改名；或只有文件末尾的换行不同，见 [FileDiff.newlineAtEndChanged]）。 */
        UNCHANGED,
    }

    enum class LineType { CONTEXT, DELETED, ADDED }

    /** 一行：文本不含行尾符（`\r` 也去掉）；[changed] 是字词级对比找出的改动区间（`start until end`），没有时为空。 */
    data class DiffLine(val type: LineType, val text: String, val changed: List<IntRange> = emptyList())

    /**
     * 一段改动（上下各带 [CONTEXT_LINES] 行上下文）；[oldStart] / [newStart] 是这一段第一行在旧 / 新文件里的行号（从 1 起）。
     * [before] 是上一段（第一段则是文件开头）到这一段之间没改的行，界面上折叠成"⋯"，点开才显示（docs/UI.md §4.2）。
     */
    data class Hunk(val oldStart: Int, val newStart: Int, val lines: List<DiffLine>, val before: List<String> = emptyList())

    data class FileDiff(
        val status: DiffStatus,
        val hunks: List<Hunk> = emptyList(),
        /** 改动超过 [MAX_DIFF_LINES] 行，只给出了前面的部分。 */
        val truncated: Boolean = false,
        val oldSize: Long = 0,
        val newSize: Long = 0,
        /** 文件末尾有没有换行变了。对比时已抹平这个差别（Obsidian 手机端保存的文件末尾不带换行，桌面端常带），这里只做说明。 */
        val newlineAtEndChanged: Boolean = false,
        /** 最后一段之后没改的行（同 [Hunk.before]）；改动被截断时为空。 */
        val after: List<String> = emptyList(),
    )

    /**
     * 一个文件两侧内容的改动（统一 diff，`HistogramDiff` + `RawTextComparator.DEFAULT`，每处改动上下各 [CONTEXT_LINES] 行，
     * 上下文重叠的相邻改动并成一段；成对的删除 / 新增行再做字词级对比 [WordDiff]）。
     * [oldBlobId] / [newBlobId] 来自 [FileChange]；新增文件旧的一侧为 null，删除文件新的一侧为 null。阻塞调用，放 IO 线程。
     */
    fun fileDiff(repo: VaultRepository, oldBlobId: String?, newBlobId: String?): FileDiff {
        val old = oldBlobId?.let { repo.repository.open(ObjectId.fromString(it), Constants.OBJ_BLOB) }
        val new = newBlobId?.let { repo.repository.open(ObjectId.fromString(it), Constants.OBJ_BLOB) }
        val oldSize = old?.size ?: 0L
        val newSize = new?.size ?: 0L
        if (oldBlobId == newBlobId) return FileDiff(DiffStatus.UNCHANGED, oldSize = oldSize, newSize = newSize)
        if (listOfNotNull(old, new).any { it.isBinary() }) return FileDiff(DiffStatus.BINARY, oldSize = oldSize, newSize = newSize)
        if (oldSize > MAX_DIFF_BYTES || newSize > MAX_DIFF_BYTES) return FileDiff(DiffStatus.TOO_LARGE, oldSize = oldSize, newSize = newSize)
        return diffTexts(
            old?.getCachedBytes(MAX_DIFF_BYTES) ?: ByteArray(0),
            new?.getCachedBytes(MAX_DIFF_BYTES) ?: ByteArray(0),
        ).copy(oldSize = oldSize, newSize = newSize)
    }

    /** [fileDiff] 的文本部分（两侧都是文本、大小没超限）。 */
    internal fun diffTexts(oldBytes: ByteArray, newBytes: ByteArray): FileDiff {
        var a = RawText(oldBytes)
        var b = RawText(newBytes)
        // 对比时行尾符也算在行里：末尾不带换行的一侧先补上，否则最后一行会显示成"删掉又加上"——
        // 两侧只差末尾换行时如此；在不带末尾换行的文件后面追加内容时也如此（旧的最后一行没有换行，到了新文件里变成中间行，有换行）
        val aMissing = a.size() > 0 && a.isMissingNewlineAtEnd
        val bMissing = b.size() > 0 && b.isMissingNewlineAtEnd
        val newlineAtEndChanged = a.size() > 0 && b.size() > 0 && aMissing != bMissing
        if (aMissing) a = RawText(oldBytes + '\n'.code.toByte())
        if (bMissing) b = RawText(newBytes + '\n'.code.toByte())
        val edits = HistogramDiff().diff(RawTextComparator.DEFAULT, a, b)
        if (edits.isEmpty()) return FileDiff(DiffStatus.UNCHANGED, newlineAtEndChanged = newlineAtEndChanged)
        val builder = HunkBuilder(a, b)
        var i = 0
        while (i < edits.size && !builder.full) {
            // 下一处改动与这一处的上下文重叠（间隔不超过两倍上下文）就并进同一段，与 git 的做法一致
            var j = i
            while (j + 1 < edits.size && edits[j + 1].beginA - edits[j].endA <= 2 * CONTEXT_LINES) j++
            builder.hunk(edits.subList(i, j + 1))
            i = j + 1
        }
        val truncated = builder.full && builder.hasMore(i, edits.size)
        return FileDiff(
            DiffStatus.TEXT,
            builder.hunks,
            truncated = truncated,
            newlineAtEndChanged = newlineAtEndChanged,
            after = if (truncated) emptyList() else builder.rest(),
        )
    }

    /** 把改动切成段，累计超过 [MAX_DIFF_LINES] 行就停下。 */
    private class HunkBuilder(private val a: RawText, private val b: RawText) {
        val hunks = ArrayList<Hunk>()
        private var count = 0
        private var cut = false

        /** 已给出的最后一段在旧文件里的结束位置（不含）；没改的行两侧相同，从旧文件取。 */
        private var shownEnd = 0

        /** 最后一段之后没改的行。 */
        fun rest(): List<String> = (shownEnd until a.size()).map { a.line(it) }

        /** 已经到行数上限。 */
        val full: Boolean get() = cut || count >= MAX_DIFF_LINES

        /** 到上限时后面是否还有没给出的内容。 */
        fun hasMore(nextEdit: Int, totalEdits: Int): Boolean = cut || nextEdit < totalEdits

        fun hunk(group: List<Edit>) {
            val aStart = maxOf(0, group.first().beginA - CONTEXT_LINES)
            val bStart = maxOf(0, group.first().beginB - CONTEXT_LINES)
            val aEnd = minOf(a.size(), group.last().endA + CONTEXT_LINES)
            val lines = ArrayList<DiffLine>()
            fun add(line: DiffLine): Boolean {
                if (count >= MAX_DIFF_LINES) {
                    cut = true
                    return false
                }
                lines += line
                count++
                return true
            }
            var ai = aStart
            run {
                for (e in group) {
                    while (ai < e.beginA) if (!add(DiffLine(LineType.CONTEXT, a.line(ai++)))) return@run
                    val deleted = (e.beginA until e.endA).map { a.line(it) }
                    val added = (e.beginB until e.endB).map { b.line(it) }
                    // 删除行与新增行按顺序一一配对做字词对比，多出来的不配对
                    val words = (0 until minOf(deleted.size, added.size)).map { WordDiff.compare(deleted[it], added[it]) }
                    for ((k, text) in deleted.withIndex()) {
                        if (!add(DiffLine(LineType.DELETED, text, words.getOrNull(k)?.oldChanged.orEmpty()))) return@run
                    }
                    for ((k, text) in added.withIndex()) {
                        if (!add(DiffLine(LineType.ADDED, text, words.getOrNull(k)?.newChanged.orEmpty()))) return@run
                    }
                    ai = e.endA
                }
                while (ai < aEnd) if (!add(DiffLine(LineType.CONTEXT, a.line(ai++)))) return@run
            }
            if (lines.isNotEmpty()) {
                hunks += Hunk(aStart + 1, bStart + 1, lines, before = (shownEnd until aStart).map { a.line(it) })
                shownEnd = ai
            }
        }

        private fun RawText.line(i: Int): String = getString(i).removeSuffix("\r")
    }

    private fun ObjectLoader.isBinary(): Boolean = openStream().use { RawText.isBinary(it) }

    /** 上下文行数、单侧最大字节数、最多给出的行数（docs/UI.md §4.2）。 */
    const val CONTEXT_LINES = 3
    const val MAX_DIFF_BYTES = 1 shl 20
    const val MAX_DIFF_LINES = 1000

    private class Unpushed(val ids: Set<ObjectId>, val truncated: Boolean)

    /** HEAD 可达、远端跟踪分支不可达的提交（最多 [MAX_UNPUSHED] 个；再多就当作都未推送）。 */
    private fun unpushed(repo: VaultRepository, head: ObjectId, branch: String): Unpushed {
        val remote = repo.remoteTrackingId(branch) ?: return Unpushed(emptySet(), truncated = true)
        RevWalk(repo.repository).use { walk ->
            walk.markStart(walk.parseCommit(head))
            walk.markUninteresting(walk.parseCommit(remote))
            val ids = HashSet<ObjectId>()
            for (c in walk) {
                if (ids.size >= MAX_UNPUSHED) return Unpushed(ids, truncated = true)
                ids += c.id.copy()
            }
            return Unpushed(ids, truncated = false)
        }
    }

    private fun info(c: RevCommit, pushed: Boolean): CommitInfo {
        val author = c.authorIdent
        return CommitInfo(
            id = c.name,
            shortId = c.name.take(7),
            summary = c.shortMessage,
            message = c.fullMessage,
            authorName = author.name,
            authorEmail = author.emailAddress,
            time = author.`when`.time,
            parentCount = c.parentCount,
            pushed = pushed,
        )
    }

    private const val MAX_UNPUSHED = 2000
}
