package com.mountriv.vaultsync.gitcore.merge

import com.mountriv.vaultsync.gitcore.model.SyncException
import com.mountriv.vaultsync.gitcore.model.SyncStage
import com.mountriv.vaultsync.gitcore.repo.VaultRepository
import org.eclipse.jgit.api.CheckoutCommand
import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.merge.MergeAlgorithm
import org.eclipse.jgit.merge.MergeChunk.ConflictState
import java.io.File

/** 冲突类型（对应 git 的 index stage 组合 / `IndexDiff.StageState`）。 */
enum class ConflictKind {
    /** 双方都修改了同一文件（stage 1+2+3）。 */
    BOTH_MODIFIED,

    /** 双方都新增了同名文件且内容不同（stage 2+3）。 */
    BOTH_ADDED,

    /** 本地删除、远端修改（stage 1+3）。 */
    DELETED_BY_US,

    /** 本地修改、远端删除（stage 1+2）。 */
    DELETED_BY_THEM,

    /** 只有本地有（stage 2）。 */
    ADDED_BY_US,

    /** 只有远端有（stage 3）。 */
    ADDED_BY_THEM,

    /** 双方都删除（stage 1）。 */
    BOTH_DELETED,
}

/** 冲突的一方：本地（index stage 2）或远端（stage 3）。 */
enum class Side { OURS, THEIRS }

/** 用户对一个冲突块的取舍。 */
sealed class Choice {
    data object Undecided : Choice()
    data object Ours : Choice()
    data object Theirs : Choice()
    data object BothOursFirst : Choice()
    data object BothTheirsFirst : Choice()

    /** 用户手动编辑的文本（不含行尾风格差异，内部按 `\n` 分行）。 */
    data class Custom(val text: String) : Choice()
}

/** 文件被切成的块：相同块原样保留，冲突块需要选择。行不含行尾符。 */
sealed class Block {
    data class Common(val lines: List<String>) : Block()

    data class Conflict(
        val ours: List<String>,
        val theirs: List<String>,
        val choice: Choice = Choice.Undecided,
    ) : Block() {
        val isDecided: Boolean get() = choice != Choice.Undecided

        /** 按 [choice] 展开的行；未决定时返回 null。 */
        fun resolvedLines(): List<String>? = when (val c = choice) {
            Choice.Undecided -> null
            Choice.Ours -> ours
            Choice.Theirs -> theirs
            Choice.BothOursFirst -> ours + theirs
            Choice.BothTheirsFirst -> theirs + ours
            is Choice.Custom -> MergeModel.splitLines(c.text)
        }
    }
}

/**
 * 一个冲突文件的模型。
 * [blocks] 为 null 表示不能按块处理（二进制、过大、或一侧不存在），只能整文件选择。
 */
data class ConflictFile(
    val path: String,
    val kind: ConflictKind,
    val isBinary: Boolean,
    val oursSize: Long,
    val theirsSize: Long,
    val blocks: List<Block>?,
    /** 文件的行尾风格（取本地版本，没有则远端），默认 `\n`。 */
    val lineEnding: String = "\n",
    val endsWithNewline: Boolean = true,
    /** 本地 / 远端 blob 的缩写 id（7 位），用于二进制文件展示；一侧不存在时为 null。 */
    val oursId: String? = null,
    val theirsId: String? = null,
) {
    val conflictCount: Int get() = blocks?.count { it is Block.Conflict } ?: 0
    val decidedCount: Int get() = blocks?.count { it is Block.Conflict && it.isDecided } ?: 0
    val allDecided: Boolean get() = decidedCount == conflictCount

    /** 本地一侧是否存在该文件（index 有 stage 2）。 */
    val hasOurs: Boolean
        get() = kind != ConflictKind.DELETED_BY_US && kind != ConflictKind.ADDED_BY_THEM && kind != ConflictKind.BOTH_DELETED

    /** 远端一侧是否存在该文件（index 有 stage 3）。 */
    val hasTheirs: Boolean
        get() = kind != ConflictKind.DELETED_BY_THEM && kind != ConflictKind.ADDED_BY_US && kind != ConflictKind.BOTH_DELETED

    /** 第 [index] 个冲突块（按冲突块计数，从 0 起）；越界返回 null。 */
    fun conflictAt(index: Int): Block.Conflict? = blocks?.filterIsInstance<Block.Conflict>()?.getOrNull(index)

    /** 第一个未决定的冲突块在 [blocks] 里的下标；全部决定时为 null。 */
    val firstUndecidedBlockIndex: Int?
        get() = blocks?.indexOfFirst { it is Block.Conflict && !it.isDecided }?.takeIf { it >= 0 }

    /** 把第 [index] 个冲突块（按冲突块计数，从 0 起）设为 [choice]。 */
    fun withChoice(index: Int, choice: Choice): ConflictFile {
        val blocks = blocks ?: return this
        var seen = -1
        return copy(
            blocks = blocks.map { b ->
                if (b is Block.Conflict) {
                    seen++
                    if (seen == index) b.copy(choice = choice) else b
                } else {
                    b
                }
            },
        )
    }

    fun withAll(choice: Choice): ConflictFile =
        copy(blocks = blocks?.map { if (it is Block.Conflict) it.copy(choice = choice) else it })

    /** 按当前选择渲染整文件；有未决定的块时返回 null。 */
    fun render(): String? {
        val blocks = blocks ?: return null
        val lines = ArrayList<String>()
        for (b in blocks) {
            when (b) {
                is Block.Common -> lines += b.lines
                is Block.Conflict -> lines += b.resolvedLines() ?: return null
            }
        }
        return MergeModel.joinLines(lines, lineEnding, endsWithNewline)
    }

    /**
     * 供整文件编辑器预填的文本：已决定的块按选择展开，未决定的块用 git 风格的冲突标记包起来
     * （`<<<<<<< 本地` / `=======` / `>>>>>>> 远端`），让用户在编辑器里自己处理。统一用 `\n` 分行。
     */
    fun renderForEditing(oursLabel: String = "ours", theirsLabel: String = "theirs"): String? {
        val blocks = blocks ?: return null
        val lines = ArrayList<String>()
        for (b in blocks) {
            when (b) {
                is Block.Common -> lines += b.lines
                is Block.Conflict -> {
                    val resolved = b.resolvedLines()
                    if (resolved != null) {
                        lines += resolved
                    } else {
                        lines += "${MergeModel.MARKER_OURS} $oursLabel"
                        lines += b.ours
                        lines += MergeModel.MARKER_SEPARATOR
                        lines += b.theirs
                        lines += "${MergeModel.MARKER_THEIRS} $theirsLabel"
                    }
                }
            }
        }
        return MergeModel.joinLines(lines, "\n", endsWithNewline)
    }
}

/**
 * 冲突块模型（docs/SYNC_ENGINE.md §6）：不解析工作区里的 `<<<<<<<` 标记，
 * 直接从 index 取 stage 1/2/3 三方 blob，用 JGit [MergeAlgorithm] 生成块，并提供写回/取舍操作。
 */
object MergeModel {

    /** 超过这个大小的文本不做块级处理（避免把堆撑爆），只允许整文件选择。 */
    const val MAX_TEXT_BYTES: Int = 8 * 1024 * 1024

    const val MARKER_OURS = "<<<<<<<"
    const val MARKER_SEPARATOR = "======="
    const val MARKER_THEIRS = ">>>>>>>"

    /** 列出仓库中所有冲突文件（按路径排序）。 */
    fun listConflicts(repo: VaultRepository): List<ConflictFile> {
        val dc = repo.repository.readDirCache()
        val byPath = LinkedHashMap<String, Array<DirCacheEntry?>>()
        for (i in 0 until dc.entryCount) {
            val e = dc.getEntry(i)
            if (e.stage == DirCacheEntry.STAGE_0) continue
            byPath.getOrPut(e.pathString) { arrayOfNulls(4) }[e.stage] = e
        }
        return byPath.entries.sortedBy { it.key }.map { (path, stages) -> load(repo, path, stages) }
    }

    /** 当前仍未解决的冲突路径（index 有 stage>0 条目），已排序。比 [listConflicts] 便宜得多。 */
    fun unresolvedPaths(repo: VaultRepository): List<String> = summaries(repo).map { it.path }

    /** 列表页用的轻量摘要：只读 index，二进制判断只看 blob 开头 8 KB。 */
    data class ConflictSummary(val path: String, val kind: ConflictKind, val isBinary: Boolean)

    /** 当前仍未解决的冲突文件摘要（按路径排序）。 */
    fun summaries(repo: VaultRepository): List<ConflictSummary> {
        val dc = repo.repository.readDirCache()
        val byPath = LinkedHashMap<String, Array<DirCacheEntry?>>()
        for (i in 0 until dc.entryCount) {
            val e = dc.getEntry(i)
            if (e.stage == DirCacheEntry.STAGE_0) continue
            byPath.getOrPut(e.pathString) { arrayOfNulls(4) }[e.stage] = e
        }
        return byPath.entries.sortedBy { it.key }.map { (path, stages) ->
            val sides = listOfNotNull(stages[DirCacheEntry.STAGE_2], stages[DirCacheEntry.STAGE_3])
            ConflictSummary(path, kindOf(stages), sides.any { isBinaryBlob(repo, it.objectId) })
        }
    }

    private fun kindOf(stages: Array<DirCacheEntry?>): ConflictKind {
        val base = stages[DirCacheEntry.STAGE_1]
        val ours = stages[DirCacheEntry.STAGE_2]
        val theirs = stages[DirCacheEntry.STAGE_3]
        return when {
            base != null && ours != null && theirs != null -> ConflictKind.BOTH_MODIFIED
            base == null && ours != null && theirs != null -> ConflictKind.BOTH_ADDED
            base != null && ours == null && theirs != null -> ConflictKind.DELETED_BY_US
            base != null && ours != null && theirs == null -> ConflictKind.DELETED_BY_THEM
            ours != null -> ConflictKind.ADDED_BY_US
            theirs != null -> ConflictKind.ADDED_BY_THEM
            else -> ConflictKind.BOTH_DELETED
        }
    }

    /**
     * 本次合并开始时冲突的全部路径：JGit 在 `MERGE_MSG` 里写了 "Conflicts:" 清单（`MergeMessageFormatter.formatWithConflicts`），
     * 这里把它解析出来，再并上当前仍未解决的路径（清单可能被桌面工具改写）。用于列表页显示"已解决"的文件。
     */
    fun originalConflictPaths(repo: VaultRepository): List<String> {
        val fromMsg = LinkedHashSet<String>()
        val msg = runCatching { repo.repository.readMergeCommitMsg() }.getOrNull()
        if (msg != null) {
            var inList = false
            for (raw in msg.split('\n')) {
                val line = raw.trimEnd('\r')
                if (!inList) {
                    if (line.trim() == "Conflicts:") inList = true
                    continue
                }
                if (line.startsWith("\t") || line.startsWith(" ")) {
                    val p = line.trim()
                    if (p.isNotEmpty()) fromMsg += p
                } else if (line.isBlank()) {
                    continue
                } else {
                    break
                }
            }
        }
        fromMsg += unresolvedPaths(repo)
        return fromMsg.sorted()
    }

    /** 重新加载单个文件的模型（例如用户在别处改了选择后刷新）。不存在冲突时返回 null。 */
    fun loadConflict(repo: VaultRepository, path: String): ConflictFile? {
        val dc = repo.repository.readDirCache()
        val stages = arrayOfNulls<DirCacheEntry>(4)
        var found = false
        for (i in 0 until dc.entryCount) {
            val e = dc.getEntry(i)
            if (e.pathString == path && e.stage != DirCacheEntry.STAGE_0) {
                stages[e.stage] = e
                found = true
            }
        }
        return if (found) load(repo, path, stages) else null
    }

    private fun load(repo: VaultRepository, path: String, stages: Array<DirCacheEntry?>): ConflictFile {
        val base = stages[DirCacheEntry.STAGE_1]
        val ours = stages[DirCacheEntry.STAGE_2]
        val theirs = stages[DirCacheEntry.STAGE_3]
        val kind = kindOf(stages)
        val oursSize = ours?.let { blobSize(repo, it.objectId) } ?: 0L
        val theirsSize = theirs?.let { blobSize(repo, it.objectId) } ?: 0L
        val oursId = ours?.objectId?.abbreviate(7)?.name()
        val theirsId = theirs?.objectId?.abbreviate(7)?.name()

        if (ours == null || theirs == null) {
            val isBinary = listOfNotNull(ours, theirs).any { isBinaryBlob(repo, it.objectId) }
            return ConflictFile(path, kind, isBinary, oursSize, theirsSize, blocks = null, oursId = oursId, theirsId = theirsId)
        }
        if (oursSize > MAX_TEXT_BYTES || theirsSize > MAX_TEXT_BYTES) {
            return ConflictFile(path, kind, isBinary = false, oursSize, theirsSize, blocks = null, oursId = oursId, theirsId = theirsId)
        }
        val oursBytes = blob(repo, ours.objectId)
        val theirsBytes = blob(repo, theirs.objectId)
        if (RawText.isBinary(oursBytes) || RawText.isBinary(theirsBytes)) {
            return ConflictFile(path, kind, isBinary = true, oursSize, theirsSize, blocks = null, oursId = oursId, theirsId = theirsId)
        }
        val baseBytes = base?.let { blob(repo, it.objectId) } ?: ByteArray(0)
        val oursText = RawText(oursBytes)
        val theirsText = RawText(theirsBytes)
        val lineEnding = oursText.lineDelimiter ?: theirsText.lineDelimiter ?: "\n"
        // 末尾换行：两侧任一有就保留。Obsidian 手机端写文件不带末尾换行，只看本地会让"用远端"的结果也少一个字节，产生假差异
        val endsWithNewline = (oursText.size() > 0 && !oursText.isMissingNewlineAtEnd) ||
            (theirsText.size() > 0 && !theirsText.isMissingNewlineAtEnd)
        return ConflictFile(
            path = path,
            kind = kind,
            isBinary = false,
            oursSize = oursSize,
            theirsSize = theirsSize,
            blocks = buildBlocks(RawText(baseBytes), oursText, theirsText),
            lineEnding = lineEnding,
            endsWithNewline = endsWithNewline,
            oursId = oursId,
            theirsId = theirsId,
        )
    }

    /**
     * 用 JGit 三方合并算法把 base/ours/theirs 切成块。
     * 5.13 的算法只为冲突输出 ours（FIRST_CONFLICTING_RANGE）与 theirs（NEXT_CONFLICTING_RANGE）两段，不含 base。
     */
    fun buildBlocks(base: RawText, ours: RawText, theirs: RawText): List<Block> {
        val result = MergeAlgorithm().merge(RawTextComparator.DEFAULT, base, ours, theirs)
        val sequences = result.sequences
        val blocks = ArrayList<Block>()
        val common = ArrayList<String>()
        var pendingOurs: List<String>? = null
        var pendingTheirs: List<String>? = null

        fun flushCommon() {
            if (common.isNotEmpty()) {
                blocks += Block.Common(ArrayList(common))
                common.clear()
            }
        }

        fun flushConflict() {
            if (pendingOurs != null || pendingTheirs != null) {
                blocks += Block.Conflict(pendingOurs ?: emptyList(), pendingTheirs ?: emptyList())
                pendingOurs = null
                pendingTheirs = null
            }
        }

        for (chunk in result) {
            val lines = sequences[chunk.sequenceIndex].lines(chunk.begin, chunk.end)
            when (chunk.conflictState) {
                ConflictState.NO_CONFLICT -> {
                    flushConflict()
                    common += lines
                }
                ConflictState.FIRST_CONFLICTING_RANGE -> {
                    flushConflict()
                    flushCommon()
                    if (chunk.sequenceIndex == 2) pendingTheirs = lines else pendingOurs = lines
                }
                ConflictState.NEXT_CONFLICTING_RANGE -> {
                    if (chunk.sequenceIndex == 2) pendingTheirs = (pendingTheirs ?: emptyList()) + lines
                    else pendingOurs = (pendingOurs ?: emptyList()) + lines
                }
                else -> {
                    // 5.13 没有其它状态；防御性处理为普通内容
                    flushConflict()
                    common += lines
                }
            }
        }
        flushConflict()
        flushCommon()
        return blocks
    }

    // ---- 写回 / 取舍 ------------------------------------------------------------------------

    /** 用 [text] 作为最终内容写回并标记已解决（index 回到 stage 0）。 */
    fun resolveWithText(repo: VaultRepository, path: String, text: String) {
        val file = File(repo.workTree, path)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
        repo.git.add().addFilepattern(path).call()
    }

    /** 整文件采用本地版本（stage 2）。 */
    fun resolveTakeOurs(repo: VaultRepository, path: String) = takeStage(repo, path, CheckoutCommand.Stage.OURS)

    /** 整文件采用远端版本（stage 3）。 */
    fun resolveTakeTheirs(repo: VaultRepository, path: String) = takeStage(repo, path, CheckoutCommand.Stage.THEIRS)

    /** 整文件采用 [side] 一方的版本；该方在 index 里不存在（对方删除了它）时抛 [SyncException]。 */
    fun resolveTakeSide(repo: VaultRepository, path: String, side: Side) {
        val stage = if (side == Side.OURS) DirCacheEntry.STAGE_2 else DirCacheEntry.STAGE_3
        if (!hasStage(repo, path, stage)) {
            throw SyncException(SyncStage.RESOLVE_CONFLICTS, "$path has no $side version to keep")
        }
        takeStage(repo, path, if (side == Side.OURS) CheckoutCommand.Stage.OURS else CheckoutCommand.Stage.THEIRS)
    }

    /**
     * 以"删除该文件"作为解决结果：直接重写 DirCache 去掉该路径的全部 stage，再删工作区文件（若存在）。
     * 等价于 `git rm`；不经 RmCommand，这样文件在工作区存在与否两种情况走同一条路径。
     */
    fun resolveDelete(repo: VaultRepository, path: String) {
        val dc = repo.repository.lockDirCache()
        try {
            val builder = dc.builder()
            for (i in 0 until dc.entryCount) {
                val e = dc.getEntry(i)
                if (e.pathString != path) builder.add(e)
            }
            builder.finish()
            builder.commit()
        } finally {
            dc.unlock()
        }
        val f = File(repo.workTree, path)
        if (f.exists()) f.delete()
    }

    /**
     * 批量：所有仍未解决的文件都采用 [side]；该方不存在的文件按"删除"处理（对方删了它，我们也认可）。
     * 返回处理过的路径。
     */
    fun resolveAll(repo: VaultRepository, side: Side): List<String> {
        val stage = if (side == Side.OURS) DirCacheEntry.STAGE_2 else DirCacheEntry.STAGE_3
        val paths = unresolvedPaths(repo)
        for (p in paths) {
            if (hasStage(repo, p, stage)) resolveTakeSide(repo, p, side) else resolveDelete(repo, p)
        }
        return paths
    }

    /** 读取 [side] 一方的文本内容（UTF-8）；该方不存在、是二进制或过大时返回 null。供整文件编辑器预填。 */
    fun readSideText(repo: VaultRepository, path: String, side: Side): String? {
        val stage = if (side == Side.OURS) DirCacheEntry.STAGE_2 else DirCacheEntry.STAGE_3
        val id = stageObjectId(repo, path, stage) ?: return null
        val loader = repo.repository.open(id)
        if (loader.size > MAX_TEXT_BYTES) return null
        val bytes = loader.getCachedBytes(MAX_TEXT_BYTES)
        if (RawText.isBinary(bytes)) return null
        return String(bytes, Charsets.UTF_8)
    }

    private fun takeStage(repo: VaultRepository, path: String, stage: CheckoutCommand.Stage) {
        repo.git.checkout().setStage(stage).addPath(path).call()
        repo.git.add().addFilepattern(path).call()
    }

    private fun hasStage(repo: VaultRepository, path: String, stage: Int): Boolean = stageObjectId(repo, path, stage) != null

    private fun stageObjectId(repo: VaultRepository, path: String, stage: Int): ObjectId? {
        val dc = repo.repository.readDirCache()
        for (i in 0 until dc.entryCount) {
            val e = dc.getEntry(i)
            if (e.pathString == path && e.stage == stage) return e.objectId
        }
        return null
    }

    // ---- 块内差异 ------------------------------------------------------------------------------

    /** 一个冲突块里两侧各自"与对方不同"的行号（从 0 起）。两侧都有的行不标记。 */
    data class LineDiff(val oursChanged: Set<Int>, val theirsChanged: Set<Int>)

    /** 用 JGit 的 diff 找出 [ours] 与 [theirs] 之间不同的行，供界面高亮。 */
    fun changedLines(ours: List<String>, theirs: List<String>): LineDiff {
        if (ours.isEmpty() || theirs.isEmpty()) {
            return LineDiff(ours.indices.toSet(), theirs.indices.toSet())
        }
        val a = RawText(joinLines(ours, "\n", true).toByteArray(Charsets.UTF_8))
        val b = RawText(joinLines(theirs, "\n", true).toByteArray(Charsets.UTF_8))
        val edits = HistogramDiff().diff(RawTextComparator.DEFAULT, a, b)
        val oursChanged = HashSet<Int>()
        val theirsChanged = HashSet<Int>()
        for (e in edits) {
            for (i in e.beginA until e.endA) oursChanged += i
            for (i in e.beginB until e.endB) theirsChanged += i
        }
        return LineDiff(oursChanged, theirsChanged)
    }

    // ---- 工具 ------------------------------------------------------------------------------

    /** 把文本按行拆开（兼容 `\r\n`），末尾换行不产生空行。 */
    fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val normalized = text.replace("\r\n", "\n")
        val parts = normalized.split('\n')
        return if (normalized.endsWith("\n")) parts.dropLast(1) else parts
    }

    fun joinLines(lines: List<String>, lineEnding: String, endsWithNewline: Boolean): String {
        if (lines.isEmpty()) return ""
        val body = lines.joinToString(lineEnding)
        return if (endsWithNewline) body + lineEnding else body
    }

    /** 文本里是否还有 git 风格的冲突标记行（用户在整文件编辑器里可能漏处理）。 */
    fun containsConflictMarkers(text: String): Boolean = conflictMarkerLines(text).isNotEmpty()

    /** 冲突标记行的行号（从 1 起）：以 `<<<<<<<` / `>>>>>>>` 开头，或整行恰为 `=======`。 */
    fun conflictMarkerLines(text: String): List<Int> =
        splitLines(text).withIndex().filter { (_, line) -> isMarkerLine(line) }.map { it.index + 1 }

    fun isMarkerLine(line: String): Boolean =
        line.startsWith(MARKER_OURS) || line == MARKER_SEPARATOR || line.startsWith(MARKER_THEIRS)

    private fun RawText.lines(begin: Int, end: Int): List<String> =
        (begin until end).map { getString(it).removeSuffix("\r") }

    private fun blob(repo: VaultRepository, id: ObjectId): ByteArray =
        repo.repository.open(id).getCachedBytes(MAX_TEXT_BYTES)

    private fun blobSize(repo: VaultRepository, id: ObjectId): Long = repo.repository.open(id).size

    /** 只看开头 8 KB 判断是否二进制，不把整个 blob 读进内存。 */
    private fun isBinaryBlob(repo: VaultRepository, id: ObjectId): Boolean {
        val loader = repo.repository.open(id)
        if (loader.size > MAX_TEXT_BYTES) return true
        return loader.openStream().use { RawText.isBinary(it) }
    }
}
