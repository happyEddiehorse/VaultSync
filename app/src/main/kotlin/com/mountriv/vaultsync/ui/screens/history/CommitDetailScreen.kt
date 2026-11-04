package com.mountriv.vaultsync.ui.screens.history

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mountriv.vaultsync.AppGraph
import com.mountriv.vaultsync.R
import com.mountriv.vaultsync.gitcore.repo.RepoHistory
import com.mountriv.vaultsync.gitcore.repo.RepoHistory.ChangeType
import com.mountriv.vaultsync.gitcore.repo.RepoHistory.DiffLine
import com.mountriv.vaultsync.gitcore.repo.RepoHistory.DiffStatus
import com.mountriv.vaultsync.gitcore.repo.RepoHistory.FileChange
import com.mountriv.vaultsync.gitcore.repo.RepoHistory.FileDiff
import com.mountriv.vaultsync.gitcore.repo.RepoHistory.LineType
import com.mountriv.vaultsync.gitcore.repo.WordDiff
import com.mountriv.vaultsync.ui.ErrorCard
import com.mountriv.vaultsync.ui.ViewOptionsMenuItems
import com.mountriv.vaultsync.ui.formatBytes
import com.mountriv.vaultsync.ui.formatFullTime
import com.mountriv.vaultsync.ui.rememberLabelWidth
import com.mountriv.vaultsync.ui.theme.DiffColors
import com.mountriv.vaultsync.ui.theme.diffColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** 一个文件改动的加载状态。 */
sealed interface DiffState {
    data object Loading : DiffState
    data class Loaded(val diff: FileDiff) : DiffState
    data class Failed(val message: String) : DiffState
}

/**
 * 提交详情的数据放在 ViewModel 里（D-031）：文件改动展开时才算，算过的留着；展开了哪些文件，
 * 旋转屏幕、切换深色模式后都还在。自动换行、显示行号存在设置里（D-038），下次进来还是上次的选择。
 */
class CommitDetailViewModel(repoId: String, commitId: String) : ViewModel() {
    val spec = AppGraph.repos.get(repoId)
    var detail by mutableStateOf<RepoHistory.CommitDetail?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private val expanded = mutableStateSetOf<String>()
    private val diffs = mutableStateMapOf<String, DiffState>()
    private val openGaps = mutableStateSetOf<String>()

    init {
        spec?.let { s ->
            viewModelScope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { AppGraph.syncManager.commitDetail(s, commitId) } }
                r.onSuccess { detail = it }.onFailure { error = it.message ?: it.toString() }
            }
        }
    }

    fun isExpanded(change: FileChange): Boolean = change.key in expanded

    fun diffOf(change: FileChange): DiffState? = diffs[change.key]

    /** 折叠起来的没改的行（[gap] 是 [gapKey] 给出的标识）是否已展开。 */
    fun isGapOpen(gap: String): Boolean = gap in openGaps

    fun toggleGap(gap: String) {
        if (!openGaps.add(gap)) openGaps.remove(gap)
    }

    /** 展开或收起一个文件；第一次展开（或上次读取失败后再展开）时在 IO 线程计算改动。 */
    fun toggle(change: FileChange) {
        val key = change.key
        if (!expanded.add(key)) {
            expanded.remove(key)
            return
        }
        val s = spec ?: return
        if (diffs[key] is DiffState.Loading || diffs[key] is DiffState.Loaded) return
        diffs[key] = DiffState.Loading
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { AppGraph.syncManager.fileDiff(s, change) } }
            diffs[key] = r.fold({ DiffState.Loaded(it) }, { DiffState.Failed(it.message ?: it.toString()) })
        }
    }
}

/** 列表里标识一个改动的文件；同一路径先删后增（例如文件换成了符号链接）时靠类型区分。 */
private val FileChange.key: String get() = "${type.name}:$path"

/**
 * 不换行时，所有改动行共用一个横向偏移：拖动任意一行，整块一起移动，成对的删除 / 新增行始终上下对齐。
 * 能滚多远取目前排过版的最宽一行，只增不减。
 */
private class SharedHorizontalScroll {
    var offset by mutableFloatStateOf(0f)
        private set
    private var maxOverflow = 0

    /** 某一行比可见宽度宽出 [overflow] 像素（排版时报告）。 */
    fun report(overflow: Int) {
        if (overflow > maxOverflow) maxOverflow = overflow
    }

    fun scrollBy(delta: Float) {
        offset = (offset - delta).coerceIn(0f, maxOverflow.toFloat())
    }
}

/** 画改动行要用的：配色、是否自动换行、是否显示行号、不换行时共用的横向滚动、等宽字体一个数字的宽度（行号栏按位数算宽度）。 */
private class DiffLook(
    val colors: DiffColors,
    val wrap: Boolean,
    val lineNumbers: Boolean,
    val scroll: SharedHorizontalScroll,
    val drag: DraggableState,
    val noteIndent: Dp,
    val digitWidth: Dp,
)

/** 一次提交的详情（docs/UI.md §4.2）：完整信息与改动的文件（相对第一父提交）；点文件展开它的改动（D-031）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommitDetailScreen(repoId: String, commitId: String, onBack: () -> Unit) {
    val vm: CommitDetailViewModel = viewModel(key = "commit:$repoId:$commitId") { CommitDetailViewModel(repoId, commitId) }
    val d = vm.detail
    var menuOpen by remember { mutableStateOf(false) }
    val settings by AppGraph.settings.state.collectAsStateWithLifecycle()
    val view = settings.diffView
    // 切换换行或行号后从最左边重新开始（行号栏的宽度变了，能滚多远也跟着变）
    val scroll = remember(view.wrapLines, view.lineNumbers) { SharedHorizontalScroll() }
    val drag = rememberDraggableState { delta -> scroll.scrollBy(delta) }
    val colors = diffColors()
    val labelWidth = rememberLabelWidth(ChangeType.entries.map { stringResource(changeLabel(it)) }, MaterialTheme.typography.labelMedium) + 12.dp
    val digitWidth = rememberLabelWidth(listOf("0"), codeStyle())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.commit_title, commitId.take(7))) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                actions = {
                    if (d != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                ViewOptionsMenuItems(view, onDismiss = { menuOpen = false }) { v ->
                                    AppGraph.settings.update { it.copy(diffView = v) }
                                }
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
            vm.error?.let { e -> item(key = "error") { ErrorCard(stringResource(R.string.history_load_failed), e, Modifier.padding(16.dp)) } }
            if (d == null && vm.error == null) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
            if (d != null) {
                item(key = "header") { CommitHeader(d) }
                item(key = "changes") {
                    Text(
                        pluralStringResource(if (d.comparedToFirstParent) R.plurals.commit_changes_merge else R.plurals.commit_changes, d.totalChanges, d.totalChanges),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                    )
                    HorizontalDivider()
                }
                val look = DiffLook(colors, view.wrapLines, view.lineNumbers, scroll, drag, noteIndent = FILE_ROW_START + labelWidth, digitWidth = digitWidth)
                for (ch in d.changes) {
                    val key = ch.key
                    val expanded = vm.isExpanded(ch)
                    item(key = "file:$key", contentType = "file") { FileRow(ch, expanded, labelWidth, onClick = { vm.toggle(ch) }) }
                    if (expanded) diffItems(key, ch, vm.diffOf(ch), look, vm::isGapOpen, vm::toggleGap)
                    item(key = "divider:$key", contentType = "divider") { HorizontalDivider() }
                }
                val more = d.totalChanges - d.changes.size
                if (more > 0) {
                    item(key = "more") {
                        Text(
                            pluralStringResource(R.plurals.commit_more_changes, more, more),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitHeader(d: RepoHistory.CommitDetail) {
    SelectionContainer(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(d.commit.message.trimEnd(), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.commit_author, d.commit.authorName, d.commit.authorEmail, formatFullTime(LocalContext.current, d.commit.time)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(d.commit.id, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(if (d.commit.pushed) R.string.commit_pushed else R.string.commit_not_pushed),
                style = MaterialTheme.typography.bodySmall,
                color = if (d.commit.pushed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

/** 一个改动的文件；点一下展开 / 收起它的改动。 */
@Composable
private fun FileRow(ch: FileChange, expanded: Boolean, labelWidth: Dp, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = FILE_ROW_START, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(changeLabel(ch.type)),
            style = MaterialTheme.typography.labelMedium,
            color = changeColor(ch.type),
            maxLines = 1,
            modifier = Modifier.width(labelWidth),
        )
        Column(Modifier.weight(1f)) {
            Text(ch.path, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            ch.oldPath?.let {
                Text(
                    stringResource(if (ch.type == ChangeType.COPIED) R.string.commit_copied_from else R.string.commit_renamed_from, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(if (expanded) R.string.diff_collapse else R.string.diff_expand),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 展开的文件下面的内容：与文件行平铺在同一个列表里（不嵌套滚动），改动多的提交也能顺畅滚动。 */
private fun LazyListScope.diffItems(
    key: String,
    ch: FileChange,
    state: DiffState?,
    look: DiffLook,
    isGapOpen: (String) -> Boolean,
    toggleGap: (String) -> Unit,
) {
    when (state) {
        null, DiffState.Loading -> item(key = "loading:$key") {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
        }
        is DiffState.Failed -> item(key = "failed:$key") { DiffNote(stringResource(R.string.diff_load_failed, state.message), look.noteIndent, error = true) }
        is DiffState.Loaded -> {
            val diff = state.diff
            when (diff.status) {
                DiffStatus.BINARY -> item(key = "note:$key") { DiffNote(stringResource(R.string.diff_binary, sizes(LocalContext.current, ch, diff)), look.noteIndent) }
                DiffStatus.TOO_LARGE -> item(key = "note:$key") { DiffNote(stringResource(R.string.diff_too_large, sizes(LocalContext.current, ch, diff)), look.noteIndent) }
                DiffStatus.UNCHANGED -> item(key = "note:$key") {
                    DiffNote(stringResource(if (diff.newlineAtEndChanged) R.string.diff_unchanged_newline else R.string.diff_unchanged), look.noteIndent)
                }
                DiffStatus.TEXT -> {
                    val numbers = diff.hunks.map(::lineNumbers)
                    // 最后一段之后没改的行接着新文件的行号往下编
                    val tailStart = diff.hunks.lastOrNull()?.let { it.newStart + it.lines.count { l -> l.type != LineType.DELETED } } ?: 1
                    val maxNumber = maxOf(tailStart - 1 + diff.after.size, numbers.maxOfOrNull { it.maxOrNull() ?: 0 } ?: 0)
                    // 不显示行号时行号栏整个去掉（D-038）
                    val gutter = if (look.lineNumbers) look.digitWidth * maxNumber.toString().length else 0.dp
                    diff.hunks.forEachIndexed { h, hunk ->
                        val gap = gapKey(key, h)
                        gapItems(gap, hunk.before, hunk.newStart - hunk.before.size, gutter, isGapOpen(gap), look, toggleGap)
                        val n = numbers[h]
                        items(hunk.lines.size, key = { "line:$key:$h:$it" }, contentType = { "line" }) { i -> DiffLineRow(hunk.lines[i], n[i], gutter, look) }
                    }
                    val tail = gapKey(key, diff.hunks.size)
                    gapItems(tail, diff.after, tailStart, gutter, isGapOpen(tail), look, toggleGap)
                    if (diff.truncated) item(key = "truncated:$key") { DiffNote(stringResource(R.string.diff_truncated, RepoHistory.MAX_DIFF_LINES), look.noteIndent) }
                    if (diff.newlineAtEndChanged) item(key = "newline:$key") { DiffNote(stringResource(R.string.diff_newline_at_end), look.noteIndent) }
                }
            }
        }
    }
    item(key = "end:$key") { Spacer(Modifier.height(8.dp)) }
}

/** 两侧大小（"12.0 KB → 13.5 KB"）；新增、删除的文件只有一侧。 */
private fun sizes(context: Context, ch: FileChange, diff: FileDiff): String = when {
    ch.oldBlobId == null -> formatBytes(context, diff.newSize)
    ch.newBlobId == null -> formatBytes(context, diff.oldSize)
    else -> "${formatBytes(context, diff.oldSize)} → ${formatBytes(context, diff.newSize)}"
}

/** 改动内容之外的说明（二进制、太大、没有变化、读取失败等），与文件路径左对齐。 */
@Composable
private fun DiffNote(text: String, indent: Dp, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = indent, end = 16.dp, top = 4.dp, bottom = 4.dp),
    )
}

/** 第 [index] 段之前（[index] 等于段数时是最后一段之后）那些没改的行的标识。 */
private fun gapKey(fileKey: String, index: Int) = "$fileKey#$index"

/**
 * 一段里每行的行号（docs/UI.md §4.2，一栏）：删除行标旧文件的行号，没改的行和新增行标新文件的行号。
 * 删除的文件整篇都是删除行，标的就是旧文件的行号。
 */
private fun lineNumbers(hunk: RepoHistory.Hunk): IntArray {
    var old = hunk.oldStart
    var new = hunk.newStart
    return IntArray(hunk.lines.size) { i ->
        when (hunk.lines[i].type) {
            LineType.DELETED -> old++
            LineType.ADDED -> new++
            LineType.CONTEXT -> new.also { old++; new++ }
        }
    }
}

/**
 * 一处没改的行：折叠时只有一行"⋯ N 行未改动，已折叠 ⋯"，点开后这一行换成"收起未改动的行"、下面接着显示这些行
 * （收起的入口留在原处，点开、收起都不用找）。[first] 是这些行里第一行的行号（两侧相同）。
 */
private fun LazyListScope.gapItems(
    gap: String,
    lines: List<String>,
    first: Int,
    gutter: Dp,
    open: Boolean,
    look: DiffLook,
    toggle: (String) -> Unit,
) {
    if (lines.isEmpty()) return
    item(key = "fold:$gap", contentType = "fold") { FoldRow(lines.size, open) { toggle(gap) } }
    if (open) {
        items(lines.size, key = { "gapline:$gap:$it" }, contentType = { "line" }) { i ->
            DiffLineRow(DiffLine(LineType.CONTEXT, lines[i]), first + i, gutter, look)
        }
    }
}

@Composable
private fun FoldRow(count: Int, open: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            if (open) stringResource(R.string.diff_fold) else pluralStringResource(R.plurals.diff_folded, count, count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 一行改动：左边行号（宽 [gutter]，右对齐；不显示行号时连同间隔一起去掉），删除红底、新增绿底，行首保留 `-` / `+`；
 * 字词级对比找出的改动在底色上再深一档。不换行时只有正文左右移动，行号和 `-` / `+` 不动。
 */
@Composable
private fun DiffLineRow(line: DiffLine, number: Int, gutter: Dp, look: DiffLook) {
    val colors = look.colors
    val (prefix, lineColor, wordColor) = when (line.type) {
        LineType.CONTEXT -> Triple(" ", Color.Unspecified, Color.Unspecified)
        LineType.DELETED -> Triple("-", colors.deletedLine, colors.deletedWord)
        LineType.ADDED -> Triple("+", colors.addedLine, colors.addedWord)
    }
    val text = remember(line, wordColor) { highlight(line, wordColor) }
    val style = codeStyle()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (lineColor.isSpecified) Modifier.background(lineColor) else Modifier)
            .then(if (look.wrap) Modifier else Modifier.draggable(look.drag, Orientation.Horizontal))
            .padding(horizontal = LINE_PADDING),
    ) {
        if (look.lineNumbers) {
            Text(
                number.toString(),
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(gutter),
            )
            Spacer(Modifier.width(GUTTER_GAP))
        }
        Text(prefix, style = style, modifier = Modifier.width(PREFIX_WIDTH))
        if (look.wrap) {
            Text(text, style = style, modifier = Modifier.weight(1f))
        } else {
            val scroll = look.scroll
            Text(
                text,
                style = style,
                softWrap = false,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f)
                    .clipToBounds()
                    .layout { measurable, constraints ->
                        // 按整行的实际宽度排版，再按共用的偏移左移；超出可见宽度的部分被裁掉
                        val p = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
                        scroll.report(p.width - constraints.maxWidth)
                        layout(constraints.maxWidth, p.height) { p.place(-scroll.offset.roundToInt(), 0) }
                    },
            )
        }
    }
}

/** 行文本，[DiffLine.changed] 的区间加上 [wordColor] 底色。 */
private fun highlight(line: DiffLine, wordColor: Color): AnnotatedString {
    val text = when {
        // 空行也要占一行高
        line.text.isEmpty() -> " "
        line.text.length > MAX_LINE_DISPLAY_CHARS -> {
            // 不把一个表情之类的代理对从中间切开
            val end = if (Character.isHighSurrogate(line.text[MAX_LINE_DISPLAY_CHARS - 1])) MAX_LINE_DISPLAY_CHARS - 1 else MAX_LINE_DISPLAY_CHARS
            line.text.substring(0, end) + "…"
        }
        else -> line.text
    }
    if (line.changed.isEmpty() || !wordColor.isSpecified) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (r in line.changed) {
            val start = r.first.coerceIn(0, text.length)
            val end = (r.last + 1).coerceIn(start, text.length)
            if (end > start) addStyle(SpanStyle(background = wordColor), start, end)
        }
    }
}

/**
 * 一行最多显示的字符数。压缩过的插件脚本（`.obsidian/plugins/…/main.js`）常常整个文件只有一行、几十万字，
 * 排版会让界面卡住好几秒；超出的部分以"…"代替。与字词对比的上限相同，截掉的部分本来也不做字词对比。
 */
private const val MAX_LINE_DISPLAY_CHARS = WordDiff.MAX_LINE_CHARS

@Composable
private fun codeStyle(): TextStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

private val FILE_ROW_START = 16.dp
private val LINE_PADDING = 8.dp
private val PREFIX_WIDTH = 16.dp
private val GUTTER_GAP = 6.dp

private fun changeLabel(t: ChangeType): Int = when (t) {
    ChangeType.ADDED -> R.string.change_added
    ChangeType.MODIFIED -> R.string.change_modified
    ChangeType.DELETED -> R.string.change_deleted
    ChangeType.RENAMED -> R.string.change_renamed
    ChangeType.COPIED -> R.string.change_copied
}

@Composable
private fun changeColor(t: ChangeType) = when (t) {
    ChangeType.ADDED -> MaterialTheme.colorScheme.primary
    ChangeType.DELETED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.tertiary
}
