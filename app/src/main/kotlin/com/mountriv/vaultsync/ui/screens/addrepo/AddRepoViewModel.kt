package com.mountriv.vaultsync.ui.screens.addrepo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mountriv.vaultsync.AppGraph
import com.mountriv.vaultsync.gitcore.auth.RemoteUrl
import com.mountriv.vaultsync.gitcore.model.AuthSpec
import com.mountriv.vaultsync.gitcore.model.GitIdentity
import com.mountriv.vaultsync.gitcore.model.RepoSpec
import com.mountriv.vaultsync.gitcore.repo.RepoInspector
import com.mountriv.vaultsync.platform.KeystoreSecretStore
import com.mountriv.vaultsync.sync.OnboardMode
import com.mountriv.vaultsync.sync.OnboardState
import com.mountriv.vaultsync.ui.RemoteForm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

enum class WizardStep { MODE, FOLDER, REMOTE, IDENTITY, CONFIRM, PROGRESS }

/** 添加库向导的状态（docs/UI.md §3）。秘密只在内存里，"开始"时才写入 Keystore。 */
class AddRepoViewModel : ViewModel() {

    val repoId: String = UUID.randomUUID().toString()

    var step by mutableStateOf(WizardStep.MODE)
    var mode by mutableStateOf(OnboardMode.CLONE)

    // 目录
    var folder by mutableStateOf<String?>(null)

    // 远端与认证
    val remote = RemoteForm()
    var branch by mutableStateOf(RepoSpec.DEFAULT_BRANCH)

    // 身份
    var name by mutableStateOf("")
    var authorName by mutableStateOf("")
    var authorEmail by mutableStateOf("")
    var commitTemplate by mutableStateOf(RepoSpec.DEFAULT_COMMIT_TEMPLATE)
    var dateFormat by mutableStateOf(RepoSpec.DEFAULT_DATE_FORMAT)

    /** 是否已经把秘密写入 Keystore 并入队；关闭向导时据此决定是否清理。 */
    private var started = false

    /** 后面各步是按哪种方式填的；第 1 步换了方式再点"下一步"时清空它们（[onModeConfirmed]）。 */
    private var filledForMode: OnboardMode? = null

    /** 接管场景从仓库 origin 预填的地址；用户没改过（地址仍是它）时，换文件夹会换成新文件夹的 origin。 */
    private var prefilledUrl: String? = null

    val remoteValid: Boolean get() = remote.isValid(allowBlankUrl = mode == OnboardMode.ADOPT)

    fun onUrlChanged(v: String) {
        remote.onUrlChanged(v)
        val p = RemoteUrl.parse(v)
        if (name.isBlank() && p.repoName.isNotBlank() && mode == OnboardMode.CLONE) name = p.repoName
    }

    /** 第 1 步点"下一步"：方式与后面各步填写时的不同，就把后面各步清空，免得沿用另一种方式预填的远端、分支等。 */
    fun onModeConfirmed() {
        if (filledForMode != null && filledForMode != mode) resetLaterSteps()
        filledForMode = mode
    }

    private fun resetLaterSteps() {
        folder = null
        remote.reset(url = "", username = "", keyId = null)
        prefilledUrl = null
        branch = RepoSpec.DEFAULT_BRANCH
        name = ""
        authorName = ""
        authorEmail = ""
        commitTemplate = RepoSpec.DEFAULT_COMMIT_TEMPLATE
        dateFormat = RepoSpec.DEFAULT_DATE_FORMAT
        startError = null
    }

    fun onFolderChosen(path: String) {
        folder = path
        if (name.isBlank() || mode != OnboardMode.CLONE) name = File(path).name
        if (mode == OnboardMode.ADOPT && (remote.url.isBlank() || remote.url == prefilledUrl)) prefillFromExistingRepo(path)
    }

    /** 接管场景：把仓库里已配置的 origin 与当前分支预填进来，这样 HTTPS 远端会出现用户名/令牌栏、SSH 远端会出现密钥选择。 */
    private fun prefillFromExistingRepo(path: String) {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { RepoInspector.inspect(File(path)) }.getOrNull() } ?: return@launch
            if (remote.url.isBlank() || remote.url == prefilledUrl) {
                val url = info.remoteUrl.orEmpty()
                remote.onUrlChanged(url)
                prefilledUrl = url.ifEmpty { null }
            }
            info.branch?.let { branch = it }
        }
    }

    fun prefillIdentity() {
        val s = AppGraph.settings.state.value
        if (commitTemplate == RepoSpec.DEFAULT_COMMIT_TEMPLATE) commitTemplate = s.commitTemplate
        if (dateFormat == RepoSpec.DEFAULT_DATE_FORMAT) dateFormat = s.dateFormat
    }

    val settingsHaveIdentity: Boolean get() = AppGraph.settings.state.value.hasIdentity

    val identityValid: Boolean
        get() = settingsHaveIdentity || (authorName.isNotBlank() && authorEmail.isNotBlank())

    fun buildSpec(): RepoSpec {
        val identity = if (authorName.isNotBlank() && authorEmail.isNotBlank()) GitIdentity(authorName.trim(), authorEmail.trim()) else null
        return RepoSpec(
            id = repoId,
            name = name.trim().ifEmpty { File(folder ?: "").name.ifEmpty { remote.parsed.repoName } },
            localPath = folder ?: "",
            remoteUrl = remote.effectiveUrl,
            branch = branch.trim().ifEmpty { RepoSpec.DEFAULT_BRANCH },
            auth = remote.authFor(repoId),
            identity = identity,
            commitTemplate = commitTemplate.ifBlank { RepoSpec.DEFAULT_COMMIT_TEMPLATE },
            dateFormat = dateFormat.ifBlank { RepoSpec.DEFAULT_DATE_FORMAT },
        )
    }

    /** "开始"时写秘密失败（Keystore 异常）的说明；正常为 null。 */
    var startError by mutableStateOf<String?>(null)

    /** 写入秘密并入队；返回 true 时调用方启动同步服务，失败设置 [startError]。 */
    fun start(): Boolean {
        val spec = buildSpec()
        if (spec.auth is AuthSpec.HttpsToken) {
            try {
                remote.storeTokenIfEntered(repoId)
            } catch (e: Exception) {
                AppGraph.logger.error("cannot store token", e)
                startError = e.message ?: e.toString()
                return false
            }
        }
        started = true
        startError = null
        AppGraph.syncManager.resetOnboard()
        AppGraph.syncManager.enqueueOnboard(mode, spec)
        step = WizardStep.PROGRESS
        return true
    }

    /** 向导被关闭：没成功的话清掉已写入的令牌与日志（SSH 密钥属于密钥列表，不动）。 */
    fun discardIfNotAdded() {
        val state = AppGraph.syncManager.onboard
        if (state.value is OnboardState.Success) return
        if (state.value is OnboardState.Running) return
        if (started) {
            AppGraph.secrets.delete(KeystoreSecretStore.httpsTokenId(repoId))
            AppGraph.logs.deleteAll(repoId)
        }
        AppGraph.syncManager.resetOnboard()
    }

    override fun onCleared() {
        remote.token = ""
    }
}
