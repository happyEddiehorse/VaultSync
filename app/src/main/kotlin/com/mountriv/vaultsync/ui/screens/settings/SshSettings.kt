package com.mountriv.vaultsync.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mountriv.vaultsync.AppGraph
import com.mountriv.vaultsync.R
import com.mountriv.vaultsync.gitcore.auth.KnownHostEntry
import com.mountriv.vaultsync.gitcore.model.AuthSpec
import com.mountriv.vaultsync.platform.SshKeyStore
import com.mountriv.vaultsync.ui.ConfirmDialog
import com.mountriv.vaultsync.ui.InfoCard
import com.mountriv.vaultsync.ui.copyToClipboard
import com.mountriv.vaultsync.ui.formatFullTime
import com.mountriv.vaultsync.ui.keyTitle
import com.mountriv.vaultsync.ui.rememberSshKeyActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页的 SSH 密钥列表（docs/UI.md §8、D-023）：每把密钥显示名称、默认标记、类型 · 指纹、被几个库使用；
 * 菜单里可查看公钥、设为默认、重命名、删除；底部生成 / 导入。
 */
@Composable
fun SshKeyListCard(showMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by AppGraph.sshKeys.state.collectAsStateWithLifecycle()
    val repos by AppGraph.repos.state.collectAsStateWithLifecycle()
    val actions = rememberSshKeyActions()
    var viewKey by remember { mutableStateOf<SshKeyStore.Key?>(null) }
    var renameKey by remember { mutableStateOf<SshKeyStore.Key?>(null) }
    var deleteKey by remember { mutableStateOf<SshKeyStore.Key?>(null) }

    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { AppGraph.sshKeys.refresh() } } }

    val doneGenerated = stringResource(R.string.ssh_key_done_generated)
    val doneImported = stringResource(R.string.ssh_key_done_imported)
    val doneDeleted = stringResource(R.string.ssh_key_done_deleted)

    fun usersOf(id: String) = repos.filter { (it.auth as? AuthSpec.SshKey)?.keyId == id }

    fun io(block: () -> Unit, after: () -> Unit = {}) {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching(block) }
            after()
        }
    }

    InfoCard(stringResource(R.string.settings_ssh)) {
        Text(stringResource(R.string.ssh_list_intro), style = MaterialTheme.typography.bodySmall)
        val loaded = state as? SshKeyStore.State.Loaded
        when {
            loaded == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            loaded.keys.isEmpty() -> Text(stringResource(R.string.ssh_list_empty), style = MaterialTheme.typography.bodyMedium)
            else -> loaded.keys.forEachIndexed { i, key ->
                if (i > 0) HorizontalDivider()
                KeyRow(
                    key = key,
                    title = keyTitle(key, loaded),
                    users = usersOf(key.id).size,
                    isDefault = key.id == loaded.defaultId,
                    onCopy = { copyToClipboard(context, "ssh public key", key.meta.publicKeyLine) },
                    onView = { viewKey = key },
                    onSetDefault = { io({ AppGraph.sshKeys.setDefault(key.id) }) },
                    onRename = { renameKey = key },
                    onDelete = { deleteKey = key },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { actions.error = null; actions.showGenerate = true }, enabled = !actions.busy, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.ssh_key_generate))
            }
            OutlinedButton(onClick = { actions.error = null; actions.showImport = true }, enabled = !actions.busy, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.ssh_key_import))
            }
        }
    }

    actions.Dialogs(onCreated = { meta ->
        showMessage(if (meta.source == SshKeyStore.SOURCE_GENERATED) doneGenerated else doneImported)
    })

    viewKey?.let { key ->
        AlertDialog(
            onDismissRequest = { viewKey = null },
            title = { Text(key.meta.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${key.meta.keyType} · ${key.meta.fingerprint}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Text(
                                stringResource(
                                    if (key.meta.source == SshKeyStore.SOURCE_GENERATED) R.string.ssh_key_source_generated else R.string.ssh_key_source_imported,
                                    formatFullTime(LocalContext.current, key.meta.createdAt),
                                ) + (key.meta.comment.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(stringResource(R.string.ssh_key_public_hint), style = MaterialTheme.typography.bodySmall)
                            Text(key.meta.publicKeyLine, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { copyToClipboard(context, "ssh public key", key.meta.publicKeyLine) }) {
                    Text(stringResource(R.string.ssh_key_copy_public))
                }
            },
            dismissButton = { TextButton(onClick = { viewKey = null }) { Text(stringResource(R.string.action_close)) } },
        )
    }

    renameKey?.let { key ->
        var name by remember(key.id) { mutableStateOf(key.meta.name) }
        AlertDialog(
            onDismissRequest = { renameKey = null },
            title = { Text(stringResource(R.string.ssh_key_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ssh_key_name_label)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = { renameKey = null; io({ AppGraph.sshKeys.rename(key.id, name) }) }, enabled = name.isNotBlank()) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = { TextButton(onClick = { renameKey = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    deleteKey?.let { key ->
        val users = usersOf(key.id)
        ConfirmDialog(
            title = stringResource(R.string.ssh_key_delete_title, key.meta.name),
            text = if (users.isEmpty()) {
                stringResource(R.string.ssh_key_delete_unused)
            } else {
                pluralStringResource(R.plurals.ssh_key_delete_used, users.size, users.size, users.joinToString(stringResource(R.string.list_separator)) { it.name })
            },
            confirmText = stringResource(R.string.ssh_key_delete),
            destructive = true,
            onConfirm = { io({ AppGraph.sshKeys.delete(key.id) }) { showMessage(doneDeleted) } },
            onDismiss = { deleteKey = null },
        )
    }
}

@Composable
private fun KeyRow(
    key: SshKeyStore.Key,
    title: String,
    users: Int,
    isDefault: Boolean,
    onCopy: () -> Unit,
    onView: () -> Unit,
    onSetDefault: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${key.meta.keyType} · ${key.meta.fingerprint}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (key.unreadable) {
                Text(stringResource(R.string.ssh_key_unreadable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else {
                Text(
                    if (users == 0) stringResource(R.string.ssh_key_unused) else pluralStringResource(R.plurals.ssh_key_used_by, users, users),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!key.unreadable) {
            TextButton(onClick = onCopy) { Text(stringResource(R.string.ssh_key_copy_public_short)) }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (!key.unreadable) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.ssh_key_view_public)) }, onClick = { menuOpen = false; onView() })
                }
                if (!isDefault && !key.unreadable) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.ssh_key_set_default)) }, onClick = { menuOpen = false; onSetDefault() })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.ssh_key_rename)) }, onClick = { menuOpen = false; onRename() })
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ssh_key_delete), color = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; onDelete() },
                )
            }
        }
    }
}

/** 设置页的"已知主机"卡片：列出 known_hosts 记录，可"忘记"。 */
@Composable
fun KnownHostsCard() {
    val scope = rememberCoroutineScope()
    var hosts by remember { mutableStateOf<List<KnownHostEntry>?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var confirmForget by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refreshKey) {
        hosts = withContext(Dispatchers.IO) { runCatching { AppGraph.syncManager.knownHosts() }.getOrElse { emptyList() } }
    }

    InfoCard(stringResource(R.string.known_hosts_title)) {
        Text(stringResource(R.string.known_hosts_body), style = MaterialTheme.typography.bodySmall)
        val list = hosts
        when {
            list == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            list.isEmpty() -> Text(stringResource(R.string.known_hosts_empty), style = MaterialTheme.typography.bodyMedium)
            else -> list.groupBy { it.host }.forEach { (host, entries) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Column {
                            Text(host, style = MaterialTheme.typography.bodyMedium)
                            entries.forEach { e ->
                                Text(
                                    "${e.type} · ${e.fingerprint}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    TextButton(onClick = { confirmForget = host }) { Text(stringResource(R.string.known_hosts_forget)) }
                }
            }
        }
    }

    confirmForget?.let { host ->
        ConfirmDialog(
            text = stringResource(R.string.known_hosts_forget_confirm, host),
            confirmText = stringResource(R.string.known_hosts_forget),
            destructive = true,
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { runCatching { AppGraph.syncManager.forgetHost(host) } }
                    refreshKey++
                }
            },
            onDismiss = { confirmForget = null },
        )
    }
}
