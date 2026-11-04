package com.mountriv.vaultsync.gitcore.sync

import com.mountriv.vaultsync.gitcore.auth.CredentialsFactory
import com.mountriv.vaultsync.gitcore.auth.TransportAuth
import com.mountriv.vaultsync.gitcore.log.SyncLogger
import com.mountriv.vaultsync.gitcore.model.FailureClassifier
import com.mountriv.vaultsync.gitcore.model.FailureKind
import com.mountriv.vaultsync.gitcore.model.GitIdentity
import com.mountriv.vaultsync.gitcore.model.ProgressSink
import com.mountriv.vaultsync.gitcore.model.RepoSpec
import com.mountriv.vaultsync.gitcore.model.SecretProvider
import com.mountriv.vaultsync.gitcore.model.SyncException
import com.mountriv.vaultsync.gitcore.model.SyncStage
import com.mountriv.vaultsync.gitcore.net.Aborter
import com.mountriv.vaultsync.gitcore.repo.GitIgnoreTemplate
import com.mountriv.vaultsync.gitcore.repo.VaultRepository
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.transport.RemoteRefUpdate
import java.io.File
import java.time.ZonedDateTime

/**
 * 三种接入流程（docs/SYNC_ENGINE.md §4）：
 * - [clone]：远端已有仓库，克隆到空目录；
 * - [initAndPush]：手机已有库、远端为空，初始化后推送首个提交；
 * - [adopt]：目录里已经有 `.git`，直接接管。
 */
object RepoOnboarding {

    sealed class Result {
        /** 可以开始同步；[spec] 可能被修正（接管时采用实际分支/远端）。[notes] 是给用户看的提示。 */
        data class Ready(val spec: RepoSpec, val notes: List<Note> = emptyList()) : Result()

        data class Failed(
            val stage: SyncStage,
            val message: String,
            val cause: Throwable? = null,
            val kind: FailureKind = FailureClassifier.classify(message, cause),
        ) : Result()
    }

    /** 接入成功时给用户的提示。界面按类型翻译；[toString] 是写日志用的英文。 */
    sealed class Note {
        /** 远端是空仓库，首次同步会推送初始提交。 */
        object RemoteEmpty : Note() {
            override fun toString() = "remote is empty; the first sync will push the initial commit"
        }

        /** 按推荐模板写入了 `.gitignore`。 */
        object GitignoreCreated : Note() {
            override fun toString() = ".gitignore created from the recommended template"
        }

        /** 仓库实际检出的分支与向导里填写的不同，以仓库为准。 */
        data class BranchAdjusted(val actual: String, val requested: String) : Note() {
            override fun toString() = "using the checked-out branch '$actual' instead of '$requested'"
        }

        /** 仓库原来配置的远端地址被替换。 */
        data class RemoteChanged(val from: String, val to: String) : Note() {
            override fun toString() = "remote URL changed from $from to $to"
        }

        /** 仓库里有未完成且仍有冲突的合并。 */
        object UnfinishedMerge : Note() {
            override fun toString() = "the repository has an unfinished merge with conflicts; resolve it in the app"
        }

        /** 仓库里有冲突已解决但尚未提交的合并。 */
        object ResolvedMergePending : Note() {
            override fun toString() = "the repository has a resolved but uncommitted merge; the next sync completes it"
        }
    }

    fun clone(
        spec: RepoSpec,
        secrets: SecretProvider,
        logger: SyncLogger,
        progress: ProgressSink = ProgressSink.None,
        cancel: () -> Boolean = { false },
        timeoutSec: Int = VaultRepository.DEFAULT_TIMEOUT_SEC,
        aborter: Aborter = Aborter(),
    ): Result {
        val dir = File(spec.localPath)
        if (dir.exists() && !dir.isDirectory) {
            return Result.Failed(SyncStage.PRECHECK, "path is not a directory: ${spec.localPath}", kind = FailureKind.DIRECTORY_NOT_EMPTY)
        }
        if (dir.isDirectory && dir.list()?.isNotEmpty() == true) {
            return Result.Failed(
                SyncStage.PRECHECK,
                "directory is not empty: ${spec.localPath}; choose an empty folder, or use 'adopt' / 'init' for an existing vault",
                kind = FailureKind.DIRECTORY_NOT_EMPTY,
            )
        }
        val auth = try {
            CredentialsFactory.forSpec(spec, secrets, logger)
        } catch (e: SyncException) {
            return Result.Failed(e.stage, e.detail, e.cause, e.kind ?: FailureClassifier.classify(e.detail, e.cause))
        }
        logger.info("auth: $auth")
        try {
            return aborter.bind { cloneWith(spec, dir, auth, logger, progress, cancel, timeoutSec) }
        } finally {
            auth.dispose()
        }
    }

    private fun cloneWith(
        spec: RepoSpec,
        dir: File,
        auth: TransportAuth,
        logger: SyncLogger,
        progress: ProgressSink,
        cancel: () -> Boolean,
        timeoutSec: Int,
    ): Result {
        val heads = try {
            VaultRepository.lsRemoteHeads(spec.remoteUrl, auth, timeoutSec)
        } catch (e: Exception) {
            logger.error("ls-remote failed", e)
            return Result.Failed(SyncStage.FETCH, "cannot reach remote: ${e.message}", e)
        }
        val wanted = Constants.R_HEADS + spec.branch
        if (heads.isNotEmpty() && !heads.containsKey(wanted)) {
            val available = heads.keys.map { it.removePrefix(Constants.R_HEADS) }.sorted()
            return Result.Failed(
                SyncStage.PRECHECK,
                "branch '${spec.branch}' not found on remote; available: $available",
                kind = FailureKind.BRANCH_NOT_FOUND,
            )
        }

        val createdByUs = !dir.exists()
        if (createdByUs && !dir.mkdirs()) {
            return Result.Failed(SyncStage.PRECHECK, "cannot create directory: ${spec.localPath}", kind = FailureKind.STORAGE)
        }
        try {
            val monitor = JGitProgress(progress, cancel)
            VaultRepository.clone(spec.remoteUrl, dir, spec.branch, auth, logger, monitor, timeoutSec).use { repo ->
                repo.ensureSharedStorageConfig(spec.remoteUrl, spec.branch)
                repo.refreshIndex(monitor)
            }
            val notes = if (heads.isEmpty()) listOf<Note>(Note.RemoteEmpty) else emptyList()
            return Result.Ready(spec, notes)
        } catch (e: Exception) {
            logger.error("clone failed", e)
            if (createdByUs) dir.deleteRecursively() else File(dir, Constants.DOT_GIT).deleteRecursively()
            return Result.Failed(SyncStage.FETCH, "clone failed: ${e.message}", e)
        }
    }

    fun initAndPush(
        spec: RepoSpec,
        secrets: SecretProvider,
        defaultIdentity: GitIdentity,
        logger: SyncLogger,
        progress: ProgressSink = ProgressSink.None,
        cancel: () -> Boolean = { false },
        deviceName: String = "android",
        timeoutSec: Int = VaultRepository.DEFAULT_TIMEOUT_SEC,
        clock: () -> ZonedDateTime = { ZonedDateTime.now() },
        aborter: Aborter = Aborter(),
    ): Result {
        val dir = File(spec.localPath)
        if (!dir.isDirectory) return Result.Failed(SyncStage.PRECHECK, "directory not found: ${spec.localPath}", kind = FailureKind.VAULT_MISSING)
        if (File(dir, Constants.DOT_GIT).exists()) {
            return Result.Failed(SyncStage.PRECHECK, "directory already contains .git; use 'adopt' instead", kind = FailureKind.ALREADY_A_REPOSITORY)
        }
        val auth = try {
            CredentialsFactory.forSpec(spec, secrets, logger)
        } catch (e: SyncException) {
            return Result.Failed(e.stage, e.detail, e.cause, e.kind ?: FailureClassifier.classify(e.detail, e.cause))
        }
        logger.info("auth: $auth")
        try {
            return aborter.bind { initWith(spec, dir, auth, spec.identity ?: defaultIdentity, logger, progress, cancel, deviceName, timeoutSec, clock) }
        } finally {
            auth.dispose()
        }
    }

    private fun initWith(
        spec: RepoSpec,
        dir: File,
        auth: TransportAuth,
        identity: GitIdentity,
        logger: SyncLogger,
        progress: ProgressSink,
        cancel: () -> Boolean,
        deviceName: String,
        timeoutSec: Int,
        clock: () -> ZonedDateTime,
    ): Result {
        val heads = try {
            VaultRepository.lsRemoteHeads(spec.remoteUrl, auth, timeoutSec)
        } catch (e: Exception) {
            logger.error("ls-remote failed", e)
            return Result.Failed(SyncStage.FETCH, "cannot reach remote: ${e.message}", e)
        }
        if (heads.isNotEmpty()) {
            val available = heads.keys.map { it.removePrefix(Constants.R_HEADS) }.sorted()
            return Result.Failed(
                SyncStage.PRECHECK,
                "remote already has branches $available; clone it into an empty folder instead (move your notes in afterwards)",
                kind = FailureKind.REMOTE_NOT_EMPTY,
            )
        }

        var stage = SyncStage.PRECHECK
        try {
            VaultRepository.init(dir, spec.branch, logger).use { repo ->
                val wroteIgnore = GitIgnoreTemplate.writeIfMissing(dir)
                repo.ensureSharedStorageConfig(spec.remoteUrl, spec.branch)

                stage = SyncStage.COMMIT_LOCAL
                repo.commitAll(identity, JGitProgress(progress, cancel), cancel) { paths ->
                    CommitMessageTemplate.render(spec.commitTemplate, spec.dateFormat, clock(), deviceName, paths)
                } ?: return Result.Failed(SyncStage.COMMIT_LOCAL, "the vault has no files to commit", kind = FailureKind.VAULT_EMPTY)

                stage = SyncStage.PUSH
                val outcome = repo.push(spec.branch, auth, JGitProgress(progress, cancel), timeoutSec)
                if (outcome.status != RemoteRefUpdate.Status.OK) {
                    return Result.Failed(
                        SyncStage.PUSH,
                        "initial push failed: ${outcome.status}${outcome.message?.let { " – $it" } ?: ""}. " +
                            "The local repository was created; fix the problem, then add the vault with 'adopt' and sync",
                        kind = FailureClassifier.classify(outcome.message, null).takeIf { it != FailureKind.UNKNOWN }
                            ?: FailureKind.PUSH_REJECTED,
                    )
                }
                val notes = buildList<Note> { if (wroteIgnore) add(Note.GitignoreCreated) }
                return Result.Ready(spec, notes)
            }
        } catch (e: Exception) {
            logger.error("init failed at $stage", e)
            return Result.Failed(stage, "${e.message}", e)
        }
    }

    fun adopt(spec: RepoSpec, logger: SyncLogger): Result {
        val dir = File(spec.localPath)
        if (!dir.isDirectory) return Result.Failed(SyncStage.PRECHECK, "directory not found: ${spec.localPath}", kind = FailureKind.VAULT_MISSING)
        if (!VaultRepository.isRepository(dir)) {
            return Result.Failed(SyncStage.PRECHECK, "no .git found in ${spec.localPath}; use 'init' or 'clone'", kind = FailureKind.NOT_A_REPOSITORY)
        }
        try {
            VaultRepository.open(dir, logger).use { repo ->
                val st = repo.state
                if (st != RepositoryState.SAFE && st != RepositoryState.MERGING && st != RepositoryState.MERGING_RESOLVED) {
                    return Result.Failed(
                        SyncStage.PRECHECK,
                        "repository is in state $st; finish it on the desktop first",
                        kind = FailureKind.REPOSITORY_STATE,
                    )
                }
                val branch = repo.currentBranch
                    ?: return Result.Failed(SyncStage.PRECHECK, "HEAD is detached; check out a branch first", kind = FailureKind.REPOSITORY_STATE)
                val configuredUrl = repo.repository.config.getString("remote", Constants.DEFAULT_REMOTE_NAME, "url")
                val url = spec.remoteUrl.trim().ifEmpty {
                    configuredUrl ?: return Result.Failed(
                        SyncStage.PRECHECK,
                        "no remote 'origin' configured; enter the remote URL",
                        kind = FailureKind.REMOTE_MISSING,
                    )
                }
                val notes = ArrayList<Note>()
                if (branch != spec.branch) notes += Note.BranchAdjusted(actual = branch, requested = spec.branch)
                if (configuredUrl != null && configuredUrl != url) notes += Note.RemoteChanged(from = configuredUrl, to = url)
                if (st == RepositoryState.MERGING) notes += Note.UnfinishedMerge
                if (st == RepositoryState.MERGING_RESOLVED) notes += Note.ResolvedMergePending
                repo.ensureSharedStorageConfig(url, branch)
                return Result.Ready(spec.copy(remoteUrl = url, branch = branch), notes)
            }
        } catch (e: Exception) {
            logger.error("adopt failed", e)
            return Result.Failed(SyncStage.PRECHECK, "cannot open repository: ${e.message}", e)
        }
    }
}
