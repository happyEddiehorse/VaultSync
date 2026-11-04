package com.mountriv.vaultsync.ui.screens.logs

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mountriv.vaultsync.AppGraph
import com.mountriv.vaultsync.BuildConfig
import com.mountriv.vaultsync.R
import com.mountriv.vaultsync.data.RepoStatus
import com.mountriv.vaultsync.platform.LogStore
import com.mountriv.vaultsync.ui.ChipTone
import com.mountriv.vaultsync.ui.StatusChip
import com.mountriv.vaultsync.ui.copyToClipboard
import com.mountriv.vaultsync.ui.formatFullTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 日志列表（docs/UI.md §7）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(repoId: String, onBack: () -> Unit, onOpenLog: (String) -> Unit) {
    var entries by remember { mutableStateOf<List<LogStore.Entry>>(emptyList()) }
    LaunchedEffect(repoId) {
        entries = withContext(Dispatchers.IO) { AppGraph.logs.list(repoId) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logs_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Text(
                stringResource(R.string.logs_empty),
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Scaffold
        }
        LazyColumn(modifier = Modifier
            .fillMaxSize()
            .padding(padding)) {
            items(entries, key = { it.file.name }) { e ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenLog(e.file.name) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(formatFullTime(LocalContext.current, e.startedAt), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(opLabel(e.op)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val (label, tone) = when (e.result) {
                        RepoStatus.SUCCESS -> stringResource(R.string.log_result_success) to ChipTone.OK
                        RepoStatus.CONFLICTS -> stringResource(R.string.log_result_conflicts) to ChipTone.WARN
                        RepoStatus.FAILED -> stringResource(R.string.log_result_failed) to ChipTone.ERROR
                        RepoStatus.CANCELLED -> stringResource(R.string.log_result_cancelled) to ChipTone.NEUTRAL
                        else -> stringResource(R.string.log_result_unknown) to ChipTone.NEUTRAL
                    }
                    StatusChip(label, tone)
                }
                HorizontalDivider()
            }
        }
    }
}

private fun opLabel(op: String?): Int = when (op) {
    "sync" -> R.string.log_op_sync
    "clone" -> R.string.log_op_clone
    "init" -> R.string.log_op_init
    "adopt" -> R.string.log_op_adopt
    "maintain" -> R.string.log_op_maintain
    else -> R.string.log_op_unknown
}

/** 崩溃报告内容：与日志详情相同的查看、复制、分享。 */
@Composable
fun CrashReportScreen(fileName: String, onBack: () -> Unit) {
    val file = remember(fileName) { AppGraph.crashes.file(fileName) }
    TextFileScreen(title = stringResource(R.string.crash_report_title), file = file, onBack = onBack)
}

/** 日志内容：等宽、可选择、复制全文、分享文件。 */
@Composable
fun LogDetailScreen(repoId: String, fileName: String, onBack: () -> Unit) {
    val file = remember(repoId, fileName) { AppGraph.logs.file(repoId, fileName) }
    TextFileScreen(title = fileName, file = file, onBack = onBack)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextFileScreen(title: String, file: java.io.File?, onBack: () -> Unit) {
    val context = LocalContext.current
    val shareTitle = stringResource(R.string.log_share_title)
    var text by remember { mutableStateOf("") }
    LaunchedEffect(file) {
        text = withContext(Dispatchers.IO) { file?.readText() ?: "" }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                actions = {
                    TextButton(onClick = { copyToClipboard(context, "log", text) }, enabled = text.isNotEmpty()) { Text(stringResource(R.string.action_copy)) }
                    TextButton(
                        onClick = {
                            val f = file ?: return@TextButton
                            val uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".files", f)
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(Intent.createChooser(send, shareTitle))
                        },
                        enabled = file != null,
                    ) { Text(stringResource(R.string.action_share)) }
                },
            )
        },
    ) { padding ->
        SelectionContainer(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}
