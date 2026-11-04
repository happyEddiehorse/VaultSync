package com.mountriv.vaultsync.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import com.mountriv.vaultsync.R

/**
 * 软件设置页与库设置页共用的"未保存修改栏"（docs/UI.md §9.1、D-029）：放在 `Scaffold` 的 `bottomBar` 位，
 * 只在 [visible]（表单与已保存的值不同）时从底部滑出。左边是"有未保存的修改"，不能保存时换成错误色的 [blockedReason]；
 * 右边"放弃修改"（不确认）与"保存"。
 *
 * 背景延伸到系统导航栏下面，按钮用 `navigationBarsPadding()` 垫在导航栏上方（L-021）；
 * 页面给 `Scaffold` 加 `imePadding()`，键盘弹出时这条栏就在键盘上方（那时导航栏的内边距已被键盘的内边距抵掉）。
 */
@Composable
fun UnsavedChangesBar(visible: Boolean, blockedReason: String?, onDiscard: () -> Unit, onSave: () -> Unit) {
    AnimatedVisibility(visible = visible, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
        Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 左边一栏窄，不能保存的原因常要折行；均衡折行让两行差不多长，不会只剩一个字掉到第二行
                Text(
                    blockedReason ?: stringResource(R.string.unsaved_changes),
                    style = MaterialTheme.typography.bodyMedium.copy(lineBreak = BalancedLineBreak),
                    color = if (blockedReason != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.unsaved_discard)) }
                Button(onClick = onSave, enabled = blockedReason == null) { Text(stringResource(R.string.action_save)) }
            }
        }
    }
}

private val BalancedLineBreak = LineBreak(LineBreak.Strategy.Balanced, LineBreak.Strictness.Normal, LineBreak.WordBreak.Default)

/** 有未保存的修改时离开页面（顶栏返回、系统返回键或手势）的确认框："放弃"（错误色）/"继续编辑"。 */
@Composable
fun DiscardChangesDialog(onDiscard: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unsaved_leave_title)) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onDiscard() }) {
                Text(stringResource(R.string.unsaved_leave_discard), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.unsaved_keep_editing)) } },
    )
}
