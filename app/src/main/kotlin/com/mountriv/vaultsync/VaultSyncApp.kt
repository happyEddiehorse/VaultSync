package com.mountriv.vaultsync

import android.app.Application
import com.mountriv.vaultsync.gitcore.GitEnvironment
import java.io.File

class VaultSyncApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppGraph.installCrashReporter(this)
        // 进程级 JGit 环境：user.home、系统配置、缓存参数（见 docs/SYNC_ENGINE.md §2）
        GitEnvironment.install(File(filesDir, "githome"))
        AppGraph.init(this)
    }
}
