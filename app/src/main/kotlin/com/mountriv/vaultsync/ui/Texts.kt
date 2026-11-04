package com.mountriv.vaultsync.ui

import android.content.Context
import com.mountriv.vaultsync.R
import com.mountriv.vaultsync.data.RepoStatus
import com.mountriv.vaultsync.gitcore.model.FailureKind
import com.mountriv.vaultsync.gitcore.model.SyncStage
import com.mountriv.vaultsync.gitcore.sync.RepoOnboarding
import com.mountriv.vaultsync.sync.JobType
import com.mountriv.vaultsync.sync.Meter
import com.mountriv.vaultsync.sync.SyncManager
import java.util.Locale

/**
 * 把同步状态、失败原因、JGit 任务名等翻译成界面文案（docs/UI.md §10）。通知服务与各页面共用，所以用 [Context] 而不是 Compose 资源函数。
 * 原始英文信息只出现在日志和"技术信息"里。
 */
object Texts {

    fun stage(context: Context, stage: SyncStage?): String = context.getString(
        when (stage) {
            SyncStage.PRECHECK -> R.string.stage_precheck
            SyncStage.COMMIT_LOCAL -> R.string.stage_commit
            SyncStage.FETCH -> R.string.stage_fetch
            SyncStage.MERGE -> R.string.stage_merge
            SyncStage.RESOLVE_CONFLICTS -> R.string.stage_conflicts
            SyncStage.PUSH -> R.string.stage_push
            SyncStage.DONE -> R.string.stage_done
            null -> R.string.stage_preparing
        },
    )

    /** JGit / 服务端进度任务名的界面文字；不认识的原样返回（去掉 `remote:` 前缀）。 */
    fun task(context: Context, title: String): String {
        val t = title.removePrefix("remote:").trim()
        val res = when (t.lowercase(Locale.ROOT)) {
            "receiving objects" -> R.string.task_receiving
            "resolving deltas" -> R.string.task_resolving
            "counting objects", "enumerating objects" -> R.string.task_counting
            "compressing objects" -> R.string.task_compressing
            "writing objects" -> R.string.task_writing
            "finding sources" -> R.string.task_finding_sources
            "getting sizes" -> R.string.task_sizes
            "selecting commits", "building bitmaps" -> R.string.task_indexing
            "updating references" -> R.string.task_updating_refs
            "checking out files" -> R.string.task_checkout
            "verifying files" -> R.string.task_verifying
            "reading changed files" -> R.string.task_staging
            "opening connection" -> R.string.task_opening
            "pack refs" -> R.string.task_pack_refs
            "prune loose objects also found in pack files", "prune loose, unreferenced objects", "finding garbage" -> R.string.task_prune
            else -> return t
        }
        return context.getString(res)
    }

    /** "拉取远端更新 · 接收对象 45%" 这样的一行进度说明。 */
    fun progressLine(context: Context, type: JobType?, stage: SyncStage?, task: String?, completed: Int, total: Int): String {
        val head = when (type) {
            JobType.MAINTAIN -> context.getString(R.string.stage_maintain)
            JobType.CLONE, JobType.INIT, JobType.ADOPT, JobType.RECLONE -> null
            else -> stage(context, stage)
        }
        val tail = task?.let { t ->
            val name = task(context, t)
            if (total > 0) name + " " + context.getString(R.string.percent, (completed * 100L / total).coerceIn(0, 100).toInt()) else name
        }
        return listOfNotNull(head, tail).joinToString(" · ").ifEmpty { context.getString(R.string.stage_preparing) }
    }

    /**
     * 流量那一半："已下载 23.4 MB · 1.5 MB/s"。拉取（含克隆、合并时）看下载，推送看上传；本地步骤、系统不给流量统计、
     * 还没收发过数据时为 null。速度不到 1 KB/s 时不写速度（下载完写文件时只剩零星的连接流量，显示"44 B/s"像是卡住了）。
     */
    fun transferText(context: Context, type: JobType?, stage: SyncStage?, m: Meter): String? {
        val upload = when (type) {
            JobType.CLONE, JobType.RECLONE -> false
            JobType.INIT -> true
            JobType.SYNC -> when (stage) {
                SyncStage.FETCH, SyncStage.MERGE -> false
                SyncStage.PUSH -> true
                else -> return null
            }
            else -> return null
        }
        val bytes = if (upload) m.txBytes else m.rxBytes
        if (bytes <= 0) return null
        val rate = if (upload) m.txPerSec else m.rxPerSec
        val amount = context.getString(if (upload) R.string.meter_uploaded else R.string.meter_downloaded, formatBytes(context, bytes))
        return if (rate >= MIN_RATE_SHOWN) amount + " · " + context.getString(R.string.meter_rate, formatBytes(context, rate)) else amount
    }

    private const val MIN_RATE_SHOWN = 1024L

    /** "用时 1:05"（超过一小时 "1:02:05"）。 */
    fun elapsedText(context: Context, m: Meter): String {
        val sec = m.elapsedMs / 1000
        val clock = if (sec >= 3600) {
            String.format(Locale.ROOT, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)
        } else {
            String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60)
        }
        return context.getString(R.string.meter_elapsed, clock)
    }

    /** 进度下面的第二行：流量（有的话）+ 用时，每秒变化，让人看得出还在干活（D-034）。 */
    fun meterLine(context: Context, type: JobType?, stage: SyncStage?, m: Meter): String =
        listOfNotNull(transferText(context, type, stage, m), elapsedText(context, m)).joinToString(" · ")

    /** 失败原因的短标题（通知、卡片标题用）。 */
    fun failureTitle(context: Context, kind: FailureKind?): String = context.getString(
        when (kind) {
            FailureKind.AUTH_TOKEN -> R.string.fail_auth_token
            FailureKind.AUTH_SSH_KEY -> R.string.fail_auth_ssh
            FailureKind.PERMISSION_DENIED -> R.string.fail_permission
            FailureKind.REPO_NOT_FOUND -> R.string.fail_repo_not_found
            FailureKind.HOST_KEY_CHANGED -> R.string.fail_host_key
            FailureKind.NETWORK -> R.string.fail_network
            FailureKind.TIMEOUT -> R.string.fail_timeout
            FailureKind.PROXY -> R.string.fail_proxy
            FailureKind.TLS -> R.string.fail_tls
            FailureKind.PUSH_REJECTED -> R.string.fail_push_rejected
            FailureKind.PUSH_RACE -> R.string.fail_push_race
            FailureKind.LOCAL_FILES_IN_WAY -> R.string.fail_local_files
            FailureKind.VAULT_MISSING -> R.string.fail_vault_missing
            FailureKind.NOT_A_REPOSITORY -> R.string.fail_not_repo
            FailureKind.ALREADY_A_REPOSITORY -> R.string.fail_already_repo
            FailureKind.CREDENTIAL_MISSING -> R.string.fail_credential_missing
            FailureKind.CREDENTIAL_UNREADABLE -> R.string.fail_credential_unreadable
            FailureKind.REPOSITORY_STATE -> R.string.fail_repo_state
            FailureKind.IDENTITY_MISSING -> R.string.fail_identity
            FailureKind.DIRECTORY_NOT_EMPTY -> R.string.fail_dir_not_empty
            FailureKind.REMOTE_NOT_EMPTY -> R.string.fail_remote_not_empty
            FailureKind.BRANCH_NOT_FOUND -> R.string.fail_branch_not_found
            FailureKind.REMOTE_MISSING -> R.string.fail_remote_missing
            FailureKind.VAULT_EMPTY -> R.string.fail_vault_empty
            FailureKind.STORAGE -> R.string.fail_storage
            FailureKind.UNKNOWN, null -> R.string.fail_unknown
        },
    )

    /** 失败原因的说明：可能原因 + 建议操作。[proxyEnabled] 为真时，网络类问题额外提示检查代理。 */
    fun failureHint(context: Context, kind: FailureKind?, proxyEnabled: Boolean): String {
        val base = context.getString(
            when (kind) {
                FailureKind.AUTH_TOKEN -> R.string.fail_auth_token_hint
                FailureKind.AUTH_SSH_KEY -> R.string.fail_auth_ssh_hint
                FailureKind.PERMISSION_DENIED -> R.string.fail_permission_hint
                FailureKind.REPO_NOT_FOUND -> R.string.fail_repo_not_found_hint
                FailureKind.HOST_KEY_CHANGED -> R.string.fail_host_key_hint
                FailureKind.NETWORK -> R.string.fail_network_hint
                FailureKind.TIMEOUT -> R.string.fail_timeout_hint
                FailureKind.PROXY -> R.string.fail_proxy_hint
                FailureKind.TLS -> R.string.fail_tls_hint
                FailureKind.PUSH_REJECTED -> R.string.fail_push_rejected_hint
                FailureKind.PUSH_RACE -> R.string.fail_push_race_hint
                FailureKind.LOCAL_FILES_IN_WAY -> R.string.fail_local_files_hint
                FailureKind.VAULT_MISSING -> R.string.fail_vault_missing_hint
                FailureKind.NOT_A_REPOSITORY -> R.string.fail_not_repo_hint
                FailureKind.ALREADY_A_REPOSITORY -> R.string.fail_already_repo_hint
                FailureKind.CREDENTIAL_MISSING -> R.string.fail_credential_missing_hint
                FailureKind.CREDENTIAL_UNREADABLE -> R.string.fail_credential_unreadable_hint
                FailureKind.REPOSITORY_STATE -> R.string.fail_repo_state_hint
                FailureKind.IDENTITY_MISSING -> R.string.fail_identity_hint
                FailureKind.DIRECTORY_NOT_EMPTY -> R.string.fail_dir_not_empty_hint
                FailureKind.REMOTE_NOT_EMPTY -> R.string.fail_remote_not_empty_hint
                FailureKind.BRANCH_NOT_FOUND -> R.string.fail_branch_not_found_hint
                FailureKind.REMOTE_MISSING -> R.string.fail_remote_missing_hint
                FailureKind.VAULT_EMPTY -> R.string.fail_vault_empty_hint
                FailureKind.STORAGE -> R.string.fail_storage_hint
                FailureKind.UNKNOWN, null -> R.string.fail_unknown_hint
            },
        )
        val networkish = kind == FailureKind.NETWORK || kind == FailureKind.TIMEOUT || kind == FailureKind.TLS
        return if (proxyEnabled && networkish) base + "\n" + context.getString(R.string.fail_check_proxy_hint) else base
    }

    fun failureKindOf(name: String?): FailureKind? = name?.let { n -> FailureKind.entries.firstOrNull { it.name == n } }

    fun stageOf(name: String?): SyncStage? = name?.let { n -> SyncStage.entries.firstOrNull { it.name == n } }

    /** 一次同步成功后的摘要："提交 3 个文件 · 已合并远端更新 · 已推送 · 用时 5.2 秒"。 */
    fun successSummary(context: Context, committed: Int, mergeStatus: String?, pushStatus: String, durationMs: Long): String {
        val parts = ArrayList<String>()
        parts += if (committed > 0) context.resources.getQuantityString(R.plurals.summary_committed, committed, committed) else context.getString(R.string.summary_no_local_changes)
        parts += context.getString(
            when (mergeStatus) {
                null -> R.string.summary_merge_none
                "FAST_FORWARD" -> R.string.summary_merge_ff
                "MERGED" -> R.string.summary_merge_merged
                else -> R.string.summary_merge_up_to_date
            },
        )
        parts += context.getString(
            when (pushStatus) {
                "OK" -> R.string.summary_push_ok
                else -> R.string.summary_push_nothing
            },
        )
        parts += context.getString(R.string.summary_duration, formatDuration(context, durationMs))
        return parts.joinToString(" · ")
    }

    /** 上次同步结果的一行说明（列表卡片 / 详情页，进程重启后也可用）。 */
    fun statusLine(context: Context, status: RepoStatus): String = when (status.result) {
        RepoStatus.SUCCESS -> if (status.committed != null && status.pushStatus != null) {
            successSummary(context, status.committed, status.mergeStatus, status.pushStatus, status.durationMs ?: 0)
        } else {
            context.getString(R.string.result_success)
        }
        RepoStatus.CONFLICTS -> status.conflictCount?.let { context.resources.getQuantityString(R.plurals.result_conflicts_n, it, it) }
            ?: context.getString(R.string.result_conflicts)
        RepoStatus.CANCELLED -> context.getString(R.string.result_cancelled)
        RepoStatus.FAILED -> context.getString(R.string.result_failed_reason, failureTitle(context, failureKindOf(status.failureKind)))
        else -> status.result
    }

    fun jobVerb(context: Context, type: JobType?): String = context.getString(
        when (type) {
            JobType.CLONE, JobType.RECLONE -> R.string.job_cloning
            JobType.INIT -> R.string.job_initializing
            JobType.ADOPT -> R.string.job_adopting
            JobType.MAINTAIN -> R.string.job_maintaining
            JobType.SYNC, null -> R.string.job_syncing
        },
    )

    /** 结果通知里一个任务的结果。 */
    fun jobResult(context: Context, r: SyncManager.JobResult): String = when (r.outcome) {
        SyncManager.JobResult.Outcome.SUCCESS -> when (r.type) {
            JobType.SYNC -> r.summary?.let { syncDone(context, it.localFilesCommitted, it.mergeStatus, it.pushStatus) }
                ?: context.getString(R.string.result_success)
            JobType.MAINTAIN -> if (r.freedBytes > 0) {
                context.getString(R.string.notif_result_maintained_freed, formatBytes(context, r.freedBytes))
            } else {
                context.getString(R.string.notif_result_maintained)
            }
            JobType.CLONE, JobType.INIT, JobType.ADOPT -> context.getString(R.string.notif_result_added)
            JobType.RECLONE -> context.getString(R.string.notif_result_recloned)
        }
        SyncManager.JobResult.Outcome.CONFLICTS -> context.resources.getQuantityString(R.plurals.notif_result_conflicts, r.conflictCount, r.conflictCount)
        SyncManager.JobResult.Outcome.FAILED -> context.getString(R.string.notif_result_failed, failureTitle(context, r.failureKind))
    }

    /**
     * 一次成功同步做了什么（结果通知用，不含用时，D-028）："提交了 2 个文件 · 已合并远端更新"；
     * 既没有提交也没有拉取/合并时，推送过写"已推送"，否则"没有变化"。
     */
    fun syncDone(context: Context, committed: Int, mergeStatus: String?, pushStatus: String): String {
        val parts = ArrayList<String>()
        if (committed > 0) parts += context.resources.getQuantityString(R.plurals.summary_committed, committed, committed)
        when (mergeStatus) {
            "FAST_FORWARD" -> parts += context.getString(R.string.summary_merge_ff)
            "MERGED" -> parts += context.getString(R.string.summary_merge_merged)
        }
        if (parts.isEmpty()) parts += context.getString(if (pushStatus == "OK") R.string.summary_push_ok else R.string.summary_no_changes)
        return parts.joinToString(" · ")
    }

    /** 名称列表："A、B、C"（中文）/ "A, B, C"（英文）；分隔符来自资源，代码里不写死标点。 */
    fun joinList(context: Context, items: List<String>): String = items.joinToString(context.getString(R.string.list_separator))

    fun onboardNote(context: Context, note: RepoOnboarding.Note): String = when (note) {
        RepoOnboarding.Note.RemoteEmpty -> context.getString(R.string.note_remote_empty)
        RepoOnboarding.Note.GitignoreCreated -> context.getString(R.string.note_gitignore_created)
        is RepoOnboarding.Note.BranchAdjusted -> context.getString(R.string.note_branch_adjusted, note.actual, note.requested)
        is RepoOnboarding.Note.RemoteChanged -> context.getString(R.string.note_remote_changed, note.to)
        RepoOnboarding.Note.UnfinishedMerge -> context.getString(R.string.note_unfinished_merge)
        RepoOnboarding.Note.ResolvedMergePending -> context.getString(R.string.note_resolved_merge)
    }
}
