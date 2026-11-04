package com.mountriv.vaultsync.ui.screens.repolist

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mountriv.vaultsync.AppGraph
import com.mountriv.vaultsync.BuildConfig
import com.mountriv.vaultsync.R
import com.mountriv.vaultsync.data.RepoStatus
import com.mountriv.vaultsync.gitcore.model.RepoSpec
import com.mountriv.vaultsync.platform.StoragePermissions
import com.mountriv.vaultsync.sync.JobType
import com.mountriv.vaultsync.sync.RepoSyncState
import com.mountriv.vaultsync.sync.SyncService
import com.mountriv.vaultsync.ui.ChipTone
import com.mountriv.vaultsync.ui.ErrorCard
import com.mountriv.vaultsync.ui.StatusChip
import com.mountriv.vaultsync.ui.Texts
import com.mountriv.vaultsync.ui.formatTime
import com.mountriv.vaultsync.ui.rememberNotificationPermissionRequest
import com.mountriv.vaultsync.ui.shortPath

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoListScreen(
    onAddRepo: () -> Unit,
    onOpenRepo: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenCrashReport: (String) -> Unit,
) {
    val context = LocalContext.current
    val repos by AppGraph.repos.state.collectAsStateWithLifecycle()
    val statuses by AppGraph.statuses.state.collectAsStateWithLifecycle()
    val states by AppGraph.syncManager.states.collectAsStateWithLifecycle()
    val settings by AppGraph.settings.state.collectAsStateWithLifecycle()
    val reposError by AppGraph.repos.loadError.collectAsStateWithLifecycle()
    val settingsError by AppGraph.settings.loadError.collectAsStateWithLifecycle()
    val statusError by AppGraph.statuses.loadError.collectAsStateWithLifecycle()

    var granted by remember { mutableStateOf(StoragePermissions.isGranted(context)) }
    var crash by remember { mutableStateOf(AppGraph.crashes.unseen().firstOrNull()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = StoragePermissions.isGranted(context) }
    val legacyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = StoragePermissions.isGranted(context)
    }
    val requestNotifications = rememberNotificationPermissionRequest()

    fun startSync(spec: RepoSpec) {
        requestNotifications()
        if (AppGraph.syncManager.enqueueSync(spec.id)) SyncService.start(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_title)) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.home_settings))
                    }
                },
            )
        },
        floatingActionButton = {
            if (granted) {
                FloatingActionButton(onClick = onAddRepo) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.home_add_repo))
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            crash?.let { report ->
                item {
                    ErrorCard(
                        title = stringResource(R.string.home_crash_title),
                        body = stringResource(R.string.home_crash_body),
                    ) {
                        TextButton(onClick = { AppGraph.crashes.markSeen(); crash = null; onOpenCrashReport(report.file.name) }) {
                            Text(stringResource(R.string.home_crash_view))
                        }
                        TextButton(onClick = { AppGraph.crashes.markSeen(); crash = null }) { Text(stringResource(R.string.action_dismiss)) }
                    }
                }
            }
            val configErrors = listOfNotNull(reposError, settingsError, statusError)
            if (configErrors.isNotEmpty()) {
                item {
                    ErrorCard(
                        title = stringResource(R.string.home_config_error_title),
                        body = stringResource(R.string.home_config_error_body, configErrors.joinToString("\n")),
                    )
                }
            }
            if (!granted) {
                item {
                    PermissionCard(
                        onRequest = {
                            if (StoragePermissions.usesAllFilesAccessSetting) {
                                try {
                                    context.startActivity(StoragePermissions.allFilesAccessIntent(context))
                                } catch (_: Exception) {
                                    StoragePermissions.allFilesAccessFallbackIntent()?.let(context::startActivity)
                                }
                            } else {
                                legacyLauncher.launch(StoragePermissions.legacyRuntimePermissions)
                            }
                        },
                    )
                }
            }
            if (!settings.hasIdentity && repos.isNotEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.home_identity_missing), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onOpenSettings, modifier = Modifier.align(Alignment.End)) {
                                Text(stringResource(R.string.action_go_settings))
                            }
                        }
                    }
                }
            }
            if (repos.isEmpty()) {
                item { EmptyState(granted, onAddRepo) }
            }
            items(repos, key = { it.id }) { spec ->
                RepoCard(
                    spec = spec,
                    status = statuses[spec.id],
                    state = states[spec.id] ?: RepoSyncState.Idle,
                    canSync = granted,
                    onClick = { onOpenRepo(spec.id) },
                    onSync = { startSync(spec) },
                )
            }
            item {
                Text(
                    text = stringResource(R.string.env_info, BuildConfig.VERSION_NAME, Build.VERSION.RELEASE),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RepoCard(
    spec: RepoSpec,
    status: RepoStatus?,
    state: RepoSyncState,
    canSync: Boolean,
    onClick: () -> Unit,
    onSync: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(spec.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        shortPath(spec.localPath) + " · " + spec.branch,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                val (label, tone) = chipFor(state, status)
                StatusChip(label, tone)
            }
            if (state is RepoSyncState.Running) {
                Text(
                    Texts.progressLine(context, state.type, state.stage, state.task, state.completed, state.total),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(Texts.meterLine(context, state.type, state.stage, state.meter), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.total > 0) {
                    LinearProgressIndicator(progress = { state.completed.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = status?.let { stringResource(R.string.last_sync_at, formatTime(LocalContext.current, it.lastSyncAt)) } ?: stringResource(R.string.status_never),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onSync, enabled = canSync && !state.isBusy) {
                    Text(stringResource(R.string.action_sync))
                }
            }
        }
    }
}

@Composable
private fun chipFor(state: RepoSyncState, status: RepoStatus?): Pair<String, ChipTone> = when (state) {
    is RepoSyncState.Queued -> stringResource(R.string.status_queued) to ChipTone.NEUTRAL
    is RepoSyncState.Running ->
        stringResource(
            when (state.type) {
                JobType.MAINTAIN -> R.string.status_maintaining
                JobType.RECLONE -> R.string.status_cloning
                else -> R.string.status_syncing
            },
        ) to ChipTone.NEUTRAL
    is RepoSyncState.Conflicts -> stringResource(R.string.status_conflicts) to ChipTone.WARN
    is RepoSyncState.Error -> stringResource(R.string.status_error) to ChipTone.ERROR
    is RepoSyncState.Done -> stringResource(R.string.status_synced) to ChipTone.OK
    is RepoSyncState.Cancelled -> stringResource(R.string.status_cancelled) to ChipTone.NEUTRAL
    RepoSyncState.Idle -> when (status?.result) {
        RepoStatus.SUCCESS -> stringResource(R.string.status_synced) to ChipTone.OK
        RepoStatus.CONFLICTS -> stringResource(R.string.status_conflicts) to ChipTone.WARN
        RepoStatus.FAILED -> stringResource(R.string.status_error) to ChipTone.ERROR
        RepoStatus.CANCELLED -> stringResource(R.string.status_cancelled) to ChipTone.NEUTRAL
        else -> stringResource(R.string.status_never) to ChipTone.NEUTRAL
    }
}

@Composable
private fun PermissionCard(onRequest: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(
                    if (StoragePermissions.usesAllFilesAccessSetting) R.string.perm_body_all_files else R.string.perm_body_legacy,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRequest, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(if (StoragePermissions.usesAllFilesAccessSetting) R.string.perm_open_settings else R.string.perm_grant))
            }
        }
    }
}

@Composable
private fun EmptyState(granted: Boolean, onAddRepo: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onAddRepo, enabled = granted) { Text(stringResource(R.string.home_add_repo)) }
    }
}
