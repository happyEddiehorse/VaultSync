package com.mountriv.vaultsync.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mountriv.vaultsync.R
import com.mountriv.vaultsync.data.ViewOptions
import com.mountriv.vaultsync.gitcore.model.FailureKind
import java.util.Calendar
import java.util.Locale

/** 错误用卡片而不是 toast（可复制，见 docs/UI.md §9）。 */
@Composable
fun ErrorCard(title: String, body: String?, modifier: Modifier = Modifier, actions: @Composable FlowRowScope.() -> Unit = {}) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            if (!body.isNullOrBlank()) {
                SelectionContainer {
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            // 按钮放不下一行时折到下一行，不把后面的按钮挤成竖排（德语、俄语的按钮文字长）
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) { actions() }
        }
    }
}

/**
 * 失败说明卡片（docs/UI.md §10）：标题写"哪一步 + 什么原因"，正文是可能原因与建议操作，原始英文信息折叠在"技术信息"里（可选中复制）。
 */
@Composable
fun FailureCard(
    title: String,
    kind: FailureKind?,
    technical: String?,
    modifier: Modifier = Modifier,
    proxyEnabled: Boolean = false,
    actions: @Composable FlowRowScope.() -> Unit = {},
) {
    val context = LocalContext.current
    var showTechnical by remember { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(
                Texts.failureHint(context, kind, proxyEnabled),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (!technical.isNullOrBlank()) {
                if (showTechnical) {
                    SelectionContainer {
                        Text(
                            technical,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                actions()
                if (!technical.isNullOrBlank()) {
                    TextButton(onClick = { showTechnical = !showTechnical }) {
                        Text(stringResource(if (showTechnical) R.string.action_hide_technical else R.string.action_show_technical))
                    }
                }
            }
        }
    }
}

@Composable
fun InfoCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun StatusChip(text: String, tone: ChipTone) {
    val (bg, fg) = when (tone) {
        ChipTone.OK -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ChipTone.WARN -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        ChipTone.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        ChipTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(text) },
        colors = AssistChipDefaults.assistChipColors(disabledContainerColor = bg, disabledLabelColor = fg),
        border = null,
    )
}

enum class ChipTone { OK, WARN, ERROR, NEUTRAL }

@Composable
fun ConfirmDialog(
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    title: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title?.let { { Text(it) } },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirmText, color = if (destructive) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Android 13+ 需要运行时申请通知权限；返回一个"需要时申请"的函数（已授权或低版本时什么都不做）。
 * 申请与否都不影响同步本身，只影响进度与结果通知能否显示。
 */
@Composable
fun rememberNotificationPermissionRequest(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
}

/** 界面语言（[context] 套过 `AppLocale` 之后的语言，不是 `Locale.getDefault()` 的系统语言，D-040）。 */
fun uiLocale(context: Context): Locale = context.resources.configuration.locales[0]

/**
 * 列表里的时间（首页"上次同步"、提交历史，D-040）：按界面语言的习惯，月份用缩写（"10月30日 14:05"、"Oct 30, 14:05"、"30. Okt., 14:05"），
 * 今年的不写年份，往年的写；12 / 24 小时跟系统设置。
 */
fun formatTime(context: Context, epochMs: Long): String {
    val thisYear = Calendar.getInstance().get(Calendar.YEAR)
    val year = Calendar.getInstance().apply { timeInMillis = epochMs }.get(Calendar.YEAR)
    return formatSkeleton(context, epochMs, if (year == thisYear) "MMMd" else "yMMMd", seconds = false)
}

/** 完整的时间（提交详情、日志、库详情等）：年月日 + 时分秒，"2026年10月30日 14:05:33"、"Oct 30, 2026, 14:05:33"。 */
fun formatFullTime(context: Context, epochMs: Long): String = formatSkeleton(context, epochMs, "yMMMd", seconds = true)

/** ICU 骨架（API 24+）：系统按语言给出日期的顺序、分隔符与月份写法；小时用 H（24 小时）或 h（12 小时，带上午 / 下午）。 */
private fun formatSkeleton(context: Context, epochMs: Long, date: String, seconds: Boolean): String {
    val hours = if (android.text.format.DateFormat.is24HourFormat(context)) "Hm" else "hm"
    val skeleton = date + hours + (if (seconds) "s" else "")
    val format = android.icu.text.DateFormat.getInstanceForSkeleton(skeleton, uiLocale(context))
    return format.format(java.util.Date(epochMs))
}

/** 小数按界面语言写（"5.2"、"5,2"）。 */
fun formatDecimal(context: Context, value: Double, digits: Int = 1): String = String.format(uiLocale(context), "%.${digits}f", value)

/** "0.4 秒"、"5.2 秒"、"1 分 12 秒"。 */
fun formatDuration(context: Context, ms: Long): String = when {
    ms < 60_000 -> context.getString(R.string.duration_seconds, formatDecimal(context, ms / 1000.0))
    else -> context.getString(R.string.duration_minutes, (ms / 60_000).toInt(), ((ms % 60_000) / 1000).toInt())
}

/** "512 B"、"12.0 KB"、"23,4 Mo"：小数点按界面语言，单位写法在资源里（D-040）。 */
fun formatBytes(context: Context, n: Long): String = when {
    n < 1024 -> context.getString(R.string.size_bytes, n.toString())
    n < 1024 * 1024 -> context.getString(R.string.size_kb, formatDecimal(context, n / 1024.0))
    n < 1024L * 1024 * 1024 -> context.getString(R.string.size_mb, formatDecimal(context, n / (1024.0 * 1024.0)))
    else -> context.getString(R.string.size_gb, formatDecimal(context, n / (1024.0 * 1024.0 * 1024.0), 2))
}

/** 路径显示：去掉 `/storage/emulated/0/` 前缀。 */
fun shortPath(path: String): String = path.removePrefix("/storage/emulated/0/").removePrefix("/storage/emulated/0")

/**
 * 一组标签里最宽一个的宽度（按 [style] 实测），给"标签 + 值"的多行做对齐用。
 * 中英文标签长短差很多（"认证" / "Authentication"），写死宽度会把英文单词从中间折断（D-030）。
 */
@Composable
fun rememberLabelWidth(labels: List<String>, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(labels, style, density) {
        with(density) { (labels.maxOfOrNull { measurer.measure(it, style, maxLines = 1).size.width } ?: 0).toDp() }
    }
}

/**
 * ⋮ 菜单里的"自动换行""显示行号"两个复选框（D-038，diff 页与冲突页共用，两页各自记住）。
 * 点一项先关菜单（[onDismiss]），再把改后的选项交给 [onChange] 保存。
 */
@Composable
fun ViewOptionsMenuItems(options: ViewOptions, onDismiss: () -> Unit, onChange: (ViewOptions) -> Unit) {
    CheckMenuItem(stringResource(R.string.view_wrap), options.wrapLines) {
        onDismiss()
        onChange(options.copy(wrapLines = !options.wrapLines))
    }
    CheckMenuItem(stringResource(R.string.view_line_numbers), options.lineNumbers) {
        onDismiss()
        onChange(options.copy(lineNumbers = !options.lineNumbers))
    }
}

@Composable
private fun CheckMenuItem(text: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        // 复选框只显示状态，点整行切换；读屏读出"已勾选 / 未勾选"
        trailingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
        onClick = onClick,
        modifier = Modifier.semantics { toggleableState = ToggleableState(checked) },
    )
}

/**
 * 进度卡片上的"取消"。点了之后马上变成灰的"正在取消…"（D-035）：有的步骤要等当前文件写完才停得下来，
 * 不给反馈的话看起来像没点上。
 */
@Composable
fun CancelButton(cancelling: Boolean, modifier: Modifier = Modifier, onCancel: () -> Unit) {
    OutlinedButton(onClick = onCancel, enabled = !cancelling, modifier = modifier) {
        Text(stringResource(if (cancelling) R.string.progress_cancelling else R.string.action_cancel))
    }
}
