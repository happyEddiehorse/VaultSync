package com.mountriv.vaultsync

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mountriv.vaultsync.platform.AppLocale
import com.mountriv.vaultsync.sync.SyncService
import com.mountriv.vaultsync.ui.AppRoot
import com.mountriv.vaultsync.ui.theme.VaultSyncTheme

class MainActivity : ComponentActivity() {

    /** 从结果通知点进来时要打开的仓库；被 AppRoot 消费后置空。 */
    private var pendingRepoId by mutableStateOf<String?>(null)

    /** 创建时套用的界面语言（D-030）。 */
    private var appliedLanguage = AppLocale.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        // 界面文字按应用内的语言设置；切换语言后 recreate() 会再走一遍这里
        appliedLanguage = AppGraph.language
        super.attachBaseContext(AppLocale.wrap(newBase, appliedLanguage))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 通知渠道名跟着界面语言（系统设置里显示的名字）
        SyncService.ensureChannels(this)
        // 只在全新启动时读：旋转屏幕、切换语言重建时 intent 还是原来那个，再读一次会又跳到那个库
        if (savedInstanceState == null) pendingRepoId = intent?.getStringExtra(EXTRA_OPEN_REPO_ID)
        setContent {
            VaultSyncTheme {
                AppRoot(
                    openRepoId = pendingRepoId,
                    onOpenRepoConsumed = { pendingRepoId = null },
                    onLanguageChanged = ::applyLanguageIfChanged,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_REPO_ID)?.let { pendingRepoId = it }
    }

    /**
     * 语言设置与创建时套用的不同（设置页切换语言、从备份恢复）就重建界面。
     * 返回栈与 ViewModel 都保留，设置页里没保存的修改不丢。
     */
    private fun applyLanguageIfChanged() {
        if (AppGraph.language != appliedLanguage) recreate()
    }

    companion object {
        const val EXTRA_OPEN_REPO_ID = "openRepoId"
    }
}
