package com.mountriv.vaultsync.ui.screens.conflicts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mountriv.vaultsync.AppGraph
import com.mountriv.vaultsync.R
import com.mountriv.vaultsync.gitcore.merge.ConflictKind
import com.mountriv.vaultsync.gitcore.merge.Side
import com.mountriv.vaultsync.ui.ChipTone
import com.mountriv.vaultsync.ui.ConfirmDialog
import com.mountriv.vaultsync.ui.ErrorCard
import com.mountriv.vaultsync.ui.InfoCard
import com.mountriv.vaultsync.ui.StatusChip
import org.eclipse.jgit.lib.RepositoryState

/** 冲突文件列表（docs/UI.md §5）：路径、类型、已解决标记、批量"全部用本地/远端"、全部解决后"完成合并并推送"。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictsScreen(repoId: String, onBack: () -> Unit, onOpenFile: (String) -> Unit, onMergeCompleted: () -> Unit) {
    val vm: ConflictsViewModel = viewModel(key = "conflicts:$repoId") { ConflictsViewModel(repoId) }
    val states by AppGraph.syncManager.states.collectAsStateWithLifecycle()
    val syncing = states[repoId]?.isBusy == true
    var menuOpen by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf<Side?>(null) }

    // 进入页面与从文件页返回时都重新读 index；同步结束时也刷新
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (!syncing) vm.refresh() }
    var wasSyncing by remember { mutableStateOf(false) }
    LaunchedEffect(syncing) {
        if (syncing) wasSyncing = true else if (wasSyncing) { wasSyncing = false; vm.refresh() }
    }

    val spec = vm.spec
    if (spec == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val list = vm.list
    val enabled = !vm.busy && !syncing && !vm.loading

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.conflicts_title))
                        Text(spec.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
                actions = {
                    val hasUnresolved = list?.unresolved?.isNotEmpty() == true
                    IconButton(onClick = { menuOpen = true }, enabled = enabled && hasUnresolved) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.conflicts_menu))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.conflicts_all_ours)) },
                            onClick = { menuOpen = false; confirmAll = Side.OURS },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.conflicts_all_theirs)) },
                            onClick = { menuOpen = false; confirmAll = Side.THEIRS },
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (list != null && list.unresolved.isEmpty() && list.state == RepositoryState.MERGING_RESOLVED) {
                Surface(tonalElevation = 3.dp) {
                    // edge-to-edge 下 Scaffold 不替 bottomBar 处理导航栏内边距，自己加（否则被三键导航栏遮住）
                    Column(Modifier.navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.conflicts_all_resolved_hint), style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = { vm.completeMergeAndSync(onMergeCompleted) },
                            enabled = enabled,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.conflicts_complete_merge)) }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    vm.error?.let { ErrorCard(stringResource(R.string.conflicts_action_failed), it) }
                    when {
                        syncing -> InfoCard(stringResource(R.string.status_syncing)) {
                            Text(stringResource(R.string.conflicts_syncing_hint), style = MaterialTheme.typography.bodyMedium)
                        }
                        vm.loading && list == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(R.string.conflicts_loading))
                        }
                        list != null && list.state != RepositoryState.MERGING && list.state != RepositoryState.MERGING_RESOLVED ->
                            InfoCard(stringResource(R.string.conflicts_none_title)) {
                                Text(stringResource(R.string.conflicts_none_body), style = MaterialTheme.typography.bodyMedium)
                            }
                        list != null && list.unresolved.isNotEmpty() -> Text(
                            pluralStringResource(R.plurals.conflicts_intro, list.unresolved.size, list.unresolved.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (list != null) {
                items(list.unresolved, key = { "u:" + it.path }) { s ->
                    ConflictRow(
                        path = s.path,
                        kindText = stringResource(kindText(s.kind, s.isBinary)),
                        resolved = false,
                        enabled = enabled,
                        onClick = { onOpenFile(s.path) },
                    )
                    HorizontalDivider()
                }
                items(list.resolved, key = { "r:$it" }) { p ->
                    ConflictRow(path = p, kindText = stringResource(R.string.conflicts_kind_resolved), resolved = true, enabled = false, onClick = {})
                    HorizontalDivider()
                }
            }
        }
    }

    confirmAll?.let { side ->
        ConfirmDialog(
            text = (list?.unresolved?.size ?: 0).let { n ->
                pluralStringResource(if (side == Side.OURS) R.plurals.conflicts_all_ours_confirm else R.plurals.conflicts_all_theirs_confirm, n, n)
            },
            confirmText = stringResource(if (side == Side.OURS) R.string.conflicts_all_ours else R.string.conflicts_all_theirs),
            destructive = true,
            onConfirm = { vm.resolveAll(side) },
            onDismiss = { confirmAll = null },
        )
    }
}

@Composable
private fun ConflictRow(path: String, kindText: String, resolved: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled && !resolved, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (resolved) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                path,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                color = if (resolved) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            // 标签放在路径下面：路径可能很长，英文标签也长，放在同一行会把路径挤得折行
            Spacer(Modifier.height(4.dp))
            StatusChip(kindText, if (resolved) ChipTone.OK else ChipTone.WARN)
        }
        if (!resolved) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 冲突类型的文案 id。 */
fun kindText(kind: ConflictKind, isBinary: Boolean): Int = when {
    isBinary -> R.string.conflicts_kind_binary
    else -> when (kind) {
        ConflictKind.BOTH_MODIFIED -> R.string.conflicts_kind_both_modified
        ConflictKind.BOTH_ADDED -> R.string.conflicts_kind_both_added
        ConflictKind.DELETED_BY_US -> R.string.conflicts_kind_deleted_by_us
        ConflictKind.DELETED_BY_THEM -> R.string.conflicts_kind_deleted_by_them
        ConflictKind.ADDED_BY_US -> R.string.conflicts_kind_added_by_us
        ConflictKind.ADDED_BY_THEM -> R.string.conflicts_kind_added_by_them
        ConflictKind.BOTH_DELETED -> R.string.conflicts_kind_both_deleted
    }
}
