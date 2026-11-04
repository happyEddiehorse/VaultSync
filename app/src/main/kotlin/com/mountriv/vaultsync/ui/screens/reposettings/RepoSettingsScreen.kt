package com.mountriv.vaultsync.ui.screens.reposettings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mountriv.vaultsync.AppGraph
import com.mountriv.vaultsync.R
import com.mountriv.vaultsync.sync.JobType
import com.mountriv.vaultsync.sync.MaintenanceResult
import com.mountriv.vaultsync.sync.RepoSyncState
import com.mountriv.vaultsync.sync.SyncService
import com.mountriv.vaultsync.ui.ConfirmDialog
import com.mountriv.vaultsync.ui.DiscardChangesDialog
import com.mountriv.vaultsync.ui.ErrorCard
import com.mountriv.vaultsync.ui.InfoCard
import com.mountriv.vaultsync.ui.RemoteFields
import com.mountriv.vaultsync.ui.Texts
import com.mountriv.vaultsync.ui.UnsavedChangesBar
import com.mountriv.vaultsync.ui.formatBytes
import com.mountriv.vaultsync.ui.formatDuration
import com.mountriv.vaultsync.ui.formatFullTime

/**
 * 仓库设置（docs/UI.md §4.1）：名称、远端与认证（HTTPS 令牌 / 选择 SSH 密钥）、本库的提交者与提交信息、
 * 仓库维护、推荐的 .gitignore 规则、从列表移除。前三组的修改用底部的未保存修改栏保存（§9.1，D-029），其余是即时操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoSettingsScreen(repoId: String, onBack: () -> Unit, onOpenSettings: () -> Unit, onRemoved: () -> Unit) {
    val vm: RepoSettingsViewModel = viewModel(key = "repo-settings:$repoId") { RepoSettingsViewModel(repoId) }
    val scope = rememberCoroutineScope()
    val repos by AppGraph.repos.state.collectAsStateWithLifecycle()
    val spec = repos.firstOrNull { it.id == repoId }
    val states by AppGraph.syncManager.states.collectAsStateWithLifecycle()
    val maintenance by AppGraph.syncManager.maintenance.collectAsStateWithLifecycle()
    // 观察密钥列表：密钥生成、删除后"能否保存"要重新计算
    val keys by AppGraph.sshKeys.state.collectAsStateWithLifecycle()
    val state = states[repoId] ?: RepoSyncState.Idle
    val busy = state.isBusy
    val snackbar = remember { SnackbarHostState() }
    val savedText = stringResource(R.string.repo_settings_saved)
    val saveFailedText = stringResource(R.string.repo_settings_save_failed)
    val focusManager = LocalFocusManager.current
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var removed by remember { mutableStateOf(false) }

    // 提示完再清标记：先清会让 key 变化，正在显示的提示随协程一起被取消
    LaunchedEffect(vm.saved) {
        if (vm.saved) {
            snackbar.showSnackbar(savedText)
            vm.consumeSaved()
        }
    }
    LaunchedEffect(vm.saveError) {
        if (vm.saveError != null) snackbar.showSnackbar(saveFailedText)
    }
    // 维护结束后刷新统计
    var wasMaintaining by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        val maintaining = state is RepoSyncState.Running && state.type == JobType.MAINTAIN || state is RepoSyncState.Queued && state.type == JobType.MAINTAIN
        if (wasMaintaining && !maintaining) vm.refreshRepoInfo()
        wasMaintaining = maintaining
    }

    if (spec == null) {
        LaunchedEffect(Unit) { if (!removed) onBack() }
        return
    }

    // keys 只是为了在密钥列表变化时重新计算（所选密钥可能刚被删除或刚生成）
    val dirty = keys.let { vm.dirty }
    val blockedReason = keys.let { vm.saveBlockedReason(busy) }?.let { stringResource(it) }
    // 有未保存的修改时离开要确认；前进到别的页面（管理密钥、点通知打开别的库）不算离开，表单在 ViewModel 里
    fun requestLeave() {
        if (dirty) confirmLeave = true else onBack()
    }
    BackHandler(enabled = dirty) { requestLeave() }

    Scaffold(
        // 键盘弹出时整页（连同底部栏）缩到键盘上方，正在输入的框保持可见
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.repo_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = ::requestLeave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
        bottomBar = {
            UnsavedChangesBar(
                visible = dirty,
                blockedReason = blockedReason,
                onDiscard = { focusManager.clearFocus(); vm.discard() },
                onSave = { focusManager.clearFocus(); vm.save() },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (busy) {
                InfoCard(stringResource(R.string.repo_settings_busy_title)) {
                    Text(stringResource(R.string.repo_settings_busy_body), style = MaterialTheme.typography.bodyMedium)
                }
            }
            InfoCard(stringResource(R.string.repo_settings_general)) {
                OutlinedTextField(
                    value = vm.name,
                    onValueChange = { vm.name = it },
                    label = { Text(stringResource(R.string.repo_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.repo_settings_folder, spec.localPath), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.repo_settings_branch, spec.branch), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            InfoCard(stringResource(R.string.repo_settings_remote)) {
                RemoteFields(
                    form = vm.remote,
                    repoId = repoId,
                    scope = scope,
                    allowBlankUrl = false,
                    onManageKeys = onOpenSettings,
                )
            }
            InfoCard(stringResource(R.string.repo_settings_identity)) {
                Text(stringResource(R.string.repo_settings_identity_body), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = vm.authorName,
                    onValueChange = { vm.authorName = it },
                    label = { Text(stringResource(R.string.identity_name_label)) },
                    placeholder = { Text(AppGraph.settings.state.value.authorName) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = vm.authorEmail,
                    onValueChange = { vm.authorEmail = it },
                    label = { Text(stringResource(R.string.identity_email_label)) },
                    placeholder = { Text(AppGraph.settings.state.value.authorEmail) },
                    singleLine = true,
                    isError = !vm.identityValid,
                    supportingText = if (!vm.identityValid) {
                        { Text(stringResource(R.string.repo_settings_identity_incomplete)) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = vm.commitTemplate,
                    onValueChange = { vm.commitTemplate = it },
                    label = { Text(stringResource(R.string.commit_template_label)) },
                    supportingText = { Text(stringResource(R.string.commit_template_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = vm.dateFormat,
                    onValueChange = { vm.dateFormat = it },
                    label = { Text(stringResource(R.string.date_format_label)) },
                    supportingText = { Text(stringResource(R.string.date_format_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            vm.saveError?.let { ErrorCard(stringResource(R.string.repo_settings_save_failed), it) }

            MaintenanceCard(vm, busy, state, maintenance[repoId])
            GitignoreCard(vm, busy)

            OutlinedButton(onClick = { confirmRemove = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.repo_settings_remove), color = MaterialTheme.colorScheme.error)
            }
            Text(stringResource(R.string.repo_settings_remove_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (confirmRemove) {
        ConfirmDialog(
            title = stringResource(R.string.repo_settings_remove_title, spec.name),
            text = stringResource(R.string.repo_settings_remove_confirm),
            confirmText = stringResource(R.string.repo_settings_remove),
            destructive = true,
            onConfirm = { removed = true; AppGraph.syncManager.removeRepo(spec); onRemoved() },
            onDismiss = { confirmRemove = false },
        )
    }
    if (confirmLeave) {
        DiscardChangesDialog(onDiscard = onBack, onDismiss = { confirmLeave = false })
    }
}

@Composable
private fun MaintenanceCard(vm: RepoSettingsViewModel, busy: Boolean, state: RepoSyncState, last: MaintenanceResult?) {
    val context = LocalContext.current
    InfoCard(stringResource(R.string.maintenance_title)) {
        Text(stringResource(R.string.maintenance_body), style = MaterialTheme.typography.bodySmall)
        val stats = vm.stats
        when {
            vm.statsError != null -> Text(vm.statsError!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            stats == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> {
                Text(
                    stringResource(
                        R.string.maintenance_stats,
                        formatBytes(LocalContext.current, stats.totalBytes),
                        stats.looseObjects,
                        stats.packFiles,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(if (stats.recommended) R.string.maintenance_recommended else R.string.maintenance_not_needed),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stats.recommended) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (last) {
            is MaintenanceResult.Done -> Text(
                stringResource(
                    R.string.maintenance_last_done,
                    formatFullTime(LocalContext.current, last.at),
                    formatBytes(LocalContext.current, last.result.before.totalBytes),
                    formatBytes(LocalContext.current, last.result.after.totalBytes),
                    formatDuration(context, last.result.durationMs),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            is MaintenanceResult.Failed -> Text(
                stringResource(R.string.maintenance_last_failed, formatFullTime(LocalContext.current, last.at), Texts.failureTitle(context, last.kind)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            null -> Unit
        }
        val running = state is RepoSyncState.Running && state.type == JobType.MAINTAIN
        if (running) {
            Text(Texts.progressLine(context, JobType.MAINTAIN, null, state.task, state.completed, state.total), style = MaterialTheme.typography.bodySmall)
            Text(Texts.meterLine(context, JobType.MAINTAIN, null, state.meter), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        OutlinedButton(
            onClick = { if (AppGraph.syncManager.enqueueMaintain(vm.repoId)) SyncService.start(context) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.maintenance_run)) }
    }
}

@Composable
private fun GitignoreCard(vm: RepoSettingsViewModel, busy: Boolean) {
    var confirm by remember { mutableStateOf(false) }
    InfoCard(stringResource(R.string.gitignore_title)) {
        val missing = vm.gitignoreMissing
        when {
            missing == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            missing.isEmpty() -> Text(stringResource(R.string.gitignore_complete), style = MaterialTheme.typography.bodyMedium)
            else -> {
                Text(pluralStringResource(R.plurals.gitignore_missing, missing.size, missing.size), style = MaterialTheme.typography.bodyMedium)
                Text(missing.joinToString("\n"), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.gitignore_note), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { confirm = true }, enabled = !busy) { Text(stringResource(R.string.gitignore_append)) }
                }
            }
        }
        vm.gitignoreAdded?.takeIf { it.isNotEmpty() }?.let {
            Text(pluralStringResource(R.plurals.gitignore_appended, it.size, it.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (confirm) {
        ConfirmDialog(
            text = stringResource(R.string.gitignore_append_confirm),
            confirmText = stringResource(R.string.gitignore_append),
            onConfirm = vm::appendGitignore,
            onDismiss = { confirm = false },
        )
    }
}
