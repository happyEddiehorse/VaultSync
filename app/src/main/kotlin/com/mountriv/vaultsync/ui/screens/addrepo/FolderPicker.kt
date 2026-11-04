package com.mountriv.vaultsync.ui.screens.addrepo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mountriv.vaultsync.R
import com.mountriv.vaultsync.platform.StoragePermissions
import com.mountriv.vaultsync.sync.OnboardMode
import com.mountriv.vaultsync.ui.ChipTone
import com.mountriv.vaultsync.ui.StatusChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class DirEntry(val file: File, val isVault: Boolean, val isRepo: Boolean)

/**
 * 自带目录浏览器（docs/ANDROID_PLATFORM.md §2）：从共享存储根开始，只列目录；
 * 标记 Obsidian 库（含 `.obsidian/`）与已有仓库（含 `.git/`）；可手动输入路径；克隆场景可新建文件夹。
 */
@Composable
fun FolderPicker(mode: OnboardMode, initial: String?, onChosen: (String) -> Unit) {
    val root = remember { StoragePermissions.sharedStorageRoot() }
    var current by remember { mutableStateOf(File(initial ?: File(root, "Documents").takeIf { it.isDirectory }?.path ?: root.path)) }
    var pathText by remember { mutableStateOf(current.absolutePath) }
    var entries by remember { mutableStateOf<List<DirEntry>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var newFolderDialog by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val notDirText = stringResource(R.string.folder_err_not_dir)

    LaunchedEffect(current) {
        pathText = current.absolutePath
        entries = withContext(Dispatchers.IO) {
            (current.listFiles { f -> f.isDirectory && f.name != "Android" && !f.name.startsWith(".") } ?: emptyArray())
                .sortedBy { it.name.lowercase() }
                .map { DirEntry(it, File(it, ".obsidian").isDirectory, File(it, ".git/HEAD").isFile) }
        }
        // 进入别的文件夹时从头显示
        listState.scrollToItem(0)
    }

    val validation = remember(current, entries, mode) { validateFolder(mode, current) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(
                when (mode) {
                    OnboardMode.CLONE -> R.string.folder_hint_clone
                    OnboardMode.INIT -> R.string.folder_hint_init
                    OnboardMode.ADOPT -> R.string.folder_hint_adopt
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = pathText,
            onValueChange = { pathText = it },
            label = { Text(stringResource(R.string.folder_path_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            trailingIcon = {
                TextButton(onClick = {
                    val f = File(pathText.trim())
                    if (f.isDirectory) { current = f; error = null } else error = notDirText
                }) { Text(stringResource(R.string.folder_go)) }
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { current.parentFile?.takeIf { current.absolutePath != root.absolutePath }?.let { current = it } }) {
                Text(stringResource(R.string.folder_up))
            }
            if (mode == OnboardMode.CLONE) {
                OutlinedButton(onClick = { newFolderDialog = true }) { Text(stringResource(R.string.folder_new)) }
            }
        }
        HorizontalDivider()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .overflowScrollbar(listState, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
            // 右侧留出滚动条的位置，免得压住"已有仓库"等标签
            contentPadding = PaddingValues(end = 8.dp),
        ) {
            if (entries.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.folder_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            items(entries, key = { it.file.absolutePath }) { e ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { current = e.file }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(e.file.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (e.isRepo) StatusChip(stringResource(R.string.folder_badge_repo), ChipTone.OK)
                    else if (e.isVault) StatusChip(stringResource(R.string.folder_badge_vault), ChipTone.WARN)
                }
            }
        }
        HorizontalDivider()
        validation.warning?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
        validation.error?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { onChosen(current.absolutePath) },
            enabled = validation.error == null,
            modifier = Modifier.align(Alignment.End),
        ) { Text(stringResource(R.string.folder_use_this)) }
    }

    if (newFolderDialog) {
        var newName by remember { mutableStateOf("") }
        var newError by remember { mutableStateOf<Int?>(null) }
        AlertDialog(
            onDismissRequest = { newFolderDialog = false },
            title = { Text(stringResource(R.string.folder_new)) },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it; newError = null },
                    label = { Text(stringResource(R.string.folder_new_name)) },
                    singleLine = true,
                    isError = newError != null,
                    supportingText = newError?.let { { Text(stringResource(it)) } },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = newName.trim()
                    when {
                        n.isEmpty() || n.contains('/') || n.contains('\\') || n == "." || n == ".." -> newError = R.string.folder_err_name
                        File(current, n).exists() -> newError = R.string.folder_err_exists
                        !File(current, n).mkdir() -> newError = R.string.folder_err_create
                        else -> { current = File(current, n); newFolderDialog = false }
                    }
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { newFolderDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * 列表内容超出框高时，在右侧一直显示一条细滚动条（不淡出、不能拖动），看得出框里还有没显示的内容；
 * 内容没填满或正好填满时不显示。滑块长度与位置按可见行的平均行高估算（这里每行高度相近）。
 */
private fun Modifier.overflowScrollbar(state: LazyListState, color: Color, width: Dp = 3.dp, minLength: Dp = 24.dp): Modifier =
    drawWithContent {
        drawContent()
        val canBack = state.canScrollBackward
        val canForward = state.canScrollForward
        if (!canBack && !canForward) return@drawWithContent
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) return@drawWithContent
        val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        val itemSize = visible.sumOf { it.size }.toFloat() / visible.size
        val total = maxOf(itemSize * info.totalItemsCount, viewport + 1f)
        val scrolled = state.firstVisibleItemIndex * itemSize + state.firstVisibleItemScrollOffset
        val thumb = (viewport / total * size.height).coerceIn(minLength.toPx().coerceAtMost(size.height), size.height)
        val range = size.height - thumb
        // 估算有误差：到顶、到底时直接贴边
        val top = when {
            !canBack -> 0f
            !canForward -> range
            else -> (scrolled / (total - viewport) * range).coerceIn(0f, range)
        }
        val w = width.toPx()
        drawRoundRect(color, topLeft = Offset(size.width - w, top), size = Size(w, thumb), cornerRadius = CornerRadius(w / 2))
    }

private data class FolderValidation(val error: Int?, val warning: Int?)

private fun validateFolder(mode: OnboardMode, dir: File): FolderValidation {
    if (!dir.isDirectory) return FolderValidation(R.string.folder_err_not_dir, null)
    val hasGit = File(dir, ".git/HEAD").isFile
    val hasObsidian = File(dir, ".obsidian").isDirectory
    return when (mode) {
        OnboardMode.CLONE -> {
            val nonEmpty = dir.list()?.isNotEmpty() == true
            FolderValidation(if (nonEmpty) R.string.folder_err_not_empty else null, null)
        }
        OnboardMode.INIT -> FolderValidation(
            if (hasGit) R.string.folder_err_has_git else null,
            if (!hasObsidian) R.string.folder_warn_no_obsidian else null,
        )
        OnboardMode.ADOPT -> FolderValidation(if (!hasGit) R.string.folder_err_no_git else null, null)
    }
}
