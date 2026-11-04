package com.mountriv.vaultsync.ui.screens.conflicts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mountriv.vaultsync.AppGraph
import com.mountriv.vaultsync.gitcore.merge.Side
import com.mountriv.vaultsync.gitcore.model.RepoSpec
import com.mountriv.vaultsync.sync.SyncManager
import com.mountriv.vaultsync.sync.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 冲突列表页的状态（docs/UI.md §5）。所有仓库操作在 IO 线程上跑；[busy] 期间禁用按钮。
 * 数据每次进入页面 / 从文件页返回时重新从 index 读取（[refresh]），不缓存。
 */
class ConflictsViewModel(private val repoId: String) : ViewModel() {

    val spec: RepoSpec? get() = AppGraph.repos.get(repoId)

    var list by mutableStateOf<SyncManager.ConflictList?>(null)
        private set

    var loading by mutableStateOf(true)
        private set

    var busy by mutableStateOf(false)
        private set

    /** 最近一次操作失败的说明；正常为 null。 */
    var error by mutableStateOf<String?>(null)

    fun refresh() {
        val s = spec ?: return
        // 同步中 SyncManager 会拒绝打开仓库；等同步结束由页面再触发一次
        if (AppGraph.syncManager.stateOf(repoId).isBusy) return
        viewModelScope.launch {
            loading = true
            val r = withContext(Dispatchers.IO) { runCatching { AppGraph.syncManager.conflictList(s) } }
            r.onSuccess { list = it; error = null }.onFailure { error = it.message ?: it.toString() }
            loading = false
        }
    }

    fun resolveAll(side: Side) = perform { AppGraph.syncManager.resolveAll(it, side) }

    fun resolveTakeSide(path: String, side: Side) = perform { AppGraph.syncManager.resolveTakeSide(it, path, side) }

    fun resolveDelete(path: String) = perform { AppGraph.syncManager.resolveDelete(it, path) }

    /** 完成合并提交，然后入队一次同步（推送）。成功时回调 [onDone]（主线程）。 */
    fun completeMergeAndSync(onDone: () -> Unit) {
        val s = spec ?: return
        viewModelScope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) { runCatching { AppGraph.syncManager.completeMerge(s) } }
            busy = false
            r.onSuccess {
                error = null
                if (AppGraph.syncManager.enqueueSync(s.id)) SyncService.start(AppGraph.appContext)
                onDone()
            }.onFailure {
                AppGraph.logger.error("complete merge failed for '${s.name}'", it)
                error = it.message ?: it.toString()
                refresh()
            }
        }
    }

    private fun perform(block: (RepoSpec) -> Unit) {
        val s = spec ?: return
        viewModelScope.launch {
            busy = true
            val r = withContext(Dispatchers.IO) { runCatching { block(s) } }
            busy = false
            r.onFailure {
                AppGraph.logger.error("conflict action failed for '${s.name}'", it)
                error = it.message ?: it.toString()
            }
            refresh()
        }
    }
}
