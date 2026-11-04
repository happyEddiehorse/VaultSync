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
import com.mountriv.vaultsync.gitcore.model.SyncOutcome
import com.mountriv.vaultsync.gitcore.model.SyncStage
import com.mountriv.vaultsync.gitcore.model.SyncSummary
import com.mountriv.vaultsync.gitcore.net.Aborter
import com.mountriv.vaultsync.gitcore.repo.VaultRepository
import org.eclipse.jgit.api.MergeResult.MergeStatus
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.transport.RemoteRefUpdate
import java.io.File
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * 同步状态机：PRECHECK → COMMIT_LOCAL → FETCH → MERGE → PUSH → DONE（docs/SYNC_ENGINE.md §5）。
 * 推送被拒（远端又有新提交）时回到 FETCH，最多重试 [maxPushRetries] 次。
 * 同一仓库同一时刻只允许一个同步；实例可全局共享。
 */
class SyncEngine(
    private val deviceName: String = "android",
    private val timeoutSec: Int = VaultRepository.DEFAULT_TIMEOUT_SEC,
    private val maxPushRetries: Int = 2,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
) {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /** 测试钩子：每次 push 之前调用，用来模拟"推送时远端又有新提交"。 */
    internal var beforePushHook: (() -> Unit)? = null

    fun isRunning(repoId: String): Boolean = locks[repoId]?.isLocked == true

    /**
     * 执行一次完整同步。阻塞调用，调用方负责放到 IO 线程。
     * [defaultIdentity] 在 [RepoSpec.identity] 为空时使用；[cancel] 返回 true 时尽快停止并返回 [SyncOutcome.Cancelled]。
     * 传了 [aborter] 时，本次打开的 SSH 会话 / HTTP 连接都登记在它上面，调用方取消时 `abort()` 立即断开（D-035）。
     */
    fun sync(
        spec: RepoSpec,
        secrets: SecretProvider,
        defaultIdentity: GitIdentity,
        logger: SyncLogger,
        progress: ProgressSink = ProgressSink.None,
        aborter: Aborter = Aborter(),
        cancel: () -> Boolean = { false },
    ): SyncOutcome {
        val lock = locks.computeIfAbsent(spec.id) { ReentrantLock() }
        if (!lock.tryLock()) {
            return SyncOutcome.Failed(SyncStage.PRECHECK, "a sync is already running for '${spec.name}'")
        }
        try {
            return aborter.bind { Run(spec, secrets, spec.identity ?: defaultIdentity, logger, progress, cancel).execute() }
        } finally {
            lock.unlock()
        }
    }

    private class CancelledException(val stage: SyncStage) : RuntimeException("cancelled at $stage")

    private inner class Run(
        private val spec: RepoSpec,
        private val secrets: SecretProvider,
        private val identity: GitIdentity,
        private val logger: SyncLogger,
        private val progress: ProgressSink,
        private val cancel: () -> Boolean,
    ) {
        private var stage = SyncStage.PRECHECK
        private val started = System.currentTimeMillis()
        private val monitor = JGitProgress(progress, cancel)

        private var localCommit: ObjectId? = null
        private var localFiles = 0
        private var mergeStatus: String? = null
        private var pushStatus = "NOT_ATTEMPTED"
        private var retries = 0
        private var auth: TransportAuth = TransportAuth.NONE

        fun execute(): SyncOutcome = try {
            run()
        } catch (e: CancelledException) {
            logger.warn("sync cancelled at ${e.stage}")
            SyncOutcome.Cancelled(e.stage)
        } catch (e: SyncException) {
            logger.error("sync failed: ${e.message}", e.cause)
            SyncOutcome.Failed(e.stage, e.detail, e.cause, e.kind ?: FailureClassifier.classify(e.detail, e.cause))
        } catch (e: Exception) {
            if (cancel()) {
                logger.warn("sync cancelled at $stage (${e.javaClass.simpleName})")
                SyncOutcome.Cancelled(stage)
            } else {
                logger.error("sync failed at $stage: $e", e)
                SyncOutcome.Failed(stage, e.message ?: e.toString(), e)
            }
        } finally {
            auth.dispose()
        }

        private fun enter(next: SyncStage) {
            stage = next
            progress.onStage(next)
            if (next != SyncStage.DONE && cancel()) throw CancelledException(next)
            logger.info("stage $next")
        }

        private fun run(): SyncOutcome {
            enter(SyncStage.PRECHECK)
            logger.info("sync '${spec.name}' branch=${spec.branch} remote=${redact(spec.remoteUrl)}")
            val dir = File(spec.localPath)
            if (!dir.isDirectory) {
                throw SyncException(SyncStage.PRECHECK, "vault directory not found: ${spec.localPath}", kind = FailureKind.VAULT_MISSING)
            }
            if (!VaultRepository.isRepository(dir)) {
                throw SyncException(
                    SyncStage.PRECHECK,
                    "not a git repository (add the vault first): ${spec.localPath}",
                    kind = FailureKind.NOT_A_REPOSITORY,
                )
            }
            auth = CredentialsFactory.forSpec(spec, secrets, logger)
            logger.info("auth: $auth")

            VaultRepository.open(dir, logger).use { repo ->
                if (repo.hasIndexLock()) {
                    logger.warn("stale index.lock found, removing")
                    repo.removeIndexLock()
                }
                when (val st = repo.state) {
                    RepositoryState.SAFE -> Unit
                    RepositoryState.MERGING -> {
                        val conflicts = repo.conflictingPaths()
                        if (conflicts.isNotEmpty()) {
                            logger.warn("unfinished merge with ${conflicts.size} conflicting file(s)")
                            stage = SyncStage.RESOLVE_CONFLICTS
                            return SyncOutcome.Conflicts(conflicts)
                        }
                    }
                    RepositoryState.MERGING_RESOLVED -> {
                        logger.info("completing previously resolved merge")
                        repo.completeMerge(identity)
                    }
                    else -> throw SyncException(
                        SyncStage.PRECHECK,
                        "unsupported repository state $st; finish it on the desktop first",
                        kind = FailureKind.REPOSITORY_STATE,
                    )
                }
                val branch = repo.currentBranch
                    ?: throw SyncException(
                        SyncStage.PRECHECK,
                        "HEAD is detached; check out branch '${spec.branch}' first",
                        kind = FailureKind.REPOSITORY_STATE,
                    )
                if (branch != spec.branch) {
                    throw SyncException(
                        SyncStage.PRECHECK,
                        "checked-out branch '$branch' differs from configured branch '${spec.branch}'",
                        kind = FailureKind.REPOSITORY_STATE,
                    )
                }
                repo.ensureSharedStorageConfig(spec.remoteUrl, spec.branch)

                enter(SyncStage.COMMIT_LOCAL)
                localCommit = repo.commitAll(identity, monitor, cancel) { paths ->
                    localFiles = paths.size
                    CommitMessageTemplate.render(spec.commitTemplate, spec.dateFormat, clock(), deviceName, paths)
                }

                while (true) {
                    enter(SyncStage.FETCH)
                    repo.fetch(auth, monitor, timeoutSec)
                    val remoteId = repo.remoteTrackingId(branch)

                    enter(SyncStage.MERGE)
                    val head = repo.headId()
                    when {
                        remoteId == null -> {
                            mergeStatus = null
                            logger.info("remote branch '$branch' does not exist yet; nothing to merge")
                        }
                        head == null -> {
                            // 本地分支还没有提交（克隆自空远端后远端才有了内容）：直接采用远端
                            repo.resetHardTo(remoteId)
                            repo.refreshIndex(monitor)
                            mergeStatus = MergeStatus.FAST_FORWARD.name
                            logger.info("local branch was unborn; checked out remote ${remoteId.name.take(7)}")
                        }
                        remoteId == head || repo.isAncestor(remoteId, head) -> {
                            mergeStatus = MergeStatus.ALREADY_UP_TO_DATE.name
                        }
                        else -> {
                            val result = try {
                                repo.mergeRemote(branch, "Merge remote-tracking branch 'origin/$branch'", monitor)
                            } catch (e: Exception) {
                                // 不是冲突而是中途出错（或取消）：回到合并前，免得留下检出了一半、却像"冲突已全部处理"的合并（L-036）
                                try {
                                    repo.rollBackFailedMerge(head, remoteId)
                                } catch (rollback: Exception) {
                                    logger.error("rolling back the failed merge failed", rollback)
                                    e.addSuppressed(rollback)
                                }
                                throw e
                            }
                            mergeStatus = result.mergeStatus.name
                            logger.info("merge: ${result.mergeStatus}")
                            when (result.mergeStatus) {
                                MergeStatus.FAST_FORWARD, MergeStatus.MERGED -> repo.refreshIndex(monitor)
                                MergeStatus.ALREADY_UP_TO_DATE -> Unit
                                MergeStatus.CONFLICTING -> {
                                    val conflicts = repo.conflictingPaths()
                                    logger.warn("merge conflicts in ${conflicts.size} file(s): $conflicts")
                                    stage = SyncStage.RESOLVE_CONFLICTS
                                    return SyncOutcome.Conflicts(conflicts)
                                }
                                MergeStatus.FAILED -> throw SyncException(
                                    SyncStage.MERGE,
                                    "merge failed: " + result.failingPaths.entries.joinToString { "${it.key} (${it.value})" },
                                    kind = FailureKind.LOCAL_FILES_IN_WAY,
                                )
                                MergeStatus.CHECKOUT_CONFLICT -> throw SyncException(
                                    SyncStage.MERGE,
                                    "checkout conflict: ${result.checkoutConflicts}",
                                    kind = FailureKind.LOCAL_FILES_IN_WAY,
                                )
                                else -> throw SyncException(SyncStage.MERGE, "unexpected merge status ${result.mergeStatus}")
                            }
                        }
                    }

                    enter(SyncStage.PUSH)
                    val headNow = repo.headId()
                    if (headNow == null) {
                        pushStatus = "NOTHING_TO_PUSH"
                        logger.info("no local commits yet; nothing to push")
                        break
                    }
                    if (remoteId != null && remoteId == headNow) {
                        pushStatus = RemoteRefUpdate.Status.UP_TO_DATE.name
                        logger.info("remote already at ${headNow.name.take(7)}; nothing to push")
                        break
                    }
                    beforePushHook?.invoke()
                    val outcome = repo.push(branch, auth, monitor, timeoutSec)
                    pushStatus = outcome.status.name
                    logger.info("push: ${outcome.status}${outcome.message?.let { " ($it)" } ?: ""}")
                    when (outcome.status) {
                        RemoteRefUpdate.Status.OK, RemoteRefUpdate.Status.UP_TO_DATE -> break
                        RemoteRefUpdate.Status.REJECTED_NONFASTFORWARD, RemoteRefUpdate.Status.REJECTED_REMOTE_CHANGED -> {
                            if (retries < maxPushRetries) {
                                retries++
                                logger.warn("push rejected (${outcome.status}); fetching again ($retries/$maxPushRetries)")
                                continue
                            }
                            throw SyncException(
                                SyncStage.PUSH,
                                "push rejected after $retries retries: ${outcome.message ?: outcome.status}",
                                kind = FailureKind.PUSH_RACE,
                            )
                        }
                        else -> throw SyncException(
                            SyncStage.PUSH,
                            "push failed: ${outcome.status}${outcome.message?.let { " – $it" } ?: ""}",
                            kind = FailureClassifier.classify(outcome.message, null)
                                .takeIf { it != FailureKind.UNKNOWN } ?: FailureKind.PUSH_REJECTED,
                        )
                    }
                }

                enter(SyncStage.DONE)
                val summary = SyncSummary(
                    localCommitId = localCommit?.name,
                    localFilesCommitted = localFiles,
                    mergeStatus = mergeStatus,
                    pushStatus = pushStatus,
                    retries = retries,
                    durationMs = System.currentTimeMillis() - started,
                )
                logger.info("sync done: $summary")
                return SyncOutcome.Success(summary)
            }
        }
    }

    companion object {
        /** 去掉 URL 里的用户信息，日志不得泄露令牌。 */
        fun redact(url: String): String = url.replace(Regex("//[^@/]+@"), "//***@")
    }
}
