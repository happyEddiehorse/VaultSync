package com.mountriv.vaultsync.gitcore.sync

import com.mountriv.vaultsync.gitcore.model.ProgressSink
import org.eclipse.jgit.lib.ProgressMonitor

/** 把 JGit 的 [ProgressMonitor] 转发到 [ProgressSink]，并用 [cancel] 驱动 JGit 的取消检查。 */
class JGitProgress(
    private val sink: ProgressSink,
    private val cancel: () -> Boolean,
) : ProgressMonitor {

    private var completed = 0

    override fun start(totalTasks: Int) = Unit

    override fun beginTask(title: String, totalWork: Int) {
        completed = 0
        sink.onTask(title, totalWork)
    }

    override fun update(completedUnits: Int) {
        completed += completedUnits
        sink.onProgress(completed)
    }

    override fun endTask() {
        sink.onTaskDone()
    }

    override fun isCancelled(): Boolean = cancel()
}
