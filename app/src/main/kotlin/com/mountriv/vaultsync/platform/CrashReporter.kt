package com.mountriv.vaultsync.platform

import android.os.Build
import com.mountriv.vaultsync.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 崩溃兜底（docs/ANDROID_PLATFORM.md §7）：未捕获异常时把堆栈写进 `filesDir/crash/crash-<时间>.txt`（保留最近 [KEEP] 个），
 * 然后交给系统原来的处理器（系统照常提示"应用已停止"）。下次启动时首页提示查看报告。
 * 只用 java.io，写文件失败也不能影响原处理器被调用。
 */
class CrashReporter(private val dir: File) {

    data class Report(val file: File, val time: Long)

    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                write(thread, error)
            } catch (_: Throwable) {
                // 记录失败也要让系统继续处理
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 还没被用户看过（或忽略）的报告，新的在前。 */
    fun unseen(): List<Report> {
        val seenAt = File(dir, SEEN_MARKER).takeIf { it.isFile }?.lastModified() ?: 0L
        return all().filter { it.time > seenAt }
    }

    fun all(): List<Report> =
        (dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") && f.name.endsWith(".txt") } ?: emptyArray())
            .map { Report(it, it.lastModified()) }
            .sortedByDescending { it.time }

    /** 标记当前所有报告为已读。 */
    fun markSeen() {
        dir.mkdirs()
        val marker = File(dir, SEEN_MARKER)
        marker.writeText(System.currentTimeMillis().toString())
        marker.setLastModified(System.currentTimeMillis())
    }

    fun file(name: String): File? {
        if (name.contains('/') || name.contains('\\')) return null
        return File(dir, name).takeIf { it.isFile }
    }

    private fun write(thread: Thread, error: Throwable) {
        dir.mkdirs()
        val stamp = LocalDateTime.now().format(FILE_FORMAT)
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("VaultSync ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · JGit ${BuildConfig.JGIT_VERSION}")
            pw.println("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL} · ${Build.DISPLAY}")
            pw.println("time=${LocalDateTime.now()} thread=${thread.name}")
            pw.println()
            error.printStackTrace(pw)
        }
        File(dir, "crash-$stamp.txt").writeText(sw.toString(), Charsets.UTF_8)
        all().drop(KEEP).forEach { it.file.delete() }
    }

    companion object {
        const val KEEP = 10
        private const val SEEN_MARKER = ".seen"
        private val FILE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
