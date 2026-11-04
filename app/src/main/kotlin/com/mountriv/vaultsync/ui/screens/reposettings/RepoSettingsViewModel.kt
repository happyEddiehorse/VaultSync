package com.mountriv.vaultsync.ui.screens.reposettings

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mountriv.vaultsync.AppGraph
import com.mountriv.vaultsync.R
import com.mountriv.vaultsync.gitcore.model.AuthSpec
import com.mountriv.vaultsync.gitcore.model.GitIdentity
import com.mountriv.vaultsync.gitcore.model.RepoSpec
import com.mountriv.vaultsync.gitcore.repo.RepoMaintenance
import com.mountriv.vaultsync.platform.KeystoreSecretStore
import com.mountriv.vaultsync.ui.RemoteForm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 仓库设置页的状态（docs/UI.md §4.1、§9.1）。表单（名称、远端与认证、提交者与提交信息）的改动只在点底部栏的"保存"时写入，
 * "放弃修改"恢复为已保存的值；表单在 ViewModel 里，跳页、旋转屏幕都不丢。维护与 .gitignore 是即时操作。
 */
class RepoSettingsViewModel(val repoId: String) : ViewModel() {

    private val original: RepoSpec? = AppGraph.repos.get(repoId)

    val remote = RemoteForm(
        initialUrl = original?.remoteUrl.orEmpty(),
        initialUsername = (original?.auth as? AuthSpec.HttpsToken)?.username.orEmpty(),
        initialKeyId = (original?.auth as? AuthSpec.SshKey)?.keyId,
        storedTokenId = KeystoreSecretStore.httpsTokenId(repoId),
    )
    var name by mutableStateOf(original?.name.orEmpty())
    var authorName by mutableStateOf(original?.identity?.name.orEmpty())
    var authorEmail by mutableStateOf(original?.identity?.email.orEmpty())
    var commitTemplate by mutableStateOf(original?.commitTemplate ?: RepoSpec.DEFAULT_COMMIT_TEMPLATE)
    var dateFormat by mutableStateOf(original?.dateFormat ?: RepoSpec.DEFAULT_DATE_FORMAT)

    var saveError by mutableStateOf<String?>(null)
    var saved by mutableStateOf(false)
        private set

    var stats by mutableStateOf<RepoMaintenance.Stats?>(null)
        private set
    var statsError by mutableStateOf<String?>(null)
        private set
    var gitignoreMissing by mutableStateOf<List<String>?>(null)
        private set
    var gitignoreAdded by mutableStateOf<List<String>?>(null)
        private set

    init {
        refreshTokenStatus()
        refreshRepoInfo()
    }

    fun refreshTokenStatus() {
        viewModelScope.launch {
            remote.storedTokenStatus = withContext(Dispatchers.IO) { AppGraph.secrets.status(KeystoreSecretStore.httpsTokenId(repoId)) }
        }
    }

    /** 读取对象存储统计与 .gitignore 缺少的推荐规则（只读，与同步可并行）。 */
    fun refreshRepoInfo() {
        val spec = AppGraph.repos.get(repoId) ?: return
        viewModelScope.launch {
            val s = withContext(Dispatchers.IO) { runCatching { AppGraph.syncManager.maintenanceStats(spec) } }
            s.onSuccess { stats = it; statsError = null }.onFailure { statsError = it.message ?: it.toString() }
            gitignoreMissing = withContext(Dispatchers.IO) { runCatching { AppGraph.syncManager.gitignoreMissing(spec) }.getOrNull() }
        }
    }

    val identityValid: Boolean
        get() = (authorName.isBlank() && authorEmail.isBlank()) || (authorName.isNotBlank() && authorEmail.isNotBlank())

    /** 不能保存的原因（字符串资源 id）；可以保存时为 null。[busy]：这个库正在同步或排队。 */
    @StringRes
    fun saveBlockedReason(busy: Boolean): Int? = when {
        busy -> R.string.unsaved_reason_busy
        name.isBlank() -> R.string.repo_settings_reason_name
        !identityValid -> R.string.repo_settings_identity_incomplete
        else -> remote.invalidReason(allowBlankUrl = false)
    }

    fun buildSpec(): RepoSpec? {
        val spec = AppGraph.repos.get(repoId) ?: return null
        return spec.copy(
            name = name.trim(),
            remoteUrl = remote.effectiveUrl,
            auth = remote.authFor(repoId),
            identity = if (authorName.isNotBlank() && authorEmail.isNotBlank()) GitIdentity(authorName.trim(), authorEmail.trim()) else null,
            commitTemplate = commitTemplate.ifBlank { RepoSpec.DEFAULT_COMMIT_TEMPLATE },
            dateFormat = dateFormat.ifBlank { RepoSpec.DEFAULT_DATE_FORMAT },
        )
    }

    /** 已保存的库设置，地址按表单规则规范化（[RemoteForm.normalize]）后再比较。 */
    private fun savedForComparison(): RepoSpec? =
        AppGraph.repos.get(repoId)?.let { it.copy(remoteUrl = RemoteForm.normalize(it.remoteUrl)) }

    /**
     * 表单与已保存的值不同（新填了令牌也算）。比较规则与保存时一致：去掉首尾空格、空值按默认值。
     * 提交者只填了一半、SSH 端口不合法时，[buildSpec] 会把它们换成"没有提交者""默认端口"，比较不出来；
     * 已保存的值不会是这两种状态，所以直接算作有修改——否则栏不出现、返回时也不确认，填了一半的内容会悄悄丢掉。
     */
    val dirty: Boolean
        get() = remote.token.isNotBlank() || !identityValid || remote.sshPortInvalid || buildSpec() != savedForComparison()

    fun save() {
        val next = buildSpec() ?: return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    remote.storeTokenIfEntered(repoId)
                    // 不再用 HTTPS 时删掉旧令牌，免得留下用不到的秘密
                    if (next.auth !is AuthSpec.HttpsToken) AppGraph.secrets.delete(KeystoreSecretStore.httpsTokenId(repoId))
                    AppGraph.syncManager.updateRepo(next)
                }
            }
            r.onSuccess {
                remote.token = ""
                saveError = null
                saved = true
                refreshTokenStatus()
            }.onFailure { saveError = it.message ?: it.toString() }
        }
    }

    /** 放弃修改：表单恢复为已保存的值（令牌栏清空）。 */
    fun discard() {
        val spec = AppGraph.repos.get(repoId) ?: return
        name = spec.name
        authorName = spec.identity?.name.orEmpty()
        authorEmail = spec.identity?.email.orEmpty()
        commitTemplate = spec.commitTemplate
        dateFormat = spec.dateFormat
        remote.reset(
            url = spec.remoteUrl,
            username = (spec.auth as? AuthSpec.HttpsToken)?.username.orEmpty(),
            keyId = (spec.auth as? AuthSpec.SshKey)?.keyId,
        )
        saveError = null
    }

    fun consumeSaved() {
        saved = false
    }

    fun appendGitignore() {
        val spec = AppGraph.repos.get(repoId) ?: return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { AppGraph.syncManager.appendGitignore(spec) } }
            r.onSuccess { gitignoreAdded = it; refreshRepoInfo() }.onFailure { statsError = it.message ?: it.toString() }
        }
    }

    override fun onCleared() {
        remote.token = ""
    }
}
