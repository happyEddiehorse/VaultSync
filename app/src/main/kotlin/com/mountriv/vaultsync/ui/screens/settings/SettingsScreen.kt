package com.mountriv.vaultsync.ui.screens.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mountriv.vaultsync.AppGraph
import com.mountriv.vaultsync.BuildConfig
import com.mountriv.vaultsync.R
import com.mountriv.vaultsync.gitcore.auth.RemoteUrl
import com.mountriv.vaultsync.gitcore.store.BackupException
import com.mountriv.vaultsync.platform.AppLocale
import com.mountriv.vaultsync.platform.ConfigBackup
import com.mountriv.vaultsync.ui.DiscardChangesDialog
import com.mountriv.vaultsync.ui.ErrorCard
import com.mountriv.vaultsync.ui.InfoCard
import com.mountriv.vaultsync.ui.UnsavedChangesBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 全局设置（docs/UI.md §8）：身份、提交、网络代理、日志、SSH 密钥、已知主机、语言、通知、备份与恢复、帮助、关于。
 * 前四组是表单，修改用底部的未保存修改栏保存（§9.1，D-029）；其余卡片都是即时操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenCrashReport: (String) -> Unit, onLanguageChanged: () -> Unit) {
    val vm: SettingsViewModel = viewModel()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val savedText = stringResource(R.string.settings_saved)
    var confirmLeave by remember { mutableStateOf(false) }

    val saved by AppGraph.settings.state.collectAsStateWithLifecycle()
    val dirty = vm.isDirty(saved)
    val proxyInvalid = vm.proxyInvalid

    // 两个提示都是提示完再清标记：先清会让 key 变化，正在显示的提示随协程一起被取消。
    // 恢复出的语言与当前不同时界面会重建，标记还在，重建后用新语言再提示一次。
    LaunchedEffect(vm.saved) {
        if (vm.saved) {
            snackbar.showSnackbar(savedText)
            vm.consumeSaved()
        }
    }

    val restored = vm.restored
    val restoredText = restored?.let {
        listOfNotNull(
            stringResource(R.string.backup_restored, it.repos, it.keys, it.tokens),
            if (it.secretsSkipped) stringResource(R.string.backup_restored_secrets_skipped) else null,
            if (it.missingFolders > 0) pluralStringResource(R.plurals.backup_restored_missing_folders, it.missingFolders, it.missingFolders) else null,
        ).joinToString("\n")
    }
    LaunchedEffect(restored) {
        if (restoredText != null) {
            snackbar.showSnackbar(restoredText)
            vm.restored = null
        }
    }

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    // 有未保存的修改时离开要确认；前进到别的页面（崩溃报告）不算离开
    fun requestLeave() {
        if (dirty) confirmLeave = true else onBack()
    }
    BackHandler(enabled = dirty) { requestLeave() }

    Scaffold(
        // 键盘弹出时整页（连同底部栏）缩到键盘上方，正在输入的框保持可见
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = ::requestLeave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) }
                },
            )
        },
        bottomBar = {
            UnsavedChangesBar(
                visible = dirty,
                blockedReason = if (proxyInvalid) stringResource(R.string.settings_reason_proxy) else null,
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
            InfoCard(stringResource(R.string.settings_identity)) {
                Text(stringResource(R.string.settings_identity_body), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = vm.authorName,
                    onValueChange = { vm.authorName = it },
                    label = { Text(stringResource(R.string.identity_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = vm.authorEmail,
                    onValueChange = { vm.authorEmail = it },
                    label = { Text(stringResource(R.string.identity_email_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
            }
            InfoCard(stringResource(R.string.settings_commit)) {
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
            InfoCard(stringResource(R.string.settings_network)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.settings_proxy_enabled), modifier = Modifier.weight(1f))
                    Switch(checked = vm.proxyEnabled, onCheckedChange = { vm.proxyEnabled = it })
                }
                OutlinedTextField(
                    value = vm.proxyHost,
                    onValueChange = { vm.proxyHost = it },
                    label = { Text(stringResource(R.string.settings_proxy_host)) },
                    singleLine = true,
                    enabled = vm.proxyEnabled,
                    isError = proxyInvalid && vm.proxyHost.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("127.0.0.1") },
                )
                OutlinedTextField(
                    value = vm.proxyPort,
                    onValueChange = { vm.proxyPort = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.settings_proxy_port)) },
                    singleLine = true,
                    enabled = vm.proxyEnabled,
                    isError = proxyInvalid && (vm.proxyPort.toIntOrNull() ?: 0) !in 1..65535,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Text(stringResource(R.string.settings_proxy_hint), style = MaterialTheme.typography.bodySmall)
            }
            InfoCard(stringResource(R.string.settings_logs)) {
                OutlinedTextField(
                    value = vm.logKeep,
                    onValueChange = { vm.logKeep = it.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(R.string.settings_log_keep)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_debug_logs))
                        Text(stringResource(R.string.settings_debug_logs_hint), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = vm.debugLogs, onCheckedChange = { vm.debugLogs = it })
                }
            }

            SshKeyListCard(showMessage = ::message)
            KnownHostsCard()
            LanguageCard(onChanged = onLanguageChanged)
            NotificationsCard()
            // 从备份恢复后，表单显示恢复出来的设置；恢复出的语言与当前不同就按新语言重建界面
            BackupCard(
                showMessage = ::message,
                onRestored = { res ->
                    vm.load(AppGraph.settings.state.value)
                    vm.restored = res
                    onLanguageChanged()
                },
            )
            DiagnosticsCard(showMessage = ::message, onOpenCrashReport = onOpenCrashReport)
            InfoCard(stringResource(R.string.settings_help)) {
                Text(stringResource(R.string.settings_help_background), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.settings_help_vault_location), style = MaterialTheme.typography.bodySmall)
            }
            InfoCard(stringResource(R.string.settings_about)) {
                Text(
                    stringResource(R.string.settings_about_body, BuildConfig.VERSION_NAME, BuildConfig.JGIT_VERSION, Build.VERSION.RELEASE),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (confirmLeave) {
        DiscardChangesDialog(onDiscard = onBack, onDismiss = { confirmLeave = false })
    }
}

/**
 * 界面语言（D-030，docs/UI.md §8）：跟随系统 / 简体中文 / English，语言名称用各自的文字。选了立即生效，不经过保存栏；
 * [onChanged] 让界面按新语言重建，表单里没保存的修改在 ViewModel 里，不受影响。
 */
@Composable
private fun LanguageCard(onChanged: () -> Unit) {
    val settings by AppGraph.settings.state.collectAsStateWithLifecycle()
    val current = settings.language
    var choosing by remember { mutableStateOf(false) }
    val options = listOf(AppLocale.SYSTEM to stringResource(R.string.settings_language_system)) +
        AppLocale.languages.map { it.tag to stringResource(it.nameRes) }

    InfoCard(stringResource(R.string.settings_language)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { choosing = true },
        ) {
            Text(
                options.firstOrNull { it.first == current }?.second ?: options.first().second,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        Text(stringResource(R.string.settings_language_hint), style = MaterialTheme.typography.bodySmall)
    }

    if (choosing) {
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    options.forEach { (tag, name) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .selectable(
                                    selected = tag == current,
                                    role = Role.RadioButton,
                                    onClick = {
                                        choosing = false
                                        if (tag != current) {
                                            AppGraph.settings.update { it.copy(language = tag) }
                                            onChanged()
                                        }
                                    },
                                ),
                        ) {
                            RadioButton(selected = tag == current, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosing = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** 通知是否可用。只陈述状态并给出系统设置入口，不针对任何机型。 */
@Composable
private fun NotificationsCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { enabled = NotificationManagerCompat.from(context).areNotificationsEnabled() }
    InfoCard(stringResource(R.string.settings_notifications)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(if (enabled) R.string.settings_notifications_on else R.string.settings_notifications_off),
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                runCatching { context.startActivity(intent) }.onFailure {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                }
            }) { Text(stringResource(if (enabled) R.string.settings_notifications_manage else R.string.settings_notifications_enable)) }
        }
        Text(stringResource(R.string.settings_notifications_body), style = MaterialTheme.typography.bodySmall)
    }
}

/** 诊断：把全部日志与崩溃报告打包成 zip，由用户选位置保存（与导出配置一样）；有崩溃报告时可直接查看最近一份。 */
@Composable
private fun DiagnosticsCard(showMessage: (String) -> Unit, onOpenCrashReport: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val crashes = remember { AppGraph.crashes.all() }
    val savedText = stringResource(R.string.diagnostics_exported)
    val failedText = stringResource(R.string.diagnostics_export_failed)

    val createZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, "export").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val zip = File(dir, "vaultsync-logs.zip")
                    val info = buildString {
                        appendLine("VaultSync ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · JGit ${BuildConfig.JGIT_VERSION}")
                        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                        AppGraph.repos.state.value.forEach { appendLine("repo ${it.id} '${it.name}' ${RemoteUrl.redact(it.remoteUrl)} ${it.localPath}") }
                    }
                    try {
                        AppGraph.logs.zipAll(zip, info, AppGraph.crashes.all().map { "crash/${it.file.name}" to it.file })
                        context.contentResolver.openOutputStream(uri, "w")?.use { out -> zip.inputStream().use { it.copyTo(out) } }
                            ?: error("cannot open $uri")
                    } finally {
                        zip.delete()
                    }
                }
            }
            busy = false
            r.onSuccess { showMessage(savedText) }.onFailure {
                AppGraph.logger.error("log export failed", it)
                showMessage(failedText)
            }
        }
    }

    InfoCard(stringResource(R.string.settings_diagnostics)) {
        Text(stringResource(R.string.settings_diagnostics_body), style = MaterialTheme.typography.bodySmall)
        Button(
            onClick = {
                val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                createZip.launch("vaultsync-logs-$stamp.zip")
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.diagnostics_export)) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        crashes.firstOrNull()?.let { latest ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    pluralStringResource(R.plurals.diagnostics_crash_count, crashes.size, crashes.size),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onOpenCrashReport(latest.file.name) }) { Text(stringResource(R.string.diagnostics_view_latest_crash)) }
            }
        }
    }
}

