package com.mountriv.vaultsync.ui.screens.repodetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mountriv.vaultsync.AppGraph
import com.mountriv.vaultsync.R
import com.mountriv.vaultsync.data.RepoStatus
import com.mountriv.vaultsync.gitcore.auth.CredentialsFactory
import com.mountriv.vaultsync.gitcore.auth.RemoteUrl
import com.mountriv.vaultsync.gitcore.model.AuthSpec
import com.mountriv.vaultsync.gitcore.model.RepoSpec
import com.mountriv.vaultsync.gitcore.repo.RepoInspector
import com.mountriv.vaultsync.gitcore.repo.RepoInspector.Residue
import com.mountriv.vaultsync.platform.ConfigBackup
import com.mountriv.vaultsync.platform.KeystoreSecretStore
import com.mountriv.vaultsync.platform.SshKeyStore
import com.mountriv.vaultsync.sync.JobType
import com.mountriv.vaultsync.sync.RepoSyncState
import com.mountriv.vaultsync.sync.SyncService
import com.mountriv.vaultsync.ui.CancelButton
import com.mountriv.vaultsync.ui.ConfirmDialog
import com.mountriv.vaultsync.ui.ErrorCard
import com.mountriv.vaultsync.ui.FailureCard
import com.mountriv.vaultsync.ui.InfoCard
import com.mountriv.vaultsync.ui.Texts
import com.mountriv.vaultsync.ui.copyToClipboard
import com.mountriv.vaultsync.ui.formatFullTime
import com.mountriv.vaultsync.ui.rememberNotificationPermissionRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 仓库详情（docs/UI.md §4）：状态、同步进度与结果、冲突与残留处理、认证问题提示；历史、日志、仓库设置的入口。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoDetailScreen(
    repoId: String,
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenConflicts: () -> Unit,
    onOpenRepoSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repos by AppGraph.repos.state.collectAsStateWithLifecycle()
    val spec = repos.firstOrNull { it.id == repoId }
    val states by AppGraph.syncManager.states.collectAsStateWithLifecycle()
    val cancelling by AppGraph.syncManager.cancelling.collectAsStateWithLifecycle()
    val statuses by AppGraph.statuses.state.collectAsStateWithLifecycle()
    val keyState by AppGraph.sshKeys.state.collectAsStateWithLifecycle()
    val settings by AppGraph.settings.state.collectAsStateWithLifecycle()
    val state = states[repoId] ?: RepoSyncState.Idle
    val status = statuses[repoId]
    val requestNotifications = rememberNotificationPermissionRequest()

    var info by remember { mutableStateOf<RepoInspector.RepoInfo?>(null) }
    var inspectError by remember { mutableStateOf<String?>(null) }
    var tokenStatus by remember { mutableStateOf<KeystoreSecretStore.Status?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var confirmAbort by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    // 库文件夹不存在或是空的：可以重新克隆到原位置（D-039）
    var canReclone by remember { mutableStateOf(false) }

    // 进程重启后状态为 Idle：打开详情页时用 RepoInspector 重新判定（可能是冲突/残留）
    LaunchedEffect(spec, state.isBusy, refreshKey) {
        if (spec == null || state.isBusy) return@LaunchedEffect
        val r = withContext(Dispatchers.IO) { runCatching { AppGraph.syncManager.inspect(spec) } }
        r.onSuccess { info = it; inspectError = null }.onFailure { inspectError = it.message ?: it.toString() }
        canReclone = withContext(Dispatchers.IO) { !ConfigBackup.hasContent(File(spec.localPath)) }
        tokenStatus = when (val a = spec.auth) {
            is AuthSpec.HttpsToken -> withContext(Dispatchers.IO) { AppGraph.secrets.status(a.secretId) }
            else -> null
        }
        if (spec.auth is AuthSpec.SshKey || CredentialsFactory.usesSsh(spec.remoteUrl)) {
            withContext(Dispatchers.IO) { runCatching { AppGraph.sshKeys.refresh() } }
        }
    }

    fun refresh() { refreshKey++ }

    fun startSync() {
        requestNotifications()
        if (AppGraph.syncManager.enqueueSync(repoId)) SyncService.start(context)
    }

    if (spec == null) {
        // 仓库已被移除（从通知点进来等）
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(spec.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                actions = {
                    IconButton(onClick = onOpenRepoSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.detail_open_repo_settings))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeaderCard(spec, info)

            // 同步中 / 结果
            when (state) {
                is RepoSyncState.Running -> InfoCard(
                    stringResource(
                        when (state.type) {
                            JobType.MAINTAIN -> R.string.status_maintaining
                            JobType.RECLONE -> R.string.status_cloning
                            else -> R.string.status_syncing
                        },
                    ),
                ) {
                    Text(Texts.progressLine(context, state.type, state.stage, state.task, state.completed, state.total), style = MaterialTheme.typography.bodyMedium)
                    Text(Texts.meterLine(context, state.type, state.stage, state.meter), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.total > 0) {
                        LinearProgressIndicator(progress = { state.completed.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    CancelButton(repoId in cancelling, Modifier.align(Alignment.End)) { AppGraph.syncManager.cancel(repoId) }
                }
                is RepoSyncState.Queued -> InfoCard(stringResource(R.string.status_queued)) {
                    Text(stringResource(R.string.detail_queued_body), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { AppGraph.syncManager.cancel(repoId) }, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
                is RepoSyncState.Error -> FailureCard(
                    title = stringResource(R.string.detail_failed_title, Texts.stage(context, state.stage), Texts.failureTitle(context, state.kind)),
                    kind = state.kind,
                    technical = state.message,
                    proxyEnabled = settings.proxyEnabled,
                ) {
                    TextButton(onClick = onOpenLogs) { Text(stringResource(R.string.action_view_logs)) }
                }
                is RepoSyncState.Done -> InfoCard(stringResource(R.string.detail_last_result, formatFullTime(LocalContext.current, state.at))) {
                    val s = state.summary
                    Text(
                        Texts.successSummary(context, s.localFilesCommitted, s.mergeStatus, s.pushStatus, s.durationMs),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is RepoSyncState.Cancelled -> InfoCard(stringResource(R.string.detail_last_result, formatFullTime(LocalContext.current, state.at))) {
                    Text(stringResource(R.string.result_cancelled), style = MaterialTheme.typography.bodyMedium)
                }
                else -> if (status != null) LastStatusCard(status, settings.proxyEnabled, onOpenLogs)
            }

            // 冲突：进程内状态或 RepoInspector 判定为 MERGING 都算
            val conflictCount = (state as? RepoSyncState.Conflicts)?.files?.size
                ?: info?.takeIf { it.residues.contains(Residue.MERGE_WITH_CONFLICTS) }?.conflicting
            if (conflictCount != null && conflictCount > 0) {
                ErrorCard(
                    title = pluralStringResource(R.plurals.detail_conflicts_title, conflictCount, conflictCount),
                    body = stringResource(R.string.detail_conflicts_body),
                ) {
                    Button(onClick = onOpenConflicts, enabled = !state.isBusy) { Text(stringResource(R.string.detail_open_conflicts)) }
                    TextButton(onClick = { confirmAbort = true }, enabled = !state.isBusy) { Text(stringResource(R.string.detail_abort_merge)) }
                }
            }

            // 冲突已全部解决但合并未提交（例如在冲突页解决完没点"完成合并"，或进程被杀）
            if (info?.residues?.contains(Residue.MERGE_RESOLVED_UNCOMMITTED) == true && !state.isBusy) {
                InfoCard(stringResource(R.string.detail_merge_resolved_title)) {
                    Text(stringResource(R.string.detail_merge_resolved_body), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = {
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { runCatching { AppGraph.syncManager.completeMerge(spec) } }
                                r.onSuccess { startSync() }.onFailure { actionError = it.message ?: it.toString() }
                                refresh()
                            }
                        }) { Text(stringResource(R.string.detail_complete_merge_sync)) }
                        TextButton(onClick = { confirmAbort = true }) { Text(stringResource(R.string.detail_abort_merge)) }
                    }
                }
            }

            actionError?.let { ErrorCard(stringResource(R.string.detail_action_failed), it) }

            // 残留状态
            inspectError?.let { ErrorCard(stringResource(R.string.detail_inspect_failed), it) }
            info?.let { i ->
                if (canReclone) {
                    if (!state.isBusy) {
                        ErrorCard(stringResource(R.string.detail_dir_missing), stringResource(R.string.detail_reclone_body, spec.localPath)) {
                            Button(onClick = {
                                requestNotifications()
                                if (AppGraph.syncManager.enqueueReclone(repoId)) SyncService.start(context)
                            }) { Text(stringResource(R.string.detail_reclone_action)) }
                        }
                    }
                } else if (!i.isRepository) {
                    ErrorCard(stringResource(R.string.detail_not_repo), spec.localPath)
                }
                val fixable = i.residues.filter { it != Residue.MERGE_WITH_CONFLICTS && it != Residue.MERGE_RESOLVED_UNCOMMITTED }
                if (fixable.isNotEmpty()) {
                    InfoCard(stringResource(R.string.detail_residue_title)) {
                        fixable.forEach { r ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(residueText(r)), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                if (r == Residue.STALE_INDEX_LOCK) {
                                    TextButton(
                                        onClick = {
                                            scope.launch { withContext(Dispatchers.IO) { runCatching { AppGraph.syncManager.removeIndexLock(spec) } }; refresh() }
                                        },
                                        enabled = !state.isBusy,
                                    ) { Text(stringResource(R.string.residue_index_lock_fix)) }
                                }
                            }
                        }
                    }
                }
            }

            // 认证问题（令牌 / 密钥缺失或不可用）
            credentialProblem(spec, tokenStatus, keyState)?.let { problem ->
                ErrorCard(stringResource(R.string.detail_auth_problem_title), stringResource(problem)) {
                    TextButton(onClick = onOpenRepoSettings) { Text(stringResource(R.string.detail_open_repo_settings)) }
                }
            }

            // 主操作
            Button(
                onClick = ::startSync,
                enabled = !state.isBusy && info?.isRepository == true,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_sync_now)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onOpenHistory, enabled = info?.isRepository == true, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.detail_history))
                }
                OutlinedButton(onClick = onOpenLogs, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.detail_logs)) }
            }
            OutlinedButton(onClick = onOpenRepoSettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.detail_open_repo_settings))
            }
        }
    }

    if (confirmAbort) {
        ConfirmDialog(
            title = stringResource(R.string.detail_abort_merge),
            text = stringResource(R.string.detail_abort_confirm),
            confirmText = stringResource(R.string.detail_abort_merge),
            destructive = true,
            onConfirm = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { AppGraph.syncManager.abortMerge(spec) } }
                    r.onFailure { actionError = it.message ?: it.toString() }
                    refresh()
                }
            },
            onDismiss = { confirmAbort = false },
        )
    }
}

@Composable
private fun HeaderCard(spec: RepoSpec, info: RepoInspector.RepoInfo?) {
    val context = LocalContext.current
    val keyState by AppGraph.sshKeys.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(spec.localPath, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.detail_branch_remote, info?.branch ?: spec.branch, RemoteUrl.redact(spec.remoteUrl)), style = MaterialTheme.typography.bodySmall)
                Text(authLine(spec, keyState), style = MaterialTheme.typography.bodySmall)
                if (info != null && info.isRepository) {
                    val total = info.modified + info.untracked + info.missing
                    Text(
                        if (total == 0) {
                            stringResource(R.string.detail_no_local_changes)
                        } else {
                            stringResource(R.string.detail_local_changes, info.modified, info.untracked, info.missing)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        TextButton(onClick = { copyToClipboard(context, "path", spec.localPath) }, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.detail_path_copy))
        }
    }
}

@Composable
private fun authLine(spec: RepoSpec, keyState: SshKeyStore.State): String = when (val a = spec.auth) {
    is AuthSpec.HttpsToken -> stringResource(R.string.detail_auth_https, a.username)
    is AuthSpec.SshKey -> {
        val key = (keyState as? SshKeyStore.State.Loaded)?.find(a.keyId)
        stringResource(R.string.detail_auth_ssh, key?.meta?.name ?: "?")
    }
    AuthSpec.None -> stringResource(R.string.detail_auth_none)
}

/** 认证配置有问题时返回说明文案的资源 id。 */
private fun credentialProblem(spec: RepoSpec, tokenStatus: KeystoreSecretStore.Status?, keyState: SshKeyStore.State): Int? {
    val auth = spec.auth
    return when {
        auth is AuthSpec.HttpsToken -> when (tokenStatus) {
            KeystoreSecretStore.Status.MISSING -> R.string.detail_auth_token_missing
            KeystoreSecretStore.Status.UNREADABLE -> R.string.detail_auth_token_unreadable
            else -> null
        }
        auth is AuthSpec.SshKey -> {
            val loaded = keyState as? SshKeyStore.State.Loaded ?: return null
            val key = loaded.find(auth.keyId)
            when {
                key == null -> R.string.detail_auth_key_missing
                key.unreadable -> R.string.detail_auth_key_unreadable
                else -> null
            }
        }
        CredentialsFactory.usesSsh(spec.remoteUrl) -> R.string.detail_auth_key_not_selected
        RemoteUrl.parse(spec.remoteUrl).kind == RemoteUrl.Kind.HTTPS -> R.string.detail_auth_token_missing
        else -> null
    }
}

/** 进程重启后，用 status.json 里的上次结果显示卡片。 */
@Composable
private fun LastStatusCard(status: RepoStatus, proxyEnabled: Boolean, onOpenLogs: () -> Unit) {
    val context = LocalContext.current
    if (status.result == RepoStatus.FAILED) {
        val kind = Texts.failureKindOf(status.failureKind)
        val stage = Texts.stageOf(status.stage)
        FailureCard(
            title = stringResource(
                R.string.detail_last_failed_title,
                formatFullTime(LocalContext.current, status.lastSyncAt),
                Texts.failureTitle(context, kind),
            ) + (stage?.let { " · " + Texts.stage(context, it) } ?: ""),
            kind = kind,
            technical = status.message,
            proxyEnabled = proxyEnabled,
        ) {
            TextButton(onClick = onOpenLogs) { Text(stringResource(R.string.action_view_logs)) }
        }
    } else {
        InfoCard(stringResource(R.string.detail_last_result, formatFullTime(LocalContext.current, status.lastSyncAt))) {
            Text(Texts.statusLine(context, status), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun residueText(r: Residue): Int = when (r) {
    Residue.STALE_INDEX_LOCK -> R.string.residue_index_lock
    Residue.MERGE_WITH_CONFLICTS -> R.string.residue_merge_conflicts
    Residue.MERGE_RESOLVED_UNCOMMITTED -> R.string.residue_merge_resolved
    Residue.UNSUPPORTED_STATE -> R.string.residue_unsupported
    Residue.DETACHED_HEAD -> R.string.residue_detached
    Residue.MISSING_REMOTE -> R.string.residue_missing_remote
    Residue.UNBORN_BRANCH -> R.string.residue_unborn
}
