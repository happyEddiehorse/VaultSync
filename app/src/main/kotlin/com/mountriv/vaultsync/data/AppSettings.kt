package com.mountriv.vaultsync.data

import com.mountriv.vaultsync.gitcore.model.RepoSpec
import com.mountriv.vaultsync.platform.AppLocale
import kotlinx.serialization.Serializable

/** 全局设置（`filesDir/config/settings.json`）。字段都有默认值，旧版本文件缺字段时用默认值。 */
@Serializable
data class AppSettings(
    val authorName: String = "",
    val authorEmail: String = "",
    val commitTemplate: String = RepoSpec.DEFAULT_COMMIT_TEMPLATE,
    val dateFormat: String = RepoSpec.DEFAULT_DATE_FORMAT,
    val proxyEnabled: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    /** 每个仓库保留的日志文件数。 */
    val logKeep: Int = 20,
    /** 是否把 JGit / JSch 的 DEBUG 日志也写进文件。 */
    val debugLogs: Boolean = false,
    /** 界面语言（D-030、D-040）：`system`（跟随系统）或 [AppLocale.languages] 里的标签。设置页里即时生效，不经过保存栏。 */
    val language: String = AppLocale.SYSTEM,
    /** 提交详情（diff）页与冲突文件页的显示选项（D-038），两页分别记住；在页面菜单里即时生效，不经过保存栏。 */
    val diffView: ViewOptions = ViewOptions(),
    val conflictView: ViewOptions = ViewOptions(),
) {
    val hasIdentity: Boolean get() = authorName.isNotBlank() && authorEmail.isNotBlank()
}

/**
 * 上一次导出配置（`filesDir/config/backup-status.json`，D-039），备份卡片据此显示"上次导出"。是这台手机的状态，**不进备份文件**。
 * [unreadableTokens]（库名）、[unreadableKeys]（密钥名）是那次本机解不开、没能导出的。
 */
@Serializable
data class BackupStatus(
    val lastExportAt: Long = 0,
    val withSecrets: Boolean = false,
    val unreadableTokens: List<String> = emptyList(),
    val unreadableKeys: List<String> = emptyList(),
)

/** 显示文件内容的页面的两个开关（D-038）：自动换行、显示行号，默认都开。 */
@Serializable
data class ViewOptions(
    val wrapLines: Boolean = true,
    val lineNumbers: Boolean = true,
)

/**
 * 每个仓库上一次同步的结果（`filesDir/config/status.json`），用于列表卡片与详情页展示。
 * [message] 是写日志用的英文摘要；界面用后面的结构化字段翻译（M5 起新增，旧文件里没有时为 null）。
 */
@Serializable
data class RepoStatus(
    val repoId: String,
    val lastSyncAt: Long,
    /** SUCCESS / CONFLICTS / FAILED / CANCELLED */
    val result: String,
    val message: String = "",
    /** 对应的日志文件名（不含目录）。 */
    val logFile: String? = null,
    /** 失败或取消时所在的阶段（`SyncStage` 名）。 */
    val stage: String? = null,
    /** 失败原因分类（`FailureKind` 名）。 */
    val failureKind: String? = null,
    /** 成功时：本次提交的文件数、合并与推送结果（`SyncSummary` 的字段）、耗时。 */
    val committed: Int? = null,
    val mergeStatus: String? = null,
    val pushStatus: String? = null,
    val durationMs: Long? = null,
    /** 冲突时：冲突文件数。 */
    val conflictCount: Int? = null,
) {
    companion object {
        const val SUCCESS = "SUCCESS"
        const val CONFLICTS = "CONFLICTS"
        const val FAILED = "FAILED"
        const val CANCELLED = "CANCELLED"
    }
}
