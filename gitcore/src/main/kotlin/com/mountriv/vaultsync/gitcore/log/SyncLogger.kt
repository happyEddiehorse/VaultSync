package com.mountriv.vaultsync.gitcore.log

/**
 * gitcore 的日志出口。app 层实现同时写 Logcat 与应用内日志文件；测试用 [InMemorySyncLogger]。
 * 日志内容不得包含令牌、私钥、口令。
 */
interface SyncLogger {
    fun info(message: String)
    fun warn(message: String, cause: Throwable? = null)
    fun error(message: String, cause: Throwable? = null)
}

/** 收集到内存，供测试断言与调试。 */
class InMemorySyncLogger : SyncLogger {
    val lines: MutableList<String> = ArrayList()

    override fun info(message: String) {
        lines += "I $message"
    }

    override fun warn(message: String, cause: Throwable?) {
        lines += "W $message" + (cause?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: "")
    }

    override fun error(message: String, cause: Throwable?) {
        lines += "E $message" + (cause?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: "")
    }

    override fun toString(): String = lines.joinToString("\n")
}

/** 丢弃一切。 */
object NoopSyncLogger : SyncLogger {
    override fun info(message: String) = Unit
    override fun warn(message: String, cause: Throwable?) = Unit
    override fun error(message: String, cause: Throwable?) = Unit
}
