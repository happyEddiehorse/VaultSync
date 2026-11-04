package org.slf4j.impl

import android.util.Log
import com.mountriv.vaultsync.platform.LogStore
import org.slf4j.ILoggerFactory
import org.slf4j.IMarkerFactory
import org.slf4j.Logger
import org.slf4j.helpers.BasicMarkerFactory
import org.slf4j.helpers.MarkerIgnoringBase
import org.slf4j.helpers.MessageFormatter
import org.slf4j.helpers.NOPMDCAdapter
import org.slf4j.spi.LoggerFactoryBinder
import org.slf4j.spi.MDCAdapter
import org.slf4j.spi.MarkerFactoryBinder
import java.util.concurrent.ConcurrentHashMap

/*
 * 自定义 SLF4J 1.7 绑定（D-015）：把 JGit / JSch 的日志同时送到 Logcat（tag `JGit`）和当前活动的会话日志文件。
 * SLF4J 1.7 通过类名 `org.slf4j.impl.StaticLoggerBinder` 反射查找绑定，所以包名与类名不能改；R8 要 keep。
 * 等级：INFO 以上总是写；DEBUG 只在设置里打开"调试日志"时写文件。
 * JSch 每次正常断开连接都会以 INFO 打一条带堆栈的 "Caught an exception, leaving main loop due to Socket closed"，
 * 这里把它降为 DEBUG，免得用户以为出了错。
 */

class StaticLoggerBinder private constructor() : LoggerFactoryBinder {

    private val factory: ILoggerFactory = VaultSyncLoggerFactory()

    override fun getLoggerFactory(): ILoggerFactory = factory

    override fun getLoggerFactoryClassStr(): String = VaultSyncLoggerFactory::class.java.name

    companion object {
        @JvmField
        val SINGLETON = StaticLoggerBinder()

        @JvmStatic
        fun getSingleton(): StaticLoggerBinder = SINGLETON

        /** SLF4J 1.7 的版本检查会读这个字段。 */
        @JvmField
        var REQUESTED_API_VERSION: String = "1.7.36"
    }
}

class StaticMDCBinder private constructor() {
    fun getMDCA(): MDCAdapter = NOPMDCAdapter()

    fun getMDCAdapterClassStr(): String = NOPMDCAdapter::class.java.name

    companion object {
        @JvmField
        val SINGLETON = StaticMDCBinder()

        @JvmStatic
        fun getSingleton(): StaticMDCBinder = SINGLETON
    }
}

class StaticMarkerBinder private constructor() : MarkerFactoryBinder {
    private val factory: IMarkerFactory = BasicMarkerFactory()

    override fun getMarkerFactory(): IMarkerFactory = factory

    override fun getMarkerFactoryClassStr(): String = BasicMarkerFactory::class.java.name

    companion object {
        @JvmField
        val SINGLETON = StaticMarkerBinder()

        @JvmStatic
        fun getSingleton(): StaticMarkerBinder = SINGLETON
    }
}

internal class VaultSyncLoggerFactory : ILoggerFactory {
    private val loggers = ConcurrentHashMap<String, Logger>()

    override fun getLogger(name: String): Logger = loggers.getOrPut(name) { VaultSyncSlf4jLogger(name) }
}

internal class VaultSyncSlf4jLogger(private val fullName: String) : MarkerIgnoringBase() {

    private val shortName = fullName.substringAfterLast('.')

    init {
        name = fullName
    }

    override fun isTraceEnabled(): Boolean = false

    override fun isDebugEnabled(): Boolean = LogStore.debugEnabled

    override fun isInfoEnabled(): Boolean = true

    override fun isWarnEnabled(): Boolean = true

    override fun isErrorEnabled(): Boolean = true

    private fun log(level: Char, priority: Int, msg: String?, t: Throwable?) {
        if (level == 'I' && isDisconnectNoise(msg)) {
            if (isDebugEnabled) log('D', Log.DEBUG, msg, null)
            return
        }
        val text = "$shortName: ${msg ?: ""}"
        if (t != null) Log.println(priority, TAG, text + "\n" + Log.getStackTraceString(t)) else Log.println(priority, TAG, text)
        LogStore.active?.append(level, TAG, text, t)
    }

    private fun fmt(format: String?, arg: Any?): String = MessageFormatter.format(format, arg).message ?: ""

    private fun fmt(format: String?, a: Any?, b: Any?): String = MessageFormatter.format(format, a, b).message ?: ""

    private fun fmtArr(format: String?, args: Array<out Any?>?): Pair<String, Throwable?> {
        val tuple = MessageFormatter.arrayFormat(format, args)
        return (tuple.message ?: "") to tuple.throwable
    }

    // trace：关闭
    override fun trace(msg: String?) = Unit
    override fun trace(format: String?, arg: Any?) = Unit
    override fun trace(format: String?, arg1: Any?, arg2: Any?) = Unit
    override fun trace(format: String?, vararg arguments: Any?) = Unit
    override fun trace(msg: String?, t: Throwable?) = Unit

    override fun debug(msg: String?) { if (isDebugEnabled) log('D', Log.DEBUG, msg, null) }
    override fun debug(format: String?, arg: Any?) { if (isDebugEnabled) log('D', Log.DEBUG, fmt(format, arg), null) }
    override fun debug(format: String?, arg1: Any?, arg2: Any?) { if (isDebugEnabled) log('D', Log.DEBUG, fmt(format, arg1, arg2), null) }
    override fun debug(format: String?, vararg arguments: Any?) {
        if (isDebugEnabled) { val (m, t) = fmtArr(format, arguments); log('D', Log.DEBUG, m, t) }
    }
    override fun debug(msg: String?, t: Throwable?) { if (isDebugEnabled) log('D', Log.DEBUG, msg, t) }

    override fun info(msg: String?) = log('I', Log.INFO, msg, null)
    override fun info(format: String?, arg: Any?) = log('I', Log.INFO, fmt(format, arg), null)
    override fun info(format: String?, arg1: Any?, arg2: Any?) = log('I', Log.INFO, fmt(format, arg1, arg2), null)
    override fun info(format: String?, vararg arguments: Any?) { val (m, t) = fmtArr(format, arguments); log('I', Log.INFO, m, t) }
    override fun info(msg: String?, t: Throwable?) = log('I', Log.INFO, msg, t)

    override fun warn(msg: String?) = log('W', Log.WARN, msg, null)
    override fun warn(format: String?, arg: Any?) = log('W', Log.WARN, fmt(format, arg), null)
    override fun warn(format: String?, vararg arguments: Any?) { val (m, t) = fmtArr(format, arguments); log('W', Log.WARN, m, t) }
    override fun warn(format: String?, arg1: Any?, arg2: Any?) = log('W', Log.WARN, fmt(format, arg1, arg2), null)
    override fun warn(msg: String?, t: Throwable?) = log('W', Log.WARN, msg, t)

    override fun error(msg: String?) = log('E', Log.ERROR, msg, null)
    override fun error(format: String?, arg: Any?) = log('E', Log.ERROR, fmt(format, arg), null)
    override fun error(format: String?, arg1: Any?, arg2: Any?) = log('E', Log.ERROR, fmt(format, arg1, arg2), null)
    override fun error(format: String?, vararg arguments: Any?) { val (m, t) = fmtArr(format, arguments); log('E', Log.ERROR, m, t) }
    override fun error(msg: String?, t: Throwable?) = log('E', Log.ERROR, msg, t)

    private fun isDisconnectNoise(msg: String?): Boolean =
        fullName.startsWith("com.jcraft.jsch") && msg != null &&
            msg.startsWith("Caught an exception, leaving main loop due to") &&
            (msg.contains("Socket closed") || msg.contains("socket closed"))

    companion object {
        private const val TAG = "JGit"
    }
}
