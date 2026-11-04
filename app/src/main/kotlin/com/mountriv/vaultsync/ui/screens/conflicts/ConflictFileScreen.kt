package com.mountriv.vaultsync.ui.screens.conflicts

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mountriv.vaultsync.AppGraph
import com.mountriv.vaultsync.R
import com.mountriv.vaultsync.data.ViewOptions
import com.mountriv.vaultsync.gitcore.merge.Block
import com.mountriv.vaultsync.gitcore.merge.Choice
import com.mountriv.vaultsync.gitcore.merge.ConflictFile
import com.mountriv.vaultsync.gitcore.merge.ConflictKind
import com.mountriv.vaultsync.gitcore.merge.MergeModel
import com.mountriv.vaultsync.gitcore.merge.Side
import com.mountriv.vaultsync.ui.ConfirmDialog
import com.mountriv.vaultsync.ui.ErrorCard
import com.mountriv.vaultsync.ui.InfoCard
import com.mountriv.vaultsync.ui.ViewOptionsMenuItems
import com.mountriv.vaultsync.ui.formatBytes
import com.mountriv.vaultsync.ui.rememberLabelWidth
import kotlinx.coroutines.launch

/**
 * 冲突文件页（docs/UI.md §6、D-025）：
 * - 能分块的文本文件：整篇按原顺序连续显示，冲突处嵌在正文中间（左侧色条标出范围，本地 / 远端两种浅底色），
 *   每个版本旁边有"保留"按钮，下面是"两者都保留"与"手动编辑"；选定后冲突处直接显示结果。长段无冲突内容折叠。
 * - 不能分块（二进制 / 一侧删除 / 过大）：整文件级选择。
 * - 整文件编辑器：等宽、可查找、可跳到残留的冲突标记；保存即标记解决。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictFileScreen(repoId: String, path: String, onBack: () -> Unit) {
    val vm: ConflictFileViewModel = viewModel(key = "conflict:$repoId:$path") { ConflictFileViewModel(repoId, path) }

    // 写回成功 → 回到列表
    LaunchedEffect(vm.resolved) { if (vm.resolved) onBack() }

    if (vm.editorOpen) {
        EditorScreen(vm, onClose = vm::closeEditor)
        return
    }
    val file = vm.file
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var askEditorMode by remember { mutableStateOf(false) }
    val oursLabel = stringResource(R.string.conflict_marker_ours)
    val theirsLabel = stringResource(R.string.conflict_marker_theirs)
    // 块视图的选择只在内存里：有选择时离开要确认；已有选择时打开整篇编辑要问带不带上
    fun requestLeave() { if (vm.hasChoices && !vm.busy) confirmLeave = true else onBack() }
    fun requestEditor() { if (vm.hasChoices) askEditorMode = true else vm.openEditorFromBlocks(oursLabel, theirsLabel) }
    BackHandler(enabled = vm.editingBlock != null) { vm.cancelBlockEdit() }
    BackHandler(enabled = vm.editingBlock == null && vm.hasChoices) { requestLeave() }
    val cannotRead = stringResource(R.string.conflict_cannot_edit_side)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val settings by AppGraph.settings.state.collectAsStateWithLifecycle()
    val view = settings.conflictView

    Scaffold(
        // 键盘弹出时整页（连同底部栏）缩到键盘上方，正在输入的框保持可见
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (path.contains('/')) {
                            Text(
                                path.substringBeforeLast('/'),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { requestLeave() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                actions = {
                    if (file?.blocks != null) {
                        Box {
                            IconButton(onClick = { menuOpen = true }, enabled = !vm.busy) {
                                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.conflict_all_blocks_ours)) },
                                    onClick = { menuOpen = false; vm.chooseAll(Choice.Ours) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.conflict_all_blocks_theirs)) },
                                    onClick = { menuOpen = false; vm.chooseAll(Choice.Theirs) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.conflict_reset_choices)) },
                                    onClick = { menuOpen = false; vm.resetChoices() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.conflict_open_editor)) },
                                    onClick = { menuOpen = false; requestEditor() },
                                )
                                HorizontalDivider()
                                ViewOptionsMenuItems(view, onDismiss = { menuOpen = false }) { v ->
                                    AppGraph.settings.update { it.copy(conflictView = v) }
                                }
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (file?.blocks != null) {
                BlocksBottomBar(
                    file = file,
                    busy = vm.busy,
                    onLocate = {
                        vm.firstUndecidedBlockIndex?.let { idx -> scope.launch { listState.animateScrollToItem(idx + 1) } }
                    },
                    onOpenEditor = { requestEditor() },
                    onMarkResolved = vm::markResolvedFromBlocks,
                )
            }
        },
    ) { padding ->
        when {
            vm.missing -> Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InfoCard(stringResource(R.string.conflict_missing_title)) {
                    Text(stringResource(R.string.conflict_missing_body), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onBack, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.action_back)) }
                }
            }
            vm.loading || file == null -> Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                vm.error?.let { ErrorCard(stringResource(R.string.conflicts_load_failed), it) }
                    ?: Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.conflicts_loading))
                    }
            }
            file.blocks == null -> WholeFileView(
                vm = vm,
                file = file,
                padding = padding,
                onDelete = { confirmDelete = true },
                onEditSide = { side -> vm.openEditorFromSide(side, cannotRead) },
            )
            else -> BlocksView(vm, file, view, padding, listState)
        }
    }

    if (confirmLeave) {
        ConfirmDialog(
            title = stringResource(R.string.conflict_leave_title),
            text = stringResource(R.string.conflict_leave_body),
            confirmText = stringResource(R.string.conflict_leave_discard),
            destructive = true,
            onConfirm = onBack,
            onDismiss = { confirmLeave = false },
        )
    }
    if (askEditorMode) {
        AlertDialog(
            onDismissRequest = { askEditorMode = false },
            title = { Text(stringResource(R.string.conflict_editor_mode_title)) },
            text = { Text(stringResource(R.string.conflict_editor_mode_body)) },
            confirmButton = {
                TextButton(onClick = { askEditorMode = false; vm.openEditorFromBlocks(oursLabel, theirsLabel, keepChoices = true) }) {
                    Text(stringResource(R.string.conflict_editor_mode_keep))
                }
            },
            dismissButton = {
                TextButton(onClick = { askEditorMode = false; vm.openEditorFromBlocks(oursLabel, theirsLabel, keepChoices = false) }) {
                    Text(stringResource(R.string.conflict_editor_mode_original))
                }
            },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.conflict_delete_title),
            text = stringResource(R.string.conflict_delete_confirm, path),
            confirmText = stringResource(R.string.conflict_delete_file),
            destructive = true,
            onConfirm = vm::deleteFile,
            onDismiss = { confirmDelete = false },
        )
    }
}

// ---- 整文件级选择（二进制 / 一侧删除 / 过大） ------------------------------------------------

@Composable
private fun WholeFileView(
    vm: ConflictFileViewModel,
    file: ConflictFile,
    padding: PaddingValues,
    onDelete: () -> Unit,
    onEditSide: (Side) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        vm.error?.let { ErrorCard(stringResource(R.string.conflicts_action_failed), it) }
        InfoCard(stringResource(kindText(file.kind, file.isBinary))) {
            Text(
                stringResource(
                    when {
                        file.isBinary -> R.string.conflict_whole_binary_body
                        file.kind == ConflictKind.DELETED_BY_THEM -> R.string.conflict_whole_deleted_by_them_body
                        file.kind == ConflictKind.DELETED_BY_US -> R.string.conflict_whole_deleted_by_us_body
                        file.kind == ConflictKind.BOTH_DELETED -> R.string.conflict_whole_both_deleted_body
                        !file.hasOurs || !file.hasTheirs -> R.string.conflict_whole_one_side_body
                        else -> R.string.conflict_whole_too_large_body
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (file.hasOurs) {
                Text(
                    stringResource(R.string.conflict_side_info, stringResource(R.string.conflict_ours), formatBytes(LocalContext.current, file.oursSize), file.oursId ?: "-"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (file.hasTheirs) {
                Text(
                    stringResource(R.string.conflict_side_info, stringResource(R.string.conflict_theirs), formatBytes(LocalContext.current, file.theirsSize), file.theirsId ?: "-"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        val enabled = !vm.busy
        if (file.hasOurs) {
            Button(onClick = { vm.takeSide(Side.OURS) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (file.hasTheirs) R.string.conflict_use_ours_file else R.string.conflict_keep_ours_file))
            }
        }
        if (file.hasTheirs) {
            Button(onClick = { vm.takeSide(Side.THEIRS) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (file.hasOurs) R.string.conflict_use_theirs_file else R.string.conflict_keep_theirs_file))
            }
        }
        if (!file.isBinary) {
            if (file.hasOurs) {
                OutlinedButton(onClick = { onEditSide(Side.OURS) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.conflict_edit_ours_then_keep))
                }
            }
            if (file.hasTheirs) {
                OutlinedButton(onClick = { onEditSide(Side.THEIRS) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.conflict_edit_theirs_then_keep))
                }
            }
        }
        OutlinedButton(onClick = onDelete, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.conflict_delete_file), color = MaterialTheme.colorScheme.error)
        }
    }
}

// ---- 块视图：整篇连续显示，冲突处内嵌 ----------------------------------------------------------

/** 正文的统一字体：与整文件编辑器一致的等宽小字，保证空格与缩进如实显示。 */
@Composable
private fun codeStyle(): TextStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

/** 冲突区域的配色：本地用主色系，远端用第三色系；未决定时色条用错误色提醒。 */
private data class ConflictColors(
    val oursTint: Color,
    val oursStrong: Color,
    val oursLabel: Color,
    val theirsTint: Color,
    val theirsStrong: Color,
    val theirsLabel: Color,
    val undecidedBar: Color,
    val decidedBar: Color,
    val decidedTint: Color,
)

@Composable
private fun conflictColors(): ConflictColors {
    val cs = MaterialTheme.colorScheme
    return ConflictColors(
        oursTint = cs.primaryContainer.copy(alpha = 0.28f),
        oursStrong = cs.primaryContainer.copy(alpha = 0.75f),
        oursLabel = cs.primary,
        theirsTint = cs.tertiaryContainer.copy(alpha = 0.35f),
        theirsStrong = cs.tertiaryContainer.copy(alpha = 0.9f),
        theirsLabel = cs.tertiary,
        undecidedBar = cs.error,
        decidedBar = cs.primary.copy(alpha = 0.5f),
        decidedTint = cs.surfaceVariant.copy(alpha = 0.45f),
    )
}

@Composable
private fun BlocksView(vm: ConflictFileViewModel, file: ConflictFile, view: ViewOptions, padding: PaddingValues, listState: LazyListState) {
    val blocks = file.blocks ?: return
    var conflictCounter = -1
    val conflictIndexOf = IntArray(blocks.size) { -1 }
    blocks.forEachIndexed { i, b -> if (b is Block.Conflict) conflictIndexOf[i] = ++conflictCounter }
    val colors = conflictColors()
    // 每块第一行在本地 / 远端文件里的行号（D-036）：只取决于两个原文件，选择与编辑都不改变它
    val starts = remember(blocks) {
        var ours = 1
        var theirs = 1
        blocks.map { b ->
            val s = LineNo(ours, theirs)
            when (b) {
                is Block.Common -> { ours += b.lines.size; theirs += b.lines.size }
                is Block.Conflict -> { ours += b.ours.size; theirs += b.theirs.size }
            }
            s
        } to maxOf(ours, theirs) - 1
    }
    val digitWidth = rememberLabelWidth(listOf("0"), codeStyle())
    val gutter = Gutter(view.lineNumbers, digitWidth * starts.second.toString().length, colors.oursLabel, colors.theirsLabel)

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
    ) {
        item(key = "header") {
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.error?.let { ErrorCard(stringResource(R.string.conflicts_action_failed), it) }
                // 关掉行号时，说明行号的那句一起去掉（D-038）
                val intro = pluralStringResource(R.plurals.conflict_blocks_intro, file.conflictCount, file.conflictCount)
                Text(
                    if (view.lineNumbers) intro + stringResource(R.string.conflict_line_numbers_intro) else intro,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        blocks.forEachIndexed { i, block ->
            when (block) {
                is Block.Common -> item(key = "c$i") {
                    CommonBlock(
                        block,
                        start = starts.first[i],
                        gutter = gutter,
                        isFirst = i == 0,
                        isLast = i == blocks.lastIndex,
                        expanded = i in vm.expandedCommon,
                        wrap = view.wrapLines,
                        onToggle = { vm.toggleCommon(i) },
                    )
                }
                is Block.Conflict -> item(key = "x$i") {
                    val ci = conflictIndexOf[i]
                    ConflictBlock(
                        block = block,
                        start = starts.first[i],
                        gutter = gutter,
                        number = ci + 1,
                        total = file.conflictCount,
                        editing = vm.editingBlock == ci,
                        editText = vm.blockEditText,
                        wrap = view.wrapLines,
                        busy = vm.busy,
                        colors = colors,
                        onChoose = { vm.choose(ci, it) },
                        onStartEdit = { vm.startBlockEdit(ci) },
                        onEditTextChange = { vm.blockEditText = it },
                        onSaveEdit = vm::saveBlockEdit,
                        onCancelEdit = vm::cancelBlockEdit,
                    )
                }
            }
        }
    }
}

/**
 * 无冲突的正文，直接接在上下文里显示。很长时折叠中间部分：紧挨冲突的几行保留（开头的块只留结尾、结尾的块只留开头），
 * 中间换成一行"⋯ N 行无冲突，已折叠 ⋯"，点击展开。
 */
@Composable
private fun CommonBlock(
    block: Block.Common,
    start: LineNo,
    gutter: Gutter,
    isFirst: Boolean,
    isLast: Boolean,
    expanded: Boolean,
    wrap: Boolean,
    onToggle: () -> Unit,
) {
    val lines = block.lines
    val keepHead = if (isFirst) 0 else CONTEXT_LINES
    val keepTail = if (isLast) 0 else CONTEXT_LINES
    val collapsible = lines.size > keepHead + keepTail + MIN_FOLDED_LINES
    // 无冲突的行在两个文件里都有，两栏都标号
    val numbers = { from: Int -> { k: Int -> LineNo(start.ours + from + k, start.theirs + from + k) } }
    Column(Modifier.fillMaxWidth()) {
        if (!collapsible || expanded) {
            CodeLines(lines, numbers(0), gutter, Color.Unspecified, wrap = wrap)
            if (collapsible) GutterRow(gutter, Color.Unspecified) { FoldRow(stringResource(R.string.conflict_common_collapse), onToggle) }
        } else {
            CodeLines(lines.take(keepHead), numbers(0), gutter, Color.Unspecified, wrap = wrap)
            val folded = lines.size - keepHead - keepTail
            GutterRow(gutter, Color.Unspecified) { FoldRow(pluralStringResource(R.plurals.conflict_common_collapsed, folded, folded), onToggle) }
            CodeLines(lines.takeLast(keepTail), numbers(lines.size - keepTail), gutter, Color.Unspecified, wrap = wrap)
        }
    }
}

@Composable
private fun FoldRow(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConflictBlock(
    block: Block.Conflict,
    start: LineNo,
    gutter: Gutter,
    number: Int,
    total: Int,
    editing: Boolean,
    editText: String,
    wrap: Boolean,
    busy: Boolean,
    colors: ConflictColors,
    onChoose: (Choice) -> Unit,
    onStartEdit: () -> Unit,
    onEditTextChange: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
) {
    // 左侧色条贯穿整个冲突区域，把它和上下文连成一篇、同时标出范围；每行各画一段，上下连成一条
    val bar = if (block.isDecided) colors.decidedBar else colors.undecidedBar
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        // 手动编辑过的结果可以"重新编辑"，所以编辑器优先于已决定的显示
        if (editing) {
            GutterRow(gutter, bar) { BlockEditor(number, total, editText, onEditTextChange, onSaveEdit, onCancelEdit) }
        } else if (block.isDecided) {
            DecidedContent(block, start, gutter, bar, wrap, busy, colors, onClear = { onChoose(Choice.Undecided) }, onEdit = onStartEdit)
        } else {
            UndecidedContent(block, start, gutter, bar, number, total, wrap, busy, colors, onChoose, onStartEdit)
        }
    }
}

/** 已决定的结果里第 [k] 行来自哪一方、在那个文件里是第几行；手动编辑的结果两栏都空（D-036）。 */
private fun resolvedLineNo(block: Block.Conflict, start: LineNo, k: Int): LineNo = when (block.choice) {
    Choice.Ours -> LineNo(start.ours + k, 0)
    Choice.Theirs -> LineNo(0, start.theirs + k)
    Choice.BothOursFirst ->
        if (k < block.ours.size) LineNo(start.ours + k, 0) else LineNo(0, start.theirs + k - block.ours.size)
    Choice.BothTheirsFirst ->
        if (k < block.theirs.size) LineNo(0, start.theirs + k) else LineNo(start.ours + k - block.theirs.size, 0)
    else -> LineNo.NONE
}

/**
 * 已决定：冲突处直接显示结果，下面一行写选了什么（只是文字），右边"清除选择"回到未决定、重新选；
 * 手动编辑的结果多一个"重新编辑"，在原来的编辑结果上接着改（用户 2026-10-31 定）。
 */
@Composable
private fun DecidedContent(
    block: Block.Conflict,
    start: LineNo,
    gutter: Gutter,
    bar: Color,
    wrap: Boolean,
    busy: Boolean,
    colors: ConflictColors,
    onClear: () -> Unit,
    onEdit: () -> Unit,
) {
    val lines = block.resolvedLines().orEmpty()
    Column(Modifier.fillMaxWidth()) {
        if (lines.isEmpty()) {
            GutterRow(gutter, bar, colors.decidedTint) {
                Text(
                    stringResource(R.string.conflict_empty_result),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        } else {
            CodeLines(lines.take(MAX_SIDE_LINES), { resolvedLineNo(block, start, it) }, gutter, bar, wrap = wrap, tint = colors.decidedTint)
            if (lines.size > MAX_SIDE_LINES) GutterRow(gutter, bar, colors.decidedTint) { TruncatedNote(lines.size - MAX_SIDE_LINES) }
        }
        GutterRow(gutter, bar, colors.decidedTint) { DecidedStatus(block, busy, onClear, onEdit) }
    }
}

/** 文字占剩下的宽度、太长就折行；按钮按自己的宽度排，不会被挤成竖排。 */
@Composable
private fun DecidedStatus(block: Block.Conflict, busy: Boolean, onClear: () -> Unit, onEdit: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            choiceText(block.choice),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
        )
        if (block.choice is Choice.Custom) {
            TextButton(onClick = onEdit, enabled = !busy) { Text(stringResource(R.string.conflict_edit_again), maxLines = 1) }
        }
        TextButton(onClick = onClear, enabled = !busy) { Text(stringResource(R.string.conflict_clear_choice), color = MaterialTheme.colorScheme.error, maxLines = 1) }
    }
}

@Composable
private fun UndecidedContent(
    block: Block.Conflict,
    start: LineNo,
    gutter: Gutter,
    bar: Color,
    number: Int,
    total: Int,
    wrap: Boolean,
    busy: Boolean,
    colors: ConflictColors,
    onChoose: (Choice) -> Unit,
    onStartEdit: () -> Unit,
) {
    val diff = remember(block.ours, block.theirs) { MergeModel.changedLines(block.ours, block.theirs) }
    // 每一行都是 GutterRow，色条才能上下连成一条；行与行之间的间距用内边距，不用 spacedBy（会在色条上留缺口）
    Column(Modifier.fillMaxWidth()) {
        GutterRow(gutter, bar) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(
                    stringResource(R.string.conflict_block_title, number, total),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        SideSection(
            gutter = gutter,
            bar = bar,
            label = stringResource(R.string.conflict_ours),
            lineCount = block.ours.size,
            action = stringResource(R.string.conflict_keep_ours),
            selected = block.choice == Choice.Ours,
            labelColor = colors.oursLabel,
            busy = busy,
            onChoose = { onChoose(Choice.Ours) },
        )
        // 本地版本的行只在本地文件里有，只标左栏
        SideLines(block.ours, { LineNo(start.ours + it, 0) }, gutter, bar, diff.oursChanged, colors.oursTint, colors.oursStrong, wrap)
        SideSection(
            gutter = gutter,
            bar = bar,
            label = stringResource(R.string.conflict_theirs),
            lineCount = block.theirs.size,
            action = stringResource(R.string.conflict_keep_theirs),
            selected = block.choice == Choice.Theirs,
            labelColor = colors.theirsLabel,
            busy = busy,
            onChoose = { onChoose(Choice.Theirs) },
        )
        SideLines(block.theirs, { LineNo(0, start.theirs + it) }, gutter, bar, diff.theirsChanged, colors.theirsTint, colors.theirsStrong, wrap)
        GutterRow(gutter, bar) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 6.dp),
            ) {
                BothButton(block.choice, busy, onChoose, Modifier.weight(1f))
                ChoiceButton(stringResource(R.string.conflict_edit_block), block.choice is Choice.Custom, busy, Modifier.weight(1f), onStartEdit)
            }
        }
    }
}

/** 一个版本的标题行：名称 · 行数 + 右侧"保留"按钮。 */
@Composable
private fun SideSection(
    gutter: Gutter,
    bar: Color,
    label: String,
    lineCount: Int,
    action: String,
    selected: Boolean,
    labelColor: Color,
    busy: Boolean,
    onChoose: () -> Unit,
) {
    GutterRow(gutter, bar) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text(
                label + " · " + pluralStringResource(R.plurals.conflict_line_count, lineCount, lineCount),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = labelColor,
                modifier = Modifier.weight(1f),
            )
            ChoiceButton(action, selected, busy, Modifier, onChoose)
        }
    }
}

@Composable
private fun SideLines(
    lines: List<String>,
    numbers: (Int) -> LineNo,
    gutter: Gutter,
    bar: Color,
    changed: Set<Int>,
    tint: Color,
    strong: Color,
    wrap: Boolean,
) {
    if (lines.isEmpty()) {
        GutterRow(gutter, bar, tint) {
            Text(
                stringResource(R.string.conflict_side_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        return
    }
    CodeLines(lines.take(MAX_SIDE_LINES), numbers, gutter, bar, wrap = wrap, tint = tint, highlight = changed, highlightColor = strong)
    if (lines.size > MAX_SIDE_LINES) GutterRow(gutter, bar, tint) { TruncatedNote(lines.size - MAX_SIDE_LINES) }
}

@Composable
private fun TruncatedNote(hidden: Int) {
    Text(
        pluralStringResource(R.plurals.conflict_side_truncated, hidden, hidden),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** "两者都保留 ▾"：展开后选本地在前或远端在前；选了之后冲突处收起，下面一行写"已保留两者（本地在前）"。 */
@Composable
private fun BothButton(choice: Choice, busy: Boolean, onChoose: (Choice) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    val selected = choice == Choice.BothOursFirst || choice == Choice.BothTheirsFirst
    Box(modifier) {
        val content: @Composable () -> Unit = {
            Text(stringResource(R.string.conflict_keep_both), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        if (selected) {
            Button(onClick = { open = true }, enabled = !busy, modifier = Modifier.fillMaxWidth(), contentPadding = COMPACT_PADDING) { content() }
        } else {
            OutlinedButton(onClick = { open = true }, enabled = !busy, modifier = Modifier.fillMaxWidth(), contentPadding = COMPACT_PADDING) { content() }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.conflict_both_ours_first)) },
                onClick = { open = false; onChoose(Choice.BothOursFirst) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.conflict_both_theirs_first)) },
                onClick = { open = false; onChoose(Choice.BothTheirsFirst) },
            )
        }
    }
}

@Composable
private fun ChoiceButton(text: String, selected: Boolean, busy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, enabled = !busy, modifier = modifier, contentPadding = COMPACT_PADDING) {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = !busy, modifier = modifier, contentPadding = COMPACT_PADDING) {
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 就地编辑这一处冲突的最终内容。 */
@Composable
private fun BlockEditor(number: Int, total: Int, text: String, onChange: (String) -> Unit, onSave: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.conflict_block_editing, number, total),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        OutlinedTextField(
            value = text,
            onValueChange = onChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp),
            textStyle = codeStyle(),
            label = { Text(stringResource(R.string.conflict_edit_block_label)) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
            Button(onClick = onSave, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.conflict_use_edited)) }
        }
    }
}

/** 一个冲突块每侧最多直接渲染的行数；再多就提示到整文件编辑器里看（避免一次性组合几千个 Text）。 */
private const val MAX_SIDE_LINES = 300

/** 折叠无冲突内容时，冲突上下各保留的行数；以及至少要折掉多少行才值得折叠。 */
private const val CONTEXT_LINES = 3
private const val MIN_FOLDED_LINES = 4

private val BAR_WIDTH = 3.dp
private val BAR_GAP = 8.dp
private val GUTTER_GAP = 6.dp
private val COMPACT_PADDING = PaddingValues(horizontal = 12.dp, vertical = 4.dp)

/** 一行在本地 / 远端文件中的行号（从 1 起）；0 表示这一行不属于那一方，那一栏留空。 */
private data class LineNo(val ours: Int, val theirs: Int) {
    companion object {
        val NONE = LineNo(0, 0)
    }
}

/**
 * 左边两栏行号（D-036）：[digits] 是一栏数字的宽度（按这个文件最大行号的位数），两栏分别用本地 / 远端的标题色。
 * [show] 为 false（菜单里关掉了"显示行号"，D-038）时整栏去掉，宽度为 0，色条贴着左边。
 */
private class Gutter(val show: Boolean, val digits: Dp, val oursColor: Color, val theirsColor: Color) {
    val width: Dp get() = if (show) digits * 2 + GUTTER_GAP * 2 else 0.dp
}

/** 色条画在行号与正文之间（每行各画一段，上下连成一条）；[bar] 为 Unspecified 时不画，只留出位置。 */
private fun Modifier.conflictBar(gutter: Gutter, bar: Color): Modifier =
    if (bar == Color.Unspecified) this
    else drawBehind { drawRect(bar, topLeft = Offset(gutter.width.toPx(), 0f), size = Size(BAR_WIDTH.toPx(), size.height)) }

/** 不标行号的一行（标题、按钮、提示）：左边留出行号与色条的位置，[tint] 只铺在正文部分。 */
@Composable
private fun GutterRow(gutter: Gutter, bar: Color, tint: Color = Color.Unspecified, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().conflictBar(gutter, bar).padding(start = gutter.width + BAR_WIDTH + BAR_GAP)) {
        Box(Modifier.fillMaxWidth().background(tint)) { content() }
    }
}

/** 两栏行号，右对齐；0 的那一栏留空。不显示行号时什么都不画。 */
@Composable
private fun LineNumbers(no: LineNo, gutter: Gutter, style: TextStyle) {
    if (!gutter.show) return
    Row {
        Text(if (no.ours > 0) no.ours.toString() else "", style = style, color = gutter.oursColor, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(gutter.digits))
        Spacer(Modifier.width(GUTTER_GAP))
        Text(if (no.theirs > 0) no.theirs.toString() else "", style = style, color = gutter.theirsColor, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(gutter.digits))
        Spacer(Modifier.width(GUTTER_GAP))
    }
}

/**
 * 等宽多行文本，左边带两栏行号（[numbers] 给出第 k 行的行号）；[tint] 是正文底色，[highlight] 里的行再加深一档。
 * 自动换行时每行一个 Row，行号与这一行顶端对齐；不换行时行号一栏、正文一栏，只有正文左右滑动。
 */
@Composable
private fun CodeLines(
    lines: List<String>,
    numbers: (Int) -> LineNo,
    gutter: Gutter,
    bar: Color,
    wrap: Boolean,
    tint: Color = Color.Unspecified,
    highlight: Set<Int> = emptySet(),
    highlightColor: Color = Color.Unspecified,
) {
    if (lines.isEmpty()) return
    val style = codeStyle()
    val lineColor = { i: Int -> if (i in highlight && highlightColor != Color.Unspecified) highlightColor else tint }
    val text: @Composable (Int, Modifier) -> Unit = { i, modifier ->
        Text(
            text = lines[i].ifEmpty { " " },
            style = style,
            softWrap = wrap,
            maxLines = if (wrap) Int.MAX_VALUE else 1,
            modifier = modifier.background(lineColor(i)).padding(horizontal = 4.dp),
        )
    }
    if (wrap) {
        Column(Modifier.fillMaxWidth()) {
            lines.indices.forEach { i ->
                Row(Modifier.fillMaxWidth().conflictBar(gutter, bar)) {
                    LineNumbers(numbers(i), gutter, style)
                    Spacer(Modifier.width(BAR_WIDTH + BAR_GAP))
                    text(i, Modifier.weight(1f))
                }
            }
        }
    } else {
        Row(Modifier.fillMaxWidth().conflictBar(gutter, bar)) {
            if (gutter.show) Column { lines.indices.forEach { LineNumbers(numbers(it), gutter, style) } }
            Spacer(Modifier.width(BAR_WIDTH + BAR_GAP))
            Column(Modifier.weight(1f).background(tint).horizontalScroll(rememberScrollState())) {
                lines.indices.forEach { text(it, Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun BlocksBottomBar(file: ConflictFile, busy: Boolean, onLocate: () -> Unit, onOpenEditor: () -> Unit, onMarkResolved: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        // edge-to-edge 下 Scaffold 不替 bottomBar 处理导航栏内边距，自己加（否则被三键导航栏遮住，L-021）
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.conflict_progress, file.decidedCount, file.conflictCount),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (!file.allDecided) {
                    TextButton(onClick = onLocate) { Text(stringResource(R.string.conflict_locate_undecided)) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onOpenEditor, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.conflict_preview_edit), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(onClick = onMarkResolved, enabled = !busy && file.allDecided, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.conflict_mark_resolved), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---- 整文件编辑器 ----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(vm: ConflictFileViewModel, onClose: () -> Unit) {
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmSaveWithMarkers by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }
    fun requestClose() { if (vm.editorDirty) confirmDiscard = true else onClose() }
    BackHandler { requestClose() }
    val markers = vm.editorMarkerLines

    Scaffold(
        // 键盘弹出时整页（连同底部栏）缩到键盘上方，正在输入的框保持可见
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conflict_editor_title, vm.path.substringAfterLast('/')), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = { requestClose() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (markers.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                pluralStringResource(R.plurals.conflict_editor_markers_warning, markers.size, formatLineList(markers)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = vm::selectNextMarker) { Text(stringResource(R.string.conflict_editor_next_marker)) }
                        }
                    }
                    vm.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Button(
                        onClick = { if (markers.isNotEmpty()) confirmSaveWithMarkers = true else vm.saveEditor() },
                        enabled = !vm.busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.conflict_editor_save))
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = vm.searchQuery,
                    onValueChange = { vm.searchQuery = it; notFound = false },
                    label = { Text(stringResource(R.string.conflict_editor_search)) },
                    singleLine = true,
                    isError = notFound,
                    supportingText = if (notFound) {
                        { Text(stringResource(R.string.conflict_editor_not_found)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { notFound = !vm.findNext() }),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { notFound = !vm.findNext() }, enabled = vm.searchQuery.isNotEmpty()) {
                    Text(stringResource(R.string.conflict_editor_find_next))
                }
            }
            OutlinedTextField(
                value = vm.editorValue,
                onValueChange = { vm.editorValue = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp, bottom = 8.dp),
                textStyle = codeStyle(),
            )
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            text = stringResource(R.string.conflict_editor_discard_confirm),
            confirmText = stringResource(R.string.conflict_editor_discard),
            destructive = true,
            onConfirm = onClose,
            onDismiss = { confirmDiscard = false },
        )
    }
    if (confirmSaveWithMarkers) {
        ConfirmDialog(
            title = stringResource(R.string.conflict_editor_save_markers_title),
            text = pluralStringResource(R.plurals.conflict_editor_save_markers_confirm, markers.size, formatLineList(markers)),
            confirmText = stringResource(R.string.conflict_editor_save_anyway),
            destructive = true,
            onConfirm = vm::saveEditor,
            onDismiss = { confirmSaveWithMarkers = false },
        )
    }
}

// ---- 工具 ------------------------------------------------------------------------------------

/** "3、7、12" 这样的行号列表（分隔符来自资源）；超过 8 个时省略后面的。 */
@Composable
private fun formatLineList(lines: List<Int>): String {
    val shown = lines.take(8).joinToString(stringResource(R.string.list_separator))
    return if (lines.size > 8) stringResource(R.string.conflict_lines_more, shown, lines.size) else shown
}

@Composable
private fun choiceText(choice: Choice): String = stringResource(
    when (choice) {
        Choice.Undecided -> R.string.conflict_choice_undecided
        Choice.Ours -> R.string.conflict_choice_ours
        Choice.Theirs -> R.string.conflict_choice_theirs
        Choice.BothOursFirst -> R.string.conflict_choice_both_ours_first
        Choice.BothTheirsFirst -> R.string.conflict_choice_both_theirs_first
        is Choice.Custom -> R.string.conflict_choice_custom
    },
)
