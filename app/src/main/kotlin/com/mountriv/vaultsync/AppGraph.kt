package com.mountriv.vaultsync

import android.content.Context
import com.mountriv.vaultsync.data.BackupStatusStore
import com.mountriv.vaultsync.data.RepoStore
import com.mountriv.vaultsync.data.SettingsStore
import com.mountriv.vaultsync.data.StatusStore
import com.mountriv.vaultsync.gitcore.log.SyncLogger
import com.mountriv.vaultsync.platform.AndroidSyncLogger
import com.mountriv.vaultsync.platform.AppLocale
import com.mountriv.vaultsync.platform.ConfigBackup
import com.mountriv.vaultsync.platform.CrashReporter
import com.mountriv.vaultsync.platform.KeystoreSecretStore
import com.mountriv.vaultsync.platform.LogStore
import com.mountriv.vaultsync.platform.SshKeyStore
import com.mountriv.vaultsync.sync.SyncManager
import java.io.File

/**
 * 手动依赖注入根。进程内单例，由 [VaultSyncApp] 初始化（docs/DEVELOPMENT.md §4）。
 * 所有配置与秘密只放 `filesDir`（D-019）：`config/`、`secrets/`、`logs/`、`crash/`、`githome/`。
 */
object AppGraph {

    lateinit var appContext: Context
        private set

    lateinit var logger: SyncLogger
        private set

    lateinit var repos: RepoStore
        private set

    lateinit var settings: SettingsStore
        private set

    lateinit var statuses: StatusStore
        private set

    lateinit var backupStatus: BackupStatusStore
        private set

    lateinit var secrets: KeystoreSecretStore
        private set

    lateinit var sshKeys: SshKeyStore
        private set

    lateinit var logs: LogStore
        private set

    lateinit var crashes: CrashReporter
        private set

    lateinit var backup: ConfigBackup
        private set

    lateinit var syncManager: SyncManager
        private set

    /** 当前的界面语言设置（D-030）；[init] 之前（正常不会发生）按跟随系统处理。 */
    val language: String
        get() = if (::settings.isInitialized) settings.state.value.language else AppLocale.SYSTEM

    /** 崩溃报告要最先装好（在读取任何配置之前），这样初始化过程中的崩溃也能记下来。 */
    fun installCrashReporter(context: Context) {
        crashes = CrashReporter(File(context.filesDir, "crash")).also { it.install() }
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        logger = AndroidSyncLogger()
        val files = appContext.filesDir
        val configDir = File(files, "config")
        repos = RepoStore(configDir, logger).also { it.load() }
        settings = SettingsStore(configDir, logger).also { it.load() }
        statuses = StatusStore(configDir, logger).also { it.load() }
        backupStatus = BackupStatusStore(configDir, logger).also { it.load() }
        secrets = KeystoreSecretStore(File(files, "secrets"), logger)
        // 迁移 / 找回的密钥会用这两个名字存下来，按所选语言取（用户可见文案不从 Application context 直接取，D-030）
        val localized = AppLocale.wrap(appContext, settings.state.value.language)
        sshKeys = SshKeyStore(
            secrets,
            configDir,
            logger,
            SshKeyStore.Names(
                migrated = localized.getString(R.string.ssh_key_name_migrated),
                recovered = localized.getString(R.string.ssh_key_name_recovered),
            ),
        )
        logs = LogStore(File(files, "logs"))
        syncManager = SyncManager(repos, settings, statuses, secrets, sshKeys, logs, logger)
        // 有任务在同步、克隆、整理或排队时不能恢复（D-039）
        backup = ConfigBackup(repos, settings, secrets, sshKeys, backupStatus, logger) { syncManager.hasPendingWork }
        LogStore.debugEnabled = settings.state.value.debugLogs
    }
}
