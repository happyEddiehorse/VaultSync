package com.mountriv.vaultsync.gitcore.net

/**
 * 让"取消"立刻掐断正在进行的传输（D-035）。JGit 只在两个对象之间检查取消，下载一个大文件时要等它下完；
 * 中断线程也不行：JSch 的管道只有在缓冲区空着、读线程在等时才理会中断，下载时缓冲区一般是满的（L-040）。
 * 所以把这次任务打开的 SSH 会话 / HTTP 连接都记在这里，[abort] 时直接断开，正在读的那边马上读到连接结束而抛异常。
 *
 * 用法：每个任务一个实例；执行传输的代码包在 [bind] 里，SSH 会话工厂与 HTTP 连接工厂在建连接时用 [current] 登记。
 * [abort] 可以在任何线程调用，断开动作放到后台线程做（JSch 断开时会等它的读线程结束）。之后再登记的连接立即断开。
 */
class Aborter {
    private val actions = ArrayList<() -> Unit>()
    private var aborted = false

    val isAborted: Boolean @Synchronized get() = aborted

    /** 登记一个断开动作；已经取消了的话马上（在后台）执行。 */
    fun register(action: () -> Unit) {
        val runNow = synchronized(this) {
            if (!aborted) actions += action
            aborted
        }
        if (runNow) runInBackground(listOf(action))
    }

    fun abort() {
        val todo = synchronized(this) {
            if (aborted) return
            aborted = true
            ArrayList(actions).also { actions.clear() }
        }
        runInBackground(todo)
    }

    /** 在 [block] 执行期间把本实例设为当前线程的 [current]。 */
    fun <T> bind(block: () -> T): T {
        val previous = CURRENT.get()
        CURRENT.set(this)
        try {
            return block()
        } finally {
            if (previous == null) CURRENT.remove() else CURRENT.set(previous)
        }
    }

    private fun runInBackground(todo: List<() -> Unit>) {
        if (todo.isEmpty()) return
        Thread({ todo.forEach { runCatching(it) } }, "vaultsync-abort").apply { isDaemon = true }.start()
    }

    companion object {
        private val CURRENT = ThreadLocal<Aborter>()

        /** 当前线程正在执行的任务的 [Aborter]；不在 [bind] 里时为 null。 */
        fun current(): Aborter? = CURRENT.get()
    }
}
