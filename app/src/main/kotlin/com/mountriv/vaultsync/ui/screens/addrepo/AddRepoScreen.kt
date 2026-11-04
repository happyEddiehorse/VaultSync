package com.mountriv.vaultsync.ui.screens.addrepo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mountriv.vaultsync.AppGraph
import com.mountriv.vaultsync.R
import com.mountriv.vaultsync.gitcore.model.AuthSpec
import com.mountriv.vaultsync.sync.JobType
import com.mountriv.vaultsync.sync.OnboardMode
import com.mountriv.vaultsync.sync.OnboardState
import com.mountriv.vaultsync.sync.SyncService
import com.mountriv.vaultsync.ui.CancelButton
import com.mountriv.vaultsync.ui.ErrorCard
import com.mountriv.vaultsync.ui.FailureCard
import com.mountriv.vaultsync.ui.RemoteFields
import com.mountriv.vaultsync.ui.Texts
import com.mountriv.vaultsync.ui.rememberLabelWidth
import com.mountriv.vaultsync.ui.rememberNotificationPermissionRequest

/** 添加库向导：场景 → 目录 → 远端与认证 → 身份 → 确认 → 进度（docs/UI.md §3）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRepoScreen(onClose: () -> Unit, onOpenRepo: (String) -> Unit, onOpenSettings: () -> Unit) {
    val vm: AddRepoViewModel = viewModel()
    val context = LocalContext.current
    val onboard by AppGraph.syncManager.onboard.collectAsStateWithLifecycle()
    val steps = WizardStep.entries
    val requestNotifications = rememberNotificationPermissionRequest()

    fun back() {
        when (vm.step) {
            WizardStep.MODE -> onClose()
            WizardStep.PROGRESS -> if (onboard !is OnboardState.Running) onClose()
            else -> vm.step = steps[vm.step.ordinal - 1]
        }
    }
    BackHandler { back() }

    DisposableEffect(Unit) {
        onDispose { vm.discardIfNotAdded() }
    }

    Scaffold(
        // 键盘弹出时整页缩到键盘上方，正在输入的框保持可见
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_title)) },
                navigationIcon = {
                    IconButton(onClick = { back() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.add_step, vm.step.ordinal + 1, steps.size) + " · " + stringResource(stepTitle(vm.step)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            when (vm.step) {
                WizardStep.MODE -> ModeStep(vm) { vm.onModeConfirmed(); vm.step = WizardStep.FOLDER }
                WizardStep.FOLDER -> FolderPicker(vm.mode, vm.folder) { path ->
                    vm.onFolderChosen(path)
                    vm.step = WizardStep.REMOTE
                }
                WizardStep.REMOTE -> RemoteStep(vm, onOpenSettings) { vm.prefillIdentity(); vm.step = WizardStep.IDENTITY }
                WizardStep.IDENTITY -> IdentityStep(vm, onOpenSettings) { vm.step = WizardStep.CONFIRM }
                WizardStep.CONFIRM -> ConfirmStep(vm) {
                    requestNotifications()
                    if (vm.start()) SyncService.start(context)
                }
                WizardStep.PROGRESS -> ProgressStep(vm, onboard, onOpenRepo, onClose)
            }
        }
    }
}

private fun stepTitle(step: WizardStep): Int = when (step) {
    WizardStep.MODE -> R.string.add_step_mode
    WizardStep.FOLDER -> R.string.add_step_folder
    WizardStep.REMOTE -> R.string.add_step_remote
    WizardStep.IDENTITY -> R.string.add_step_identity
    WizardStep.CONFIRM -> R.string.add_step_confirm
    WizardStep.PROGRESS -> R.string.add_step_progress
}

@Composable
private fun ModeStep(vm: AddRepoViewModel, onNext: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModeOption(OnboardMode.CLONE, R.string.mode_clone_title, R.string.mode_clone_body, vm)
        ModeOption(OnboardMode.INIT, R.string.mode_init_title, R.string.mode_init_body, vm)
        ModeOption(OnboardMode.ADOPT, R.string.mode_adopt_title, R.string.mode_adopt_body, vm)
        Button(onClick = onNext, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.action_next)) }
    }
}

@Composable
private fun ModeOption(mode: OnboardMode, title: Int, body: Int, vm: AddRepoViewModel) {
    val selected = vm.mode == mode
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { vm.mode = mode },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = { vm.mode = mode })
            Spacer(Modifier.width(8.dp))
            Column {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun RemoteStep(vm: AddRepoViewModel, onOpenSettings: () -> Unit, onNext: () -> Unit) {
    val scope = rememberCoroutineScope()
    // 观察密钥列表：刚打开应用时列表还没读出来，读出来后"下一步"要重新判断（接管时地址是预填的，没有输入来触发重算）
    val keys by AppGraph.sshKeys.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        RemoteFields(
            form = vm.remote,
            repoId = vm.repoId,
            scope = scope,
            allowBlankUrl = vm.mode == OnboardMode.ADOPT,
            onManageKeys = onOpenSettings,
            onUrlChanged = vm::onUrlChanged,
        )
        OutlinedTextField(
            value = vm.branch,
            onValueChange = { vm.branch = it },
            label = { Text(stringResource(R.string.remote_branch_label)) },
            singleLine = true,
        )
        Button(onClick = onNext, enabled = keys.let { vm.remoteValid }, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.action_next))
        }
    }
}

@Composable
private fun IdentityStep(vm: AddRepoViewModel, onOpenSettings: () -> Unit, onNext: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = vm.name,
            onValueChange = { vm.name = it },
            label = { Text(stringResource(R.string.repo_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(stringResource(R.string.identity_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.identity_body), style = MaterialTheme.typography.bodyMedium)
        if (!vm.settingsHaveIdentity) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.identity_missing), color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.home_go_settings)) }
            }
        }
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
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onNext, enabled = vm.identityValid, modifier = Modifier.align(Alignment.End)) {
            Text(stringResource(R.string.action_next))
        }
    }
}

@Composable
private fun ConfirmStep(vm: AddRepoViewModel, onStart: () -> Unit) {
    val spec = vm.buildSpec()
    val labelWidth = rememberLabelWidth(
        listOf(
            R.string.confirm_mode, R.string.repo_name_label, R.string.confirm_folder, R.string.confirm_remote,
            R.string.confirm_branch, R.string.confirm_auth, R.string.confirm_identity,
        ).map { stringResource(it) },
        MaterialTheme.typography.labelLarge,
    ) + 12.dp
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConfirmRow(
            labelWidth,
            stringResource(R.string.confirm_mode),
            stringResource(
                when (vm.mode) {
                    OnboardMode.CLONE -> R.string.mode_clone_title
                    OnboardMode.INIT -> R.string.mode_init_title
                    OnboardMode.ADOPT -> R.string.mode_adopt_title
                },
            ),
        )
        ConfirmRow(labelWidth, stringResource(R.string.repo_name_label), spec.name)
        ConfirmRow(labelWidth, stringResource(R.string.confirm_folder), spec.localPath)
        ConfirmRow(labelWidth, stringResource(R.string.confirm_remote), spec.remoteUrl.ifBlank { stringResource(R.string.confirm_remote_existing) })
        ConfirmRow(labelWidth, stringResource(R.string.confirm_branch), spec.branch)
        ConfirmRow(
            labelWidth,
            stringResource(R.string.confirm_auth),
            when (val a = spec.auth) {
                is AuthSpec.HttpsToken -> stringResource(R.string.confirm_auth_https, a.username)
                is AuthSpec.SshKey -> stringResource(R.string.confirm_auth_ssh, AppGraph.sshKeys.loaded?.find(a.keyId)?.meta?.name ?: "?")
                else -> stringResource(R.string.confirm_auth_none)
            },
        )
        ConfirmRow(
            labelWidth,
            stringResource(R.string.confirm_identity),
            spec.identity?.let { "${it.name} <${it.email}>" } ?: stringResource(R.string.confirm_identity_default),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                when (vm.mode) {
                    OnboardMode.CLONE -> R.string.confirm_actions_clone
                    OnboardMode.INIT -> R.string.confirm_actions_init
                    OnboardMode.ADOPT -> R.string.confirm_actions_adopt
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        vm.startError?.let { ErrorCard(title = stringResource(R.string.add_start_failed), body = it) }
        Button(onClick = onStart, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.confirm_start)) }
    }
}

/** 确认页的一行"标签 + 值"；[labelWidth] 是这一页最宽标签的宽度，各行的值因此对齐。 */
@Composable
private fun ConfirmRow(labelWidth: Dp, label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(labelWidth))
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun ProgressStep(vm: AddRepoViewModel, state: OnboardState, onOpenRepo: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val settings by AppGraph.settings.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            OnboardState.Idle, is OnboardState.Running -> {
                val r = state as? OnboardState.Running
                Text(
                    r?.task?.let { t ->
                        Texts.progressLine(context, vm.mode.jobType, null, t, r.completed, r.total)
                    } ?: stringResource(R.string.progress_running, Texts.jobVerb(context, vm.mode.jobType)),
                )
                r?.let { Text(Texts.meterLine(context, vm.mode.jobType, null, it.meter), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (r != null && r.total > 0) {
                    LinearProgressIndicator(progress = { r.completed.toFloat() / r.total }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                val cancelling by AppGraph.syncManager.cancelling.collectAsStateWithLifecycle()
                CancelButton(vm.repoId in cancelling) { AppGraph.syncManager.cancel(vm.repoId) }
            }
            is OnboardState.Success -> {
                Text(stringResource(R.string.progress_success), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                if (state.notes.isNotEmpty()) {
                    state.notes.forEach { Text("• " + Texts.onboardNote(context, it), style = MaterialTheme.typography.bodyMedium) }
                }
                Button(onClick = { onOpenRepo(state.spec.id) }, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.progress_open_repo))
                }
            }
            OnboardState.Cancelled -> {
                Text(stringResource(R.string.progress_cancelled), style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.step = WizardStep.CONFIRM; AppGraph.syncManager.resetOnboard() }) {
                        Text(stringResource(R.string.action_retry))
                    }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
                }
            }
            is OnboardState.Failed -> {
                FailureCard(
                    title = stringResource(R.string.progress_failed, Texts.stage(context, state.stage), Texts.failureTitle(context, state.kind)),
                    kind = state.kind,
                    technical = state.message,
                    proxyEnabled = settings.proxyEnabled,
                ) {
                    TextButton(onClick = { vm.step = WizardStep.REMOTE; AppGraph.syncManager.resetOnboard() }) {
                        Text(stringResource(R.string.progress_back_to_edit))
                    }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
                }
            }
        }
    }
}

private val OnboardMode.jobType: JobType
    get() = when (this) {
        OnboardMode.CLONE -> JobType.CLONE
        OnboardMode.INIT -> JobType.INIT
        OnboardMode.ADOPT -> JobType.ADOPT
    }
