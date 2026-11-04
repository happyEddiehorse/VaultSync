package com.mountriv.vaultsync.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mountriv.vaultsync.AppGraph
import com.mountriv.vaultsync.R
import com.mountriv.vaultsync.gitcore.auth.SshKeys
import com.mountriv.vaultsync.platform.SshKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 生成 / 导入密钥的共用逻辑（设置页、添加向导、仓库设置都用）：在 IO 线程操作 [SshKeyStore]，
 * 把导入失败的原因翻译成文案。对话框的开关状态也放在这里，调用方只需渲染 [Dialogs]。
 */
class SshKeyActions(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
    var showGenerate by mutableStateOf(false)
    var showImport by mutableStateOf(false)

    fun generate(name: String, onDone: (SshKeyStore.KeyMeta) -> Unit) {
        busy = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { AppGraph.sshKeys.generate(name, SshKeyStore.defaultComment(Build.MODEL)) }
            }
            busy = false
            r.onSuccess { showGenerate = false; onDone(it) }.onFailure { error = it.message ?: it.toString() }
        }
    }

    fun import(name: String, text: String, passphrase: String, messages: ImportMessages, onDone: (SshKeyStore.KeyMeta) -> Unit) {
        busy = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { AppGraph.sshKeys.import(name, text, passphrase.takeIf { it.isNotEmpty() }?.toCharArray()) }
            }
            busy = false
            r.onSuccess { showImport = false; onDone(it) }.onFailure { e ->
                error = when ((e as? SshKeys.SshKeyException)?.reason) {
                    SshKeys.SshKeyException.Reason.INVALID -> messages.invalid
                    SshKeys.SshKeyException.Reason.PASSPHRASE_REQUIRED -> messages.passphraseRequired
                    SshKeys.SshKeyException.Reason.WRONG_PASSPHRASE -> messages.wrongPassphrase
                    SshKeys.SshKeyException.Reason.UNSUPPORTED -> messages.unsupported.format(e.message ?: "")
                    null -> e.message ?: e.toString()
                }
            }
        }
    }

    data class ImportMessages(val invalid: String, val passphraseRequired: String, val wrongPassphrase: String, val unsupported: String)

    /** 生成 / 导入对话框。[onCreated] 在新密钥写入后调用（例如让选择器选中它）。 */
    @Composable
    fun Dialogs(onCreated: (SshKeyStore.KeyMeta) -> Unit) {
        val keys by AppGraph.sshKeys.state.collectAsStateWithLifecycle()
        val count = (keys as? SshKeyStore.State.Loaded)?.keys?.size ?: 0
        val suggested = stringResource(R.string.ssh_key_name_suggestion, count + 1)
        val messages = ImportMessages(
            invalid = stringResource(R.string.ssh_import_err_invalid),
            passphraseRequired = stringResource(R.string.ssh_import_err_passphrase_required),
            wrongPassphrase = stringResource(R.string.ssh_import_err_wrong_passphrase),
            unsupported = stringResource(R.string.ssh_import_err_unsupported),
        )
        if (showGenerate) {
            GenerateKeyDialog(
                suggestedName = suggested,
                busy = busy,
                error = error,
                onDismiss = { if (!busy) showGenerate = false },
                onGenerate = { name -> generate(name, onCreated) },
            )
        }
        if (showImport) {
            ImportKeyDialog(
                suggestedName = suggested,
                busy = busy,
                error = error,
                onDismiss = { if (!busy) showImport = false },
                onImport = { name, text, pass -> import(name, text, pass, messages, onCreated) },
            )
        }
    }
}

@Composable
fun rememberSshKeyActions(): SshKeyActions {
    val scope = rememberCoroutineScope()
    return remember { SshKeyActions(scope) }
}

/**
 * 仓库使用哪把 SSH 密钥（添加向导、仓库设置）：下拉选择已有密钥，或直接生成 / 导入新密钥（新密钥自动选中）。
 * 选中密钥后显示指纹与"复制公钥"。[selectedId] 为 null 时提示选择。
 */
@Composable
fun SshKeyPicker(selectedId: String?, onSelect: (String) -> Unit, onManageKeys: (() -> Unit)?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by AppGraph.sshKeys.state.collectAsStateWithLifecycle()
    val actions = rememberSshKeyActions()
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { runCatching { AppGraph.sshKeys.refresh() } } }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ssh_picker_title), style = MaterialTheme.typography.titleSmall)
        val loaded = state as? SshKeyStore.State.Loaded
        when {
            loaded == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            loaded.keys.isEmpty() -> {
                Text(stringResource(R.string.ssh_picker_empty), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { actions.error = null; actions.showGenerate = true }, enabled = !actions.busy) {
                        Text(stringResource(R.string.ssh_key_generate))
                    }
                    OutlinedButton(onClick = { actions.error = null; actions.showImport = true }, enabled = !actions.busy) {
                        Text(stringResource(R.string.ssh_key_import))
                    }
                }
            }
            else -> {
                val selected = loaded.find(selectedId)
                Box {
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { menuOpen = true },
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                if (selected != null) {
                                    Text(keyTitle(selected, loaded), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        "${selected.meta.keyType} · ${selected.meta.fingerprint}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                } else {
                                    Text(
                                        stringResource(if (selectedId == null) R.string.ssh_picker_choose else R.string.ssh_picker_deleted),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.ssh_picker_open))
                        }
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        loaded.keys.forEach { key ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(keyTitle(key, loaded))
                                        Text(
                                            "${key.meta.keyType} · ${key.meta.fingerprint.take(19)}…",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = { menuOpen = false; onSelect(key.id) },
                                enabled = !key.unreadable,
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ssh_picker_new_generate)) },
                            onClick = { menuOpen = false; actions.error = null; actions.showGenerate = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ssh_picker_new_import)) },
                            onClick = { menuOpen = false; actions.error = null; actions.showImport = true },
                        )
                    }
                }
                if (selected != null) {
                    if (selected.unreadable) {
                        Text(stringResource(R.string.ssh_key_unreadable), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(stringResource(R.string.ssh_picker_add_public_key), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selected != null && !selected.unreadable) {
                        OutlinedButton(onClick = { copyToClipboard(context, "ssh public key", selected.meta.publicKeyLine) }) {
                            Text(stringResource(R.string.ssh_key_copy_public))
                        }
                    }
                    if (onManageKeys != null) {
                        TextButton(onClick = onManageKeys) { Text(stringResource(R.string.ssh_picker_manage)) }
                    }
                }
            }
        }
        if (actions.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    actions.Dialogs(onCreated = { onSelect(it.id) })
}

/** "默认密钥 · 默认"这样的标题：默认密钥后面带标记。 */
@Composable
fun keyTitle(key: SshKeyStore.Key, loaded: SshKeyStore.State.Loaded): String =
    if (key.id == loaded.defaultId) stringResource(R.string.ssh_key_title_default, key.meta.name) else key.meta.name

@Composable
fun GenerateKeyDialog(suggestedName: String, busy: Boolean, error: String?, onDismiss: () -> Unit, onGenerate: (String) -> Unit) {
    var name by remember { mutableStateOf(suggestedName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ssh_generate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ssh_generate_body), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ssh_key_name_label)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onGenerate(name) }, enabled = name.isNotBlank() && !busy) { Text(stringResource(R.string.ssh_key_generate)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun ImportKeyDialog(
    suggestedName: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onImport: (name: String, text: String, passphrase: String) -> Unit,
) {
    var name by remember { mutableStateOf(suggestedName) }
    var text by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ssh_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ssh_import_key_hint), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.ssh_key_name_label)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.ssh_import_key_label)) },
                    minLines = 4,
                    maxLines = 8,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text(stringResource(R.string.ssh_import_passphrase_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                )
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(name, text, passphrase) }, enabled = text.isNotBlank() && name.isNotBlank() && !busy) {
                Text(stringResource(R.string.ssh_import_action))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}
