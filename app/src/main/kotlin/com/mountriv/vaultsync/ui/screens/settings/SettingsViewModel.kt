package com.mountriv.vaultsync.ui.screens.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.mountriv.vaultsync.AppGraph
import com.mountriv.vaultsync.data.AppSettings
import com.mountriv.vaultsync.platform.ConfigBackup
import com.mountriv.vaultsync.platform.LogStore

/**
 * 设置页的表单（docs/UI.md §8、§9.1，D-029）：默认提交者、提交信息、代理、日志。放在 ViewModel 里，
 * 跳到别的页面再回来、旋转屏幕、切换深色模式或语言都不丢；只在点底部栏的"保存"时写入。
 * 语言等不在表单里的设置不参与比较，保存时保持已保存的值。
 */
class SettingsViewModel : ViewModel() {
    var authorName by mutableStateOf("")
    var authorEmail by mutableStateOf("")
    var commitTemplate by mutableStateOf("")
    var dateFormat by mutableStateOf("")
    var proxyEnabled by mutableStateOf(false)
    var proxyHost by mutableStateOf("")
    var proxyPort by mutableStateOf("")
    var logKeep by mutableStateOf("")
    var debugLogs by mutableStateOf(false)

    var saved by mutableStateOf(false)
        private set

    /** 刚从备份恢复的结果，界面据此提示一次。放在这里：恢复出的语言与当前不同时界面会重建，重建后仍要提示。 */
    var restored by mutableStateOf<ConfigBackup.RestoreResult?>(null)

    init {
        load(AppGraph.settings.state.value)
    }

    /** 表单显示 [s]（打开页面、放弃修改、保存后、从备份恢复后）。 */
    fun load(s: AppSettings) {
        authorName = s.authorName
        authorEmail = s.authorEmail
        commitTemplate = s.commitTemplate
        dateFormat = s.dateFormat
        proxyEnabled = s.proxyEnabled
        proxyHost = s.proxyHost
        proxyPort = if (s.proxyPort > 0) s.proxyPort.toString() else ""
        logKeep = s.logKeep.toString()
        debugLogs = s.debugLogs
    }

    /** 把表单套到 [base] 上；比较与保存用同一套规则（去掉首尾空格、空值按默认值）。 */
    fun applyTo(base: AppSettings): AppSettings {
        val defaults = AppSettings()
        return base.copy(
            authorName = authorName.trim(),
            authorEmail = authorEmail.trim(),
            commitTemplate = commitTemplate.ifBlank { defaults.commitTemplate },
            dateFormat = dateFormat.ifBlank { defaults.dateFormat },
            proxyEnabled = proxyEnabled,
            proxyHost = proxyHost.trim(),
            proxyPort = proxyPort.toIntOrNull() ?: 0,
            logKeep = logKeep.toIntOrNull()?.coerceIn(1, 200) ?: defaults.logKeep,
            debugLogs = debugLogs,
        )
    }

    fun isDirty(saved: AppSettings): Boolean = applyTo(saved) != saved

    /** 开启代理时地址与端口必须合法才能保存。 */
    val proxyInvalid: Boolean
        get() = proxyEnabled && (proxyHost.isBlank() || (proxyPort.toIntOrNull() ?: 0) !in 1..65535)

    fun save() {
        AppGraph.settings.update(::applyTo)
        LogStore.debugEnabled = debugLogs
        // 表单显示实际保存的值（例如日志数量被限制在 1～200、去掉了首尾空格）
        load(AppGraph.settings.state.value)
        saved = true
    }

    fun discard() = load(AppGraph.settings.state.value)

    fun consumeSaved() {
        saved = false
    }
}
