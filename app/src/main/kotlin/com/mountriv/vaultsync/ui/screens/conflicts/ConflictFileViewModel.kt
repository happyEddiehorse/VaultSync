package com.mountriv.vaultsync.ui.screens.conflicts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mountriv.vaultsync.AppGraph
import com.mountriv.vaultsync.gitcore.merge.Block
import com.mountriv.vaultsync.gitcore.merge.Choice
import com.mountriv.vaultsync.gitcore.merge.ConflictFile
import com.mountriv.vaultsync.gitcore.merge.MergeModel
import com.mountriv.vaultsync.gitcore.merge.Side
import com.mountriv.vaultsync.gitcore.model.RepoSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 冲突文件页的状态（docs/UI.md §6）。块的取舍只在内存里（[file]），"标记为已解决"时才写回工作区与 index。
 * 整文件编辑器的文本统一用 `\n` 分行，写回时换成文件原有的行尾。
 */
class ConflictFileViewModel(private val repoId: String, val path: String) : ViewModel() {

    val spec: RepoSpec? get() = AppGraph.repos.get(repoId)

    var file by mutableStateOf<ConflictFile?>(null)
        private set

    var loading by mutableStateOf(true)
        private set

    var busy by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)

    /** 已写回并标记解决；界面据此返回列表。 */
    var resolved by mutableStateOf(false)
        private set

    /** 该路径在 index 里已经没有冲突（别处解决了，或路径无效）。 */
    var missing by mutableStateOf(false)
        private set

    // ---- 块视图 ----

    /** 展开显示全部行的相同块（按 blocks 下标）。 */
    var expandedCommon by mutableStateOf<Set<Int>>(emptySet())
        private set

    /** 正在内嵌编辑的冲突块（按冲突块计数）与其文本。 */
    var editingBlock by mutableStateOf<Int?>(null)
        private set
    var blockEditText by mutableStateOf("")

    // ---- 整文件编辑器 ----

    var editorOpen by mutableStateOf(false)
        private set
    var editorValue by mutableStateOf(TextFieldValue(""))
    private var editorOriginal: String = ""

    /** 编辑器内容写回时使用的行尾；从某一方文本打开时按该文本检测，否则用块模型的。 */
    private var editorLineEnding: String? = null
    var searchQuery by mutableStateOf("")
    val editorDirty: Boolean get() = editorValue.text != editorOriginal

    init {
        load()
    }

    fun load() {
        val s = spec ?: run { missing = true; loading = false; return }
        viewModelScope.launch {
            loading = true
            val r = withContext(Dispatchers.IO) { runCatching { AppGraph.syncManager.loadConflict(s, path) } }
            r.onSuccess { f ->
                if (f == null) missing = true else file = f
                error = null
            }.onFailure { error = it.message ?: it.toString() }
            loading = false
        }
    }

    // ---- 取舍 ----

    fun choose(conflictIndex: Int, choice: Choice) {
        val f = file ?: return
        file = f.withChoice(conflictIndex, choice)
        // 决定后折叠；正在就地编辑的这一处关掉编辑器
        if (editingBlock == conflictIndex) editingBlock = null
    }

    fun chooseAll(choice: Choice) {
        file = file?.withAll(choice)
        editingBlock = null
    }

    fun resetChoices() = chooseAll(Choice.Undecided)

    fun toggleCommon(blockIndex: Int) {
        expandedCommon = if (blockIndex in expandedCommon) expandedCommon - blockIndex else expandedCommon + blockIndex
    }

    /** 开始内嵌编辑：预填当前选择的结果；未决定时预填"本地 + 远端"。 */
    fun startBlockEdit(conflictIndex: Int) {
        val c = file?.conflictAt(conflictIndex) ?: return
        val lines = c.resolvedLines() ?: (c.ours + c.theirs)
        blockEditText = MergeModel.joinLines(lines, "\n", true)
        editingBlock = conflictIndex
    }

    fun cancelBlockEdit() {
        editingBlock = null
    }

    fun saveBlockEdit() {
        val idx = editingBlock ?: return
        choose(idx, Choice.Custom(blockEditText))
        editingBlock = null
    }

    /** 第一个未决定块在 blocks 里的下标（供"定位"滚动）。 */
    val firstUndecidedBlockIndex: Int? get() = file?.firstUndecidedBlockIndex


    // ---- 整文件编辑器 ----

    /** 块视图里已有选择（未写回）；离开页面前要确认，打开整篇编辑时要问是否带上这些选择。 */
    val hasChoices: Boolean get() = (file?.decidedCount ?: 0) > 0

    /**
     * 打开整篇编辑器。[keepChoices] 为 true 时用块视图的当前结果（未决定的块用冲突标记包起来）；
     * 为 false 时所有冲突都以标记形式出现（块视图里的选择不受影响）。
     */
    fun openEditorFromBlocks(oursLabel: String, theirsLabel: String, keepChoices: Boolean = true) {
        val f = file ?: return
        val text = (if (keepChoices) f else f.withAll(Choice.Undecided)).renderForEditing(oursLabel, theirsLabel)
        openEditor(text ?: return)
    }

    /** 一侧缺失 / 无法分块时：用某一方的文本打开编辑器。读不到（二进制、过大）时报错。 */
    fun openEditorFromSide(side: Side, cannotReadMessage: String) {
        val s = spec ?: return
        viewModelScope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) { runCatching { AppGraph.syncManager.readSideText(s, path, side) } }
            busy = false
            r.onSuccess { text ->
                if (text == null) {
                    error = cannotReadMessage
                } else {
                    openEditor(text.replace("\r\n", "\n"), lineEnding = if (text.contains("\r\n")) "\r\n" else "\n")
                }
            }.onFailure { error = it.message ?: it.toString() }
        }
    }

    private fun openEditor(text: String, lineEnding: String? = null) {
        editorLineEnding = lineEnding
        editorOriginal = text
        editorValue = TextFieldValue(text, TextRange(0))
        searchQuery = ""
        editorOpen = true
    }

    fun closeEditor() {
        editorOpen = false
    }

    /** 从当前光标之后找下一个匹配并选中；找不到从头再找。返回是否找到。 */
    fun findNext(): Boolean {
        val q = searchQuery
        if (q.isEmpty()) return false
        val text = editorValue.text
        val from = editorValue.selection.max
        var idx = text.indexOf(q, from, ignoreCase = true)
        if (idx < 0) idx = text.indexOf(q, 0, ignoreCase = true)
        if (idx < 0) return false
        editorValue = editorValue.copy(selection = TextRange(idx, idx + q.length))
        return true
    }

    /** 编辑器文本里冲突标记所在的行号（从 1 起）。 */
    val editorMarkerLines: List<Int> get() = MergeModel.conflictMarkerLines(editorValue.text)

    /** 选中光标之后的下一条冲突标记行（到末尾后从头找）。 */
    fun selectNextMarker() {
        val text = editorValue.text
        val starts = ArrayList<Pair<Int, Int>>()
        var pos = 0
        for (line in text.split('\n')) {
            if (MergeModel.isMarkerLine(line)) starts += pos to pos + line.length
            pos += line.length + 1
        }
        if (starts.isEmpty()) return
        val from = editorValue.selection.max
        val next = starts.firstOrNull { it.first >= from } ?: starts.first()
        editorValue = editorValue.copy(selection = TextRange(next.first, next.second))
    }

    /** 编辑器内容写回并标记解决。 */
    fun saveEditor() {
        val lineEnding = editorLineEnding ?: file?.lineEnding ?: "\n"
        val text = editorValue.text.replace("\r\n", "\n").replace("\n", lineEnding)
        resolveWith { AppGraph.syncManager.resolveWithText(it, path, text) }
    }

    // ---- 写回 ----

    /** 块视图全部决定后写回。 */
    fun markResolvedFromBlocks() {
        val text = file?.render() ?: return
        resolveWith { AppGraph.syncManager.resolveWithText(it, path, text) }
    }

    fun takeSide(side: Side) = resolveWith { AppGraph.syncManager.resolveTakeSide(it, path, side) }

    fun deleteFile() = resolveWith { AppGraph.syncManager.resolveDelete(it, path) }

    private fun resolveWith(block: (RepoSpec) -> Unit) {
        val s = spec ?: return
        viewModelScope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) { runCatching { block(s) } }
            busy = false
            r.onSuccess { error = null; editorOpen = false; resolved = true }
                .onFailure {
                    AppGraph.logger.error("resolve failed for '${s.name}' $path", it)
                    error = it.message ?: it.toString()
                }
        }
    }
}
