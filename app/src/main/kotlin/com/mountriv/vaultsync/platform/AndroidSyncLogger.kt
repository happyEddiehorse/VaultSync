package com.mountriv.vaultsync.platform

import android.util.Log
import com.mountriv.vaultsync.gitcore.log.SyncLogger
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 应用日志（docs/ANDROID_PLATFORM.md §7）。
 *
 * - [AndroidSyncLogger]：全局日志器，写 Logcat（tag `VaultSync`），并转发到当前活动的会话日志文件（若有）。
 * - [LogStore]：`filesDir/logs/<repoId>/<yyyyMMdd-HHmmss>.log` 的创建、列举、清理；同一时刻至多一个活动会话
 *   （同步是串行的），SLF4J 绑定（JGit/JSch 日志）也写到活动会话。
 * - 日志内容不得包含令牌、私钥、口令。
 */
class AndroidSyncLogger(private val tag: String = TAG) : SyncLogger {

    override fun info(message: String) {
        Log.i(tag, message)
        LogStore.active?.append('I', tag, message, null)
    }

    override fun warn(message: String, cause: Throwable?) {
        Log.w(tag, message, cause)
        LogStore.active?.append('W', tag, message, cause)
    }

    override fun error(message: String, cause: Throwable?) {
        Log.e(tag, message, cause)
        LogStore.active?.append('E', tag, message, cause)
    }

    companion object {
        const val TAG = "VaultSync"
    }
}

/** 一次同步/接入的日志文件。 */
class SessionLog internal constructor(val repoId: String, val file: File) : SyncLogger {

    private val writer = file.printWriter(Charsets.UTF_8)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    @Volatile
    var closed = false
        private set

    @Synchronized
    fun append(level: Char, tag: String, message: String, cause: Throwable?) {
        if (closed) return
        writer.print(LocalDateTime.now().format(timeFormat))
        writer.print(' ')
        writer.print(level)
        writer.print(' ')
        writer.print(tag)
        writer.print(": ")
        writer.println(message)
        if (cause != null) {
            val sw = StringWriter()
            cause.printStackTrace(PrintWriter(sw))
            writer.println(sw.toString().trimEnd())
        }
        writer.flush()
    }

    override fun info(message: String) {
        Log.i(AndroidSyncLogger.TAG, message)
        append('I', AndroidSyncLogger.TAG, message, null)
    }

    override fun warn(message: String, cause: Throwable?) {
        Log.w(AndroidSyncLogger.TAG, message, cause)
        append('W', AndroidSyncLogger.TAG, message, cause)
    }

    override fun error(message: String, cause: Throwable?) {
        Log.e(AndroidSyncLogger.TAG, message, cause)
        append('E', AndroidSyncLogger.TAG, message, cause)
    }

    /** 写入结果行并关闭。 */
    @Synchronized
    fun finish(result: String, detail: String) {
        if (closed) return
        append('I', AndroidSyncLogger.TAG, "RESULT $result $detail", null)
        closed = true
        writer.close()
        if (LogStore.active === this) LogStore.active = null
    }
}

class LogStore(private val root: File) {

    /** [op]：`sync` / `clone` / `init` / `adopt` / `maintain`（取自日志第二行的 `op=`）。 */
    data class Entry(val repoId: String, val file: File, val startedAt: Long, val result: String?, val op: String?)

    /** 开始一次会话：新建文件并设为活动会话。 */
    fun begin(repoId: String, header: List<String>): SessionLog {
        val dir = File(root, repoId).apply { mkdirs() }
        val stamp = LocalDateTime.now().format(FILE_FORMAT)
        var file = File(dir, "$stamp.log")
        var n = 1
        while (file.exists()) file = File(dir, "$stamp-${n++}.log")
        val session = SessionLog(repoId, file)
        header.forEach { session.append('I', AndroidSyncLogger.TAG, it, null) }
        active = session
        return session
    }

    fun list(repoId: String): List<Entry> {
        val dir = File(root, repoId)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: return emptyList()
        return files.sortedByDescending { it.name }.map { f ->
            Entry(repoId, f, startedAtOf(f), resultOf(f), opOf(f))
        }
    }

    /**
     * 把全部日志（`logs/<repoId>/` 下的 `.log` 文件）与 [extra] 里的文件打包成 zip 写到 [target]，附一份 [info] 说明（设备、版本、库列表）。
     * 供"导出日志"分享给开发者；日志本身不含令牌、私钥、口令。
     */
    fun zipAll(target: File, info: String, extra: List<Pair<String, File>>) {
        target.parentFile?.mkdirs()
        java.util.zip.ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("info.txt"))
            zip.write(info.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            root.listFiles { f -> f.isDirectory }?.forEach { dir ->
                dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }?.forEach { f ->
                    zip.putNextEntry(java.util.zip.ZipEntry("logs/${dir.name}/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            extra.forEach { (name, f) ->
                if (f.isFile) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    fun file(repoId: String, name: String): File? {
        if (name.contains('/') || name.contains('\\')) return null
        return File(File(root, repoId), name).takeIf { it.isFile }
    }

    /** 保留最近 [keep] 个，其余删除。 */
    fun prune(repoId: String, keep: Int) {
        list(repoId).drop(keep.coerceAtLeast(1)).forEach { it.file.delete() }
    }

    fun deleteAll(repoId: String) {
        File(root, repoId).deleteRecursively()
    }

    private fun startedAtOf(f: File): Long = try {
        LocalDateTime.parse(f.name.substringBefore('.').take(15), FILE_FORMAT)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (e: Exception) {
        f.lastModified()
    }

    private fun opOf(f: File): String? = try {
        f.bufferedReader(Charsets.UTF_8).use { r ->
            generateSequence { r.readLine() }.take(3).firstNotNullOfOrNull { line -> OP.find(line)?.groupValues?.get(1) }
        }
    } catch (e: Exception) {
        null
    }

    /** 从文件尾部找 `RESULT xxx` 行。 */
    private fun resultOf(f: File): String? {
        val tail = try {
            java.io.RandomAccessFile(f, "r").use { raf ->
                val len = raf.length()
                val n = minOf(len, 4096L).toInt()
                raf.seek(len - n)
                val buf = ByteArray(n)
                raf.readFully(buf)
                String(buf, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            return null
        }
        val idx = tail.lastIndexOf("RESULT ")
        if (idx < 0) return null
        return tail.substring(idx + 7).trim().substringBefore(' ')
    }

    companion object {
        private val FILE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val OP = Regex("""op=([a-z]+)""")

        /** 当前活动会话；全局日志器与 SLF4J 绑定都往这里追加。 */
        @Volatile
        var active: SessionLog? = null

        /** SLF4J（JGit/JSch）是否把 DEBUG 也写进会话文件。由设置控制。 */
        @Volatile
        var debugEnabled: Boolean = false
    }
}
