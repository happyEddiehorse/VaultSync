package com.mountriv.vaultsync.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mountriv.vaultsync.AppGraph
import com.mountriv.vaultsync.R
import com.mountriv.vaultsync.gitcore.store.BackupException
import com.mountriv.vaultsync.platform.ConfigBackup
import com.mountriv.vaultsync.sync.OnboardState
import com.mountriv.vaultsync.ui.ErrorCard
import com.mountriv.vaultsync.ui.InfoCard
import com.mountriv.vaultsync.ui.formatFullTime
import com.mountriv.vaultsync.ui.formatTime
import com.mountriv.vaultsync.ui.shortPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 备份与恢复（D-019、D-027、D-039，docs/UI.md §8）：卡片上写上次导出的时间与缺失项；导出后核对文件；
 * 恢复前先展示摘要，按勾选恢复；有任务在跑时不能恢复。
 */
@Composable
fun BackupCard(showMessage: (String) -> Unit, onRestored: (ConfigBackup.RestoreResult) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val last by AppGraph.backupStatus.state.collectAsStateWithLifecycle()
    val states by AppGraph.syncManager.states.collectAsStateWithLifecycle()
    val onboard by AppGraph.syncManager.onboard.collectAsStateWithLifecycle()
    val syncing = states.values.any { it.isBusy } || onboard is OnboardState.Running
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showExport by remember { mutableStateOf(false) }
    var exportPassphrase by remember { mutableStateOf<CharArray?>(null) }
    var preview by remember { mutableStateOf<ConfigBackup.Preview?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    val exportedText = stringResource(R.string.backup_exported)
    val exportedPartialText = stringResource(R.string.backup_exported_partial)
    val errNotBackup = stringResource(R.string.backup_err_not_backup)
    val errTooNew = stringResource(R.string.backup_err_too_new)
    val errWrongPass = stringResource(R.string.backup_err_wrong_passphrase)
    val errCorrupt = stringResource(R.string.backup_err_corrupt)
    val errVerify = stringResource(R.string.backup_err_verify)
    val errBusy = stringResource(R.string.backup_restore_busy)

    fun explain(e: Throwable): String = when {
        e is ConfigBackup.BusyException -> errBusy
        e is BackupException -> when (e.reason) {
            BackupException.Reason.NOT_A_BACKUP -> errNotBackup
            BackupException.Reason.TOO_NEW -> errTooNew
            BackupException.Reason.WRONG_PASSPHRASE -> errWrongPass
            BackupException.Reason.CORRUPT -> errCorrupt
        }
        // 导出后读回来对不上（verifyWritten），或写不进去
        e is IllegalStateException -> errVerify + "\n" + (e.message ?: e.toString())
        else -> e.message ?: e.toString()
    }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val pass = exportPassphrase
        exportPassphrase = null
        if (uri == null) {
            pass?.fill('\u0000')
            return@rememberLauncherForActivityResult
        }
        busy = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { AppGraph.backup.exportTo(context.contentResolver, uri, pass) } }
            pass?.fill('\u0000')
            busy = false
            r.onSuccess { res ->
                val missing = res.unreadableTokens.isNotEmpty() || res.unreadableKeys.isNotEmpty()
                showMessage(if (missing) exportedPartialText else exportedText)
            }.onFailure {
                AppGraph.logger.error("backup export failed", it)
                error = explain(it)
            }
        }
    }

    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: error("cannot open $uri")
                    AppGraph.backup.preview(text)
                }
            }
            busy = false
            r.onSuccess { importError = null; preview = it }.onFailure { error = explain(it) }
        }
    }

    InfoCard(stringResource(R.string.settings_backup)) {
        Text(stringResource(R.string.settings_backup_body), style = MaterialTheme.typography.bodySmall)
        // 上次导出（只在本机记录，D-039）
        if (last.lastExportAt > 0) {
            Text(
                stringResource(
                    if (last.withSecrets) R.string.backup_last_export_with_secrets else R.string.backup_last_export_without_secrets,
                    formatTime(context, last.lastExportAt),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val separator = stringResource(R.string.list_separator)
            val missing = last.unreadableTokens.map { stringResource(R.string.backup_missing_token, it) } +
                last.unreadableKeys.map { stringResource(R.string.backup_missing_key, it) }
            if (missing.isNotEmpty()) {
                Text(
                    stringResource(R.string.backup_last_export_missing, missing.joinToString(separator)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            Text(
                stringResource(R.string.backup_never_exported),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { error = null; showExport = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.backup_export), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedButton(
                onClick = { error = null; openDoc.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.backup_import), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { ErrorCard(stringResource(R.string.backup_failed), it) }
    }

    if (showExport) {
        ExportDialog(
            onDismiss = { showExport = false },
            onExport = { pass ->
                showExport = false
                exportPassphrase = pass
                createDoc.launch(ConfigBackup.defaultFileName())
            },
        )
    }

    preview?.let { p ->
        ImportDialog(
            preview = p,
            busy = busy,
            syncing = syncing,
            error = importError,
            onDismiss = { if (!busy) preview = null },
            onImport = { pass, selection ->
                busy = true
                importError = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { AppGraph.backup.restore(p, pass, selection) } }
                    pass?.fill('\u0000')
                    busy = false
                    r.onSuccess { res ->
                        preview = null
                        onRestored(res)
                    }.onFailure {
                        AppGraph.logger.warn("backup import failed", it)
                        importError = explain(it)
                    }
                }
            },
        )
    }
}
@Composable
private fun ExportDialog(onDismiss: () -> Unit, onExport: (CharArray?) -> Unit) {
    var includeSecrets by remember { mutableStateOf(true) }
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val tooShort = includeSecrets && pass.length < MIN_PASSPHRASE
    val mismatch = includeSecrets && confirm != pass
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.backup_export_body), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.backup_include_secrets), modifier = Modifier.weight(1f))
                    Switch(checked = includeSecrets, onCheckedChange = { includeSecrets = it })
                }
                if (includeSecrets) {
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text(stringResource(R.string.backup_passphrase)) },
                        supportingText = { Text(stringResource(R.string.backup_passphrase_hint, MIN_PASSPHRASE)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = confirm,
                        onValueChange = { confirm = it },
                        label = { Text(stringResource(R.string.backup_passphrase_confirm)) },
                        isError = confirm.isNotEmpty() && mismatch,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(stringResource(R.string.backup_without_secrets_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onExport(if (includeSecrets) pass.toCharArray() else null) },
                enabled = !tooShort && !mismatch,
            ) { Text(stringResource(R.string.backup_choose_location)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * 恢复前的摘要（D-039）：每个库、每把密钥、"软件设置"各一个复选框，默认值见 [ConfigBackup.RepoItem.selectedByDefault]。
 * 不恢复令牌与私钥时密钥恢复不了，只列名称。[syncing] 时"恢复"不可用并写明原因。
 */
@Composable
private fun ImportDialog(
    preview: ConfigBackup.Preview,
    busy: Boolean,
    syncing: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onImport: (CharArray?, ConfigBackup.Selection) -> Unit,
) {
    val context = LocalContext.current
    var pass by remember { mutableStateOf("") }
    var selection by remember(preview) { mutableStateOf(preview.defaultSelection()) }
    val doc = preview.document
    val withoutKeys = selection.copy(keyIds = emptySet())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_import_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(
                    stringResource(
                        R.string.backup_import_source,
                        preview.createdAtMs?.let { formatFullTime(context, it) } ?: doc.createdAt,
                        doc.device.ifBlank { "?" },
                        doc.appVersion.ifBlank { "?" },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                SectionTitle(pluralStringResource(R.plurals.backup_import_repos, preview.repos.size, preview.repos.size))
                preview.repos.forEach { item ->
                    val id = item.spec.id
                    CheckRow(
                        checked = id in selection.repoIds,
                        enabled = !busy,
                        onChange = { on -> selection = selection.copy(repoIds = if (on) selection.repoIds + id else selection.repoIds - id) },
                    ) {
                        Text(item.spec.name, style = MaterialTheme.typography.bodyMedium)
                        Text(shortPath(item.spec.localPath), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val note = when {
                            item.folderUsedBy != null -> stringResource(R.string.backup_repo_folder_used, item.folderUsedBy)
                            item.folderMissing -> stringResource(R.string.backup_repo_folder_missing)
                            item.replacesSameRepo -> stringResource(R.string.backup_repo_replaces)
                            else -> null
                        }
                        val warn = item.folderUsedBy != null || item.folderMissing
                        note?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (preview.keys.isNotEmpty()) {
                    SectionTitle(pluralStringResource(R.plurals.backup_import_keys, preview.keys.size, preview.keys.size))
                    if (preview.hasSecrets) {
                        preview.keys.forEach { item ->
                            val id = item.meta.id
                            CheckRow(
                                checked = id in selection.keyIds,
                                enabled = !busy,
                                onChange = { on -> selection = selection.copy(keyIds = if (on) selection.keyIds + id else selection.keyIds - id) },
                            ) {
                                Text(item.meta.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${item.meta.keyType} · ${item.meta.fingerprint}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (item.replacesSameKey) {
                                    Text(stringResource(R.string.backup_key_replaces), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    } else {
                        Text(preview.keys.joinToString(stringResource(R.string.list_separator)) { it.meta.name }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (preview.hasSettings) {
                    SectionTitle(stringResource(R.string.backup_import_settings_title))
                    CheckRow(checked = selection.settings, enabled = !busy, onChange = { selection = selection.copy(settings = it) }) {
                        Text(stringResource(R.string.backup_import_settings), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.backup_import_settings_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.padding(top = 4.dp))
                Text(stringResource(R.string.backup_import_known_hosts), style = MaterialTheme.typography.bodySmall)
                if (preview.hasSecrets) {
                    OutlinedTextField(
                        value = pass,
                        onValueChange = { pass = it },
                        label = { Text(stringResource(R.string.backup_passphrase)) },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(stringResource(R.string.backup_import_no_secrets), style = MaterialTheme.typography.bodySmall)
                }
                if (syncing) Text(stringResource(R.string.backup_restore_busy), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (preview.hasSecrets) {
                    TextButton(onClick = { onImport(null, withoutKeys) }, enabled = !busy && !syncing && !withoutKeys.isEmpty) {
                        Text(stringResource(R.string.backup_import_without_secrets))
                    }
                }
            }
        },
        confirmButton = {
            val effective = if (preview.hasSecrets) selection else withoutKeys
            TextButton(
                onClick = { onImport(if (preview.hasSecrets) pass.toCharArray() else null, effective) },
                enabled = !busy && !syncing && !effective.isEmpty && (!preview.hasSecrets || pass.isNotEmpty()),
            ) { Text(stringResource(R.string.backup_import_action)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

/** 一行复选：左边复选框，右边说明；点整行切换。 */
@Composable
private fun CheckRow(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, content: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { content() }
    }
}

private const val MIN_PASSPHRASE = 8
