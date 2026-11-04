package com.mountriv.vaultsync.gitcore.model

import kotlinx.serialization.Serializable

/** 提交作者身份。 */
@Serializable
data class GitIdentity(val name: String, val email: String)

/** 认证方式。秘密本身不在这里，只存 app 层 SecretStore 的 id。 */
@Serializable
sealed class AuthSpec {
    @Serializable
    data object None : AuthSpec()

    /** HTTPS + 个人访问令牌。[username] 任意非空（GitHub 推荐账号名）；令牌存 [secretId]。 */
    @Serializable
    data class HttpsToken(val username: String, val secretId: String) : AuthSpec()

    /** SSH 密钥认证。私钥（OpenSSH 格式）与口令存 [keyId] / [passphraseId]。 */
    @Serializable
    data class SshKey(val keyId: String, val passphraseId: String? = null) : AuthSpec()
}

/** 一个被管理的 Obsidian 库仓库。`.git` 位于 [localPath] 之内。 */
@Serializable
data class RepoSpec(
    val id: String,
    val name: String,
    val localPath: String,
    val remoteUrl: String,
    val branch: String = DEFAULT_BRANCH,
    val auth: AuthSpec = AuthSpec.None,
    val identity: GitIdentity? = null,
    val commitTemplate: String = DEFAULT_COMMIT_TEMPLATE,
    val dateFormat: String = DEFAULT_DATE_FORMAT,
) {
    companion object {
        const val DEFAULT_BRANCH = "main"

        /** 与 obsidian-git 插件默认值一致（见 LESSONS L-008）。日期格式按 moment 写法，见 CommitMessageTemplate。 */
        const val DEFAULT_COMMIT_TEMPLATE = "vault backup: {{date}}"
        const val DEFAULT_DATE_FORMAT = "YYYY-MM-DD HH:mm:ss"
    }
}

/** 同步阶段，用于进度展示与错误定位。 */
enum class SyncStage {
    PRECHECK,
    COMMIT_LOCAL,
    FETCH,
    MERGE,
    RESOLVE_CONFLICTS,
    PUSH,
    DONE,
}

/**
 * gitcore 对外抛出的异常：带阶段与可读信息（[detail] 不含阶段前缀）。
 * 原因明确时抛出处给出 [kind]，否则由 [FailureClassifier] 根据异常链推断。
 */
class SyncException(
    val stage: SyncStage,
    val detail: String,
    cause: Throwable? = null,
    val kind: FailureKind? = null,
) : Exception("[$stage] $detail", cause)

/** 一次成功同步的摘要。 */
data class SyncSummary(
    /** 本次本地提交的 id；没有本地变更时为 null。 */
    val localCommitId: String?,
    val localFilesCommitted: Int,
    /** FAST_FORWARD / MERGED / ALREADY_UP_TO_DATE；远端还没有该分支时为 null。 */
    val mergeStatus: String?,
    /** OK / UP_TO_DATE / NOTHING_TO_PUSH。 */
    val pushStatus: String,
    val retries: Int,
    val durationMs: Long,
)

/** 同步结果。 */
sealed class SyncOutcome {
    data class Success(val summary: SyncSummary) : SyncOutcome()

    /** 合并产生冲突，仓库处于 MERGING 状态，需要用户在冲突页处理。 */
    data class Conflicts(val files: List<String>) : SyncOutcome()

    data class Cancelled(val stage: SyncStage) : SyncOutcome()

    data class Failed(
        val stage: SyncStage,
        val message: String,
        val cause: Throwable? = null,
        val kind: FailureKind = FailureClassifier.classify(message, cause),
    ) : SyncOutcome()
}

/** 进度回调（UI 实现；所有方法都在同步线程上调用）。 */
interface ProgressSink {
    fun onStage(stage: SyncStage) {}
    fun onTask(title: String, totalWork: Int) {}
    fun onProgress(completed: Int) {}
    fun onTaskDone() {}

    object None : ProgressSink
}

/** 秘密（令牌、口令）的提供者；app 层用 Keystore 实现。返回 null 表示没有该秘密。 */
fun interface SecretProvider {
    fun secret(id: String): CharArray?

    companion object {
        val NONE: SecretProvider = SecretProvider { null }
    }
}
