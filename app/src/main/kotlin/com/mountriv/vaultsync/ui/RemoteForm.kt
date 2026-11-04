package com.mountriv.vaultsync.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mountriv.vaultsync.AppGraph
import com.mountriv.vaultsync.R
import com.mountriv.vaultsync.gitcore.auth.CredentialsFactory
import com.mountriv.vaultsync.gitcore.auth.RemoteUrl
import com.mountriv.vaultsync.gitcore.auth.TransportAuth
import com.mountriv.vaultsync.gitcore.model.AuthSpec
import com.mountriv.vaultsync.gitcore.model.FailureClassifier
import com.mountriv.vaultsync.gitcore.model.FailureKind
import com.mountriv.vaultsync.gitcore.model.RepoSpec
import com.mountriv.vaultsync.gitcore.net.ProxyConfig
import com.mountriv.vaultsync.gitcore.repo.VaultRepository
import com.mountriv.vaultsync.platform.KeystoreSecretStore
import com.mountriv.vaultsync.platform.SshKeyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.lib.Constants

/**
 * 远端地址与认证的表单状态（添加向导与仓库设置共用，docs/UI.md §3/§4）：地址识别 HTTPS / SSH，SSH 可改端口与选择密钥，
 * HTTPS 填用户名与令牌；"测试连接"用 ls-remote。令牌只在内存里，保存时才写入 Keystore。
 *
 * [storedTokenId]：仓库设置里已保存令牌的秘密 id；令牌栏留空表示沿用它。它的状态由页面在 IO 线程读出后写进 [storedTokenStatus]
 * （解密要访问 Keystore，不能在组合时做）。
 */
class RemoteForm(
    initialUrl: String = "",
    initialUsername: String = "",
    initialKeyId: String? = null,
    val storedTokenId: String? = null,
) {
    var url by mutableStateOf(initialUrl)
        private set
    var username by mutableStateOf(initialUsername)
    var token by mutableStateOf("")
    var sshPort by mutableStateOf((RemoteUrl.parse(initialUrl).port ?: RemoteUrl.DEFAULT_SSH_PORT).toString())
    var keyId by mutableStateOf(initialKeyId)
    var testResult by mutableStateOf<TestResult?>(null)
    var storedTokenStatus by mutableStateOf<KeystoreSecretStore.Status?>(null)

    sealed class TestResult {
        data object Testing : TestResult()

        data class Ok(val branches: List<String>) : TestResult()

        data class Failed(val message: String, val kind: FailureKind) : TestResult()
    }

    val parsed: RemoteUrl.Parsed get() = RemoteUrl.parse(url)

    val kind: RemoteUrl.Kind get() = parsed.kind

    /** 规范化后的远端地址（SSH 带端口）。 */
    val effectiveUrl: String
        get() {
            val p = parsed
            return when (p.kind) {
                RemoteUrl.Kind.HTTPS -> p.normalized()
                RemoteUrl.Kind.SSH -> RemoteUrl.withPort(url.trim(), sshPort.toIntOrNull() ?: RemoteUrl.DEFAULT_SSH_PORT)
                RemoteUrl.Kind.UNSUPPORTED -> url.trim()
            }
        }

    /** 选中的 SSH 密钥存在且可读。 */
    val keyReady: Boolean get() = AppGraph.sshKeys.loaded?.find(keyId)?.unreadable == false

    private val hasToken: Boolean get() = token.isNotBlank() || storedTokenStatus == KeystoreSecretStore.Status.PRESENT

    /**
     * SSH 端口栏不是 1～65535。这时 [effectiveUrl] 按默认端口算，和已保存的地址可能比不出差别，
     * 库设置页要把它单独算作"有修改"（已保存的端口一定合法）。
     */
    val sshPortInvalid: Boolean get() = kind == RemoteUrl.Kind.SSH && (sshPort.toIntOrNull() ?: 0) !in 1..65535

    /** 认证信息是否齐全（[allowBlankUrl]：接管场景可以不填地址，沿用仓库里的 origin）。 */
    fun isValid(allowBlankUrl: Boolean): Boolean = invalidReason(allowBlankUrl) == null

    /** 不齐全的原因（字符串资源 id，未保存修改栏里显示）；齐全时为 null。 */
    @StringRes
    fun invalidReason(allowBlankUrl: Boolean): Int? = when {
        allowBlankUrl && url.isBlank() -> null
        kind == RemoteUrl.Kind.HTTPS -> when {
            username.isBlank() -> R.string.remote_reason_username
            !hasToken -> R.string.remote_reason_token
            else -> null
        }
        kind == RemoteUrl.Kind.SSH -> when {
            sshPortInvalid -> R.string.remote_reason_port
            !keyReady -> R.string.remote_reason_key
            else -> null
        }
        else -> R.string.remote_reason_url
    }

    /** 恢复为已保存的值（库设置页"放弃修改"）：地址、端口、用户名、所选密钥；新填的令牌清空，测试结果清掉。 */
    fun reset(url: String, username: String, keyId: String?) {
        this.url = url
        this.username = username
        this.keyId = keyId
        token = ""
        sshPort = (RemoteUrl.parse(url).port ?: RemoteUrl.DEFAULT_SSH_PORT).toString()
        testResult = null
    }

    fun onUrlChanged(v: String) {
        url = v
        testResult = null
        val p = RemoteUrl.parse(v)
        if (p.kind == RemoteUrl.Kind.SSH && p.port != null) sshPort = p.port.toString()
    }

    fun useGitHub443() {
        url = RemoteUrl.toGitHubPort443(url.trim())
        sshPort = RemoteUrl.GITHUB_SSH_443_PORT.toString()
        testResult = null
    }

    /** [useGitHub443] 的反向：换回 `github.com` 的 22 端口。 */
    fun useGitHub22() {
        url = RemoteUrl.toGitHubPort22(url.trim())
        sshPort = RemoteUrl.DEFAULT_SSH_PORT.toString()
        testResult = null
    }

    /** 按表单内容生成认证方式；HTTPS 的令牌秘密 id 按库 id 约定（[KeystoreSecretStore.httpsTokenId]）。 */
    fun authFor(repoId: String): AuthSpec = when (kind) {
        RemoteUrl.Kind.HTTPS -> AuthSpec.HttpsToken(username.trim(), KeystoreSecretStore.httpsTokenId(repoId))
        RemoteUrl.Kind.SSH -> keyId?.let { AuthSpec.SshKey(it) } ?: AuthSpec.None
        RemoteUrl.Kind.UNSUPPORTED -> AuthSpec.None
    }

    /** 保存时写入新填的令牌（留空则不动已保存的）。 */
    fun storeTokenIfEntered(repoId: String) {
        if (kind == RemoteUrl.Kind.HTTPS && token.isNotBlank()) {
            AppGraph.secrets.put(KeystoreSecretStore.httpsTokenId(repoId), token.trim().toCharArray())
        }
    }

    fun testConnection(scope: CoroutineScope, repoId: String) {
        val target = effectiveUrl
        val k = kind
        testResult = TestResult.Testing
        val user = username.trim()
        val typed = token.trim().toCharArray()
        val spec = RepoSpec(id = repoId, name = "test", localPath = "", remoteUrl = target, auth = authFor(repoId))
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                var auth: TransportAuth? = null
                try {
                    val s = AppGraph.settings.state.value
                    ProxyConfig.apply(ProxyConfig.Settings(s.proxyEnabled, s.proxyHost, s.proxyPort))
                    auth = if (k == RemoteUrl.Kind.HTTPS) {
                        val tokenChars = typed.takeIf { it.isNotEmpty() } ?: storedTokenId?.let { AppGraph.secrets.get(it) } ?: CharArray(0)
                        TransportAuth.https(user, tokenChars)
                    } else {
                        CredentialsFactory.forSpec(spec, AppGraph.secrets.provider, AppGraph.logger)
                    }
                    AppGraph.logger.info("test connection: ${RemoteUrl.redact(target)} auth=$auth")
                    val heads = VaultRepository.lsRemoteHeads(target, auth, TEST_TIMEOUT_SEC)
                    TestResult.Ok(heads.keys.map { it.removePrefix(Constants.R_HEADS) }.sorted())
                } catch (e: Exception) {
                    AppGraph.logger.warn("test connection failed: ${RemoteUrl.redact(target)}", e)
                    TestResult.Failed(e.message ?: e.toString(), FailureClassifier.classify(e.message, e))
                } finally {
                    auth?.dispose()
                }
            }
            testResult = r
        }
    }

    companion object {
        private const val TEST_TIMEOUT_SEC = 30

        /**
         * 已保存的地址按表单规则规范化后的样子（HTTPS 去掉用户信息，SSH 统一为 `ssh://` 形式），与没改过的表单的 [effectiveUrl] 相同。
         * 判断"有没有修改"时用它，免得旧的 scp 写法（接管时沿用的 origin）一打开页面就被当成修改。
         */
        fun normalize(url: String): String {
            val p = RemoteUrl.parse(url)
            return if (p.kind == RemoteUrl.Kind.UNSUPPORTED) url.trim() else p.normalized()
        }
    }
}

/**
 * [RemoteForm] 的输入界面：地址、HTTPS 的用户名与令牌 / SSH 的端口、GitHub 443 / 22 端口切换与密钥选择、测试连接及结果。
 */
@Composable
fun RemoteFields(
    form: RemoteForm,
    repoId: String,
    scope: CoroutineScope,
    allowBlankUrl: Boolean,
    onManageKeys: (() -> Unit)?,
    onUrlChanged: (String) -> Unit = form::onUrlChanged,
) {
    val context = LocalContext.current
    val tokenStatus = form.storedTokenStatus
    val kind = form.kind
    val keys by AppGraph.sshKeys.state.collectAsStateWithLifecycle()
    val settings by AppGraph.settings.state.collectAsStateWithLifecycle()
    // 第一次出现 SSH 地址时，默认选中默认密钥
    LaunchedEffect(kind, keys) {
        val loaded = keys as? SshKeyStore.State.Loaded
        if (kind == RemoteUrl.Kind.SSH && form.keyId == null && loaded != null) form.keyId = loaded.default?.id
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = form.url,
            onValueChange = onUrlChanged,
            label = { Text(stringResource(R.string.remote_url_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            supportingText = {
                Text(
                    when {
                        allowBlankUrl && form.url.isBlank() -> stringResource(R.string.remote_url_adopt_hint)
                        kind == RemoteUrl.Kind.HTTPS -> stringResource(R.string.remote_kind_https)
                        kind == RemoteUrl.Kind.SSH -> stringResource(R.string.remote_kind_ssh)
                        form.url.isBlank() -> stringResource(R.string.remote_url_examples)
                        else -> stringResource(R.string.remote_kind_unsupported)
                    },
                )
            },
            isError = form.url.isNotBlank() && kind == RemoteUrl.Kind.UNSUPPORTED,
        )
        if (kind == RemoteUrl.Kind.HTTPS) {
            OutlinedTextField(
                value = form.username,
                onValueChange = { form.username = it; form.testResult = null },
                label = { Text(stringResource(R.string.remote_username_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = form.token,
                onValueChange = { form.token = it; form.testResult = null },
                label = {
                    Text(stringResource(if (tokenStatus == KeystoreSecretStore.Status.PRESENT) R.string.remote_token_replace_label else R.string.remote_token_label))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                supportingText = {
                    Text(
                        when (tokenStatus) {
                            KeystoreSecretStore.Status.PRESENT -> stringResource(R.string.remote_token_saved_hint)
                            KeystoreSecretStore.Status.UNREADABLE -> stringResource(R.string.remote_token_unreadable_hint)
                            else -> stringResource(R.string.remote_token_hint)
                        },
                    )
                },
                isError = tokenStatus == KeystoreSecretStore.Status.UNREADABLE && form.token.isBlank(),
            )
        }
        if (kind == RemoteUrl.Kind.SSH) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.sshPort,
                    onValueChange = { form.sshPort = it.filter(Char::isDigit).take(5); form.testResult = null },
                    label = { Text(stringResource(R.string.remote_ssh_port_label)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                // GitHub 的 22 / 443 端口来回切换（443 走 ssh.github.com，D-018）
                when (form.parsed.host) {
                    RemoteUrl.GITHUB_HOST -> TextButton(onClick = form::useGitHub443) { Text(stringResource(R.string.remote_ssh_github_443)) }
                    RemoteUrl.GITHUB_SSH_443_HOST -> TextButton(onClick = form::useGitHub22) { Text(stringResource(R.string.remote_ssh_github_22)) }
                }
            }
            SshKeyPicker(selectedId = form.keyId, onSelect = { form.keyId = it; form.testResult = null }, onManageKeys = onManageKeys)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { form.testConnection(scope, repoId) },
                enabled = form.url.isNotBlank() && kind != RemoteUrl.Kind.UNSUPPORTED && form.isValid(allowBlankUrl = false) &&
                    form.testResult !is RemoteForm.TestResult.Testing,
            ) { Text(stringResource(R.string.remote_test)) }
            if (form.testResult is RemoteForm.TestResult.Testing) CircularProgressIndicator(Modifier.size(20.dp))
        }
        when (val r = form.testResult) {
            is RemoteForm.TestResult.Ok -> Text(
                if (r.branches.isEmpty()) stringResource(R.string.remote_test_ok_empty) else stringResource(R.string.remote_test_ok, Texts.joinList(context, r.branches)),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
            )
            is RemoteForm.TestResult.Failed -> FailureCard(
                title = stringResource(R.string.remote_test_fail_title, Texts.failureTitle(context, r.kind)),
                kind = r.kind,
                technical = r.message,
                proxyEnabled = settings.proxyEnabled,
            )
            else -> Unit
        }
    }
}
