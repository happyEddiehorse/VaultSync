package com.mountriv.vaultsync.gitcore.auth

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import com.mountriv.vaultsync.gitcore.GitEnvironment
import com.mountriv.vaultsync.gitcore.log.SyncLogger
import java.io.File

/** 展示用：一台主机的一把密钥（顶层类型，app 层不必接触 JSch 的接口）。 */
data class KnownHostEntry(val host: String, val type: String, val fingerprint: String)

/** 给 app 层的入口：默认 known_hosts 的记录列表。JSch 的类型不出 gitcore（app 模块的类路径上没有 jsch）。 */
fun knownHostEntries(logger: SyncLogger): List<KnownHostEntry> = VaultKnownHosts.default(logger).hosts()

/** 给 app 层的入口：忘记 [host]，返回删掉的记录数。 */
fun forgetKnownHost(logger: SyncLogger, host: String): Int = VaultKnownHosts.default(logger).forget(host)

/** 给 app 层的入口：known_hosts 全文（配置备份用）。 */
fun knownHostsText(logger: SyncLogger): String = VaultKnownHosts.default(logger).exportText()

/** 给 app 层的入口：把备份里的 known_hosts 并入本机（见 [VaultKnownHosts.mergeFrom]），返回新增的记录数。 */
fun mergeKnownHosts(logger: SyncLogger, text: String): Int = VaultKnownHosts.default(logger).mergeFrom(text)

/**
 * 应用自己的 known_hosts（docs/SYNC_ENGINE.md §8、D-022）。文件是 OpenSSH 格式（`主机[,主机…] 类型 base64 [注释]`），
 * 端口非 22 时主机写作 `[host]:port`（JSch `Session.checkHost` 传进来的就是这个字符串，这里原样存取，不自己拼）。
 *
 * 策略是 **TOFU（首次信任）**：
 * - 主机未知 → 在 [check] 里直接记录并返回 `OK`（同时把指纹写进日志）；
 * - 主机已知且密钥一致 → `OK`；
 * - 主机已知但密钥不同（或只知道其它类型的密钥）→ `CHANGED`，配合会话上的 `StrictHostKeyChecking=yes`，
 *   JSch 会抛 `HostKey has been changed`，用户确认后可在设置里"忘记"该主机再连。
 *
 * 文件不存在时用 GitHub 公布的三把主机密钥预置（`api.github.com/meta`），这样对 GitHub 的第一次连接就是有校验的，
 * 而不是盲信；GitHub 若换了密钥，用户"忘记"后走 TOFU。每次操作都重读并整体重写文件（文件很小），跨线程用全局锁。
 */
class VaultKnownHosts(val file: File, private val logger: SyncLogger) : HostKeyRepository {

    /** 一条记录；[hosts] 可含多个主机名（OpenSSH 允许逗号分隔）。 */
    class Entry(val hosts: List<String>, val type: String, val key: ByteArray, val comment: String? = null) {
        val fingerprint: String get() = SshSupport.fingerprint(key)

        fun matches(host: String): Boolean = hosts.any { it.equals(host, ignoreCase = true) }

        fun line(): String = buildString {
            append(hosts.joinToString(",")).append(' ').append(type).append(' ').append(SshSupport.base64(key))
            if (!comment.isNullOrBlank()) append(' ').append(comment.trim())
        }
    }

    /** 展示用：一台主机的一把密钥。 */
    private class Content(val entries: MutableList<Entry>, val otherLines: MutableList<String>)

    // ---- JSch HostKeyRepository ----

    override fun check(host: String?, key: ByteArray?): Int = synchronized(LOCK) {
        if (host == null || key == null) return HostKeyRepository.NOT_INCLUDED
        val type = SshSupport.keyTypeOf(key)
        if (type == null) {
            logger.error("host key from '$host' has an unrecognised format; refusing")
            return HostKeyRepository.NOT_INCLUDED
        }
        val content = load()
        val known = content.entries.filter { it.matches(host) }
        val sameType = known.filter { it.type == type }
        if (sameType.any { it.key.contentEquals(key) }) return HostKeyRepository.OK
        val presented = SshSupport.fingerprint(key)
        if (sameType.isNotEmpty()) {
            logger.error(
                "HOST KEY CHANGED for '$host' ($type): known ${sameType.joinToString { it.fingerprint }}, presented $presented. " +
                    "Someone could be intercepting the connection, or the server really changed its key. " +
                    "If you are sure, forget the host in Settings and connect again.",
            )
            return HostKeyRepository.CHANGED
        }
        if (known.isNotEmpty()) {
            logger.error(
                "host '$host' is known with ${known.joinToString { it.type }} but presented a $type key ($presented); refusing. " +
                    "If the server really changed its keys, forget the host in Settings and connect again.",
            )
            return HostKeyRepository.CHANGED
        }
        content.entries += Entry(listOf(host), type, key.copyOf())
        save(content)
        logger.warn("first connection to '$host': recorded its $type host key $presented in ${file.name}")
        return HostKeyRepository.OK
    }

    override fun add(hostkey: HostKey, ui: UserInfo?) {
        val key = SshSupport.decodeBase64(hostkey.key) ?: return
        val type = SshSupport.keyTypeOf(key) ?: return
        synchronized(LOCK) {
            val content = load()
            if (content.entries.any { it.matches(hostkey.host) && it.type == type && it.key.contentEquals(key) }) return
            content.entries += Entry(listOf(hostkey.host), type, key)
            save(content)
        }
    }

    override fun remove(host: String?, type: String?) = remove(host, type, null)

    override fun remove(host: String?, type: String?, key: ByteArray?) {
        if (host == null) return
        synchronized(LOCK) {
            val content = load()
            val changed = removeHost(content, host) { e ->
                (type == null || e.type == type) && (key == null || e.key.contentEquals(key))
            }
            if (changed) save(content)
        }
    }

    override fun getKnownHostsRepositoryID(): String = file.absolutePath

    override fun getHostKey(): Array<HostKey> = synchronized(LOCK) {
        load().entries.flatMap { e -> e.hosts.mapNotNull { h -> toHostKey(h, e) } }.toTypedArray()
    }

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = synchronized(LOCK) {
        load().entries
            .filter { (host == null || it.matches(host)) && (type == null || it.type == type) }
            .mapNotNull { e -> toHostKey(host ?: e.hosts.first(), e) }
            .toTypedArray()
    }

    // ---- 应用自己的 API ----

    /** 全部记录，按主机展开。 */
    fun hosts(): List<KnownHostEntry> = synchronized(LOCK) {
        load().entries.flatMap { e -> e.hosts.map { KnownHostEntry(it, e.type, e.fingerprint) } }
    }

    /** 忘记 [host] 的全部密钥（其它主机不受影响）。返回删掉的记录数。 */
    fun forget(host: String): Int = synchronized(LOCK) {
        val content = load()
        var count = 0
        val changed = removeHost(content, host) { count++; true }
        if (changed) save(content)
        count
    }

    /** 文件全文（不存在时先按预置内容创建）。 */
    fun exportText(): String = synchronized(LOCK) {
        load()
        file.readText(Charsets.UTF_8)
    }

    /**
     * 并入另一份 known_hosts 的记录：本机没有的 主机+类型 才加入；本机已有同一主机同类型但密钥不同的，保留本机的
     * （不因为导入而静默改变信任）。返回新增的记录数。
     */
    fun mergeFrom(text: String): Int = synchronized(LOCK) {
        val content = load()
        var added = 0
        text.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("@") && !it.startsWith("|") }
            .mapNotNull { parseLine(it) }
            .forEach { incoming ->
                val newHosts = incoming.hosts.filter { h -> content.entries.none { it.matches(h) && it.type == incoming.type } }
                if (newHosts.isNotEmpty()) {
                    content.entries += Entry(newHosts, incoming.type, incoming.key, incoming.comment)
                    added += newHosts.size
                }
            }
        if (added > 0) {
            save(content)
            logger.info("known_hosts: merged $added host key(s) from backup")
        }
        added
    }

    /** 仅供测试：写入/替换一条记录。 */
    internal fun putForTest(host: String, type: String, key: ByteArray) = synchronized(LOCK) {
        val content = load()
        removeHost(content, host) { it.type == type }
        content.entries += Entry(listOf(host), type, key)
        save(content)
    }

    // ---- 内部 ----

    private fun removeHost(content: Content, host: String, predicate: (Entry) -> Boolean): Boolean {
        var changed = false
        val it = content.entries.listIterator()
        while (it.hasNext()) {
            val e = it.next()
            if (!e.matches(host) || !predicate(e)) continue
            changed = true
            val remaining = e.hosts.filterNot { h -> h.equals(host, ignoreCase = true) }
            if (remaining.isEmpty()) it.remove() else it.set(Entry(remaining, e.type, e.key, e.comment))
        }
        return changed
    }

    private fun toHostKey(host: String, e: Entry): HostKey? {
        val type = when (e.type) {
            "ssh-dss" -> HostKey.SSHDSS
            "ssh-rsa" -> HostKey.SSHRSA
            "ecdsa-sha2-nistp256" -> HostKey.ECDSA256
            "ecdsa-sha2-nistp384" -> HostKey.ECDSA384
            "ecdsa-sha2-nistp521" -> HostKey.ECDSA521
            "ssh-ed25519" -> HostKey.ED25519
            "ssh-ed448" -> HostKey.ED448
            else -> return null
        }
        return try {
            HostKey(host, type, e.key)
        } catch (ex: Exception) {
            null
        }
    }

    private fun load(): Content {
        if (!file.isFile) {
            val seeded = Content(ArrayList(), ArrayList())
            seeded.otherLines += "# VaultSync known_hosts. Hosts are recorded on first connection (TOFU)."
            seeded.otherLines += "# GitHub keys below were seeded from https://api.github.com/meta on $GITHUB_SEED_DATE."
            GITHUB_SEED_LINES.mapNotNullTo(seeded.entries) { parseLine(it) }
            save(seeded)
            return seeded
        }
        val content = Content(ArrayList(), ArrayList())
        file.readLines(Charsets.UTF_8).forEach { raw ->
            val line = raw.trim()
            val entry = if (line.isEmpty() || line.startsWith("#") || line.startsWith("@") || line.startsWith("|")) null else parseLine(line)
            if (entry != null) content.entries += entry else content.otherLines += raw
        }
        return content
    }

    private fun parseLine(line: String): Entry? {
        val parts = line.trim().split(Regex("\\s+"), limit = 4)
        if (parts.size < 3) return null
        val hosts = parts[0].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (hosts.isEmpty()) return null
        val key = SshSupport.decodeBase64(parts[2]) ?: return null
        val type = SshSupport.keyTypeOf(key) ?: return null
        if (type != parts[1]) return null
        return Entry(hosts, type, key, parts.getOrNull(3))
    }

    private fun save(content: Content) {
        file.parentFile?.mkdirs()
        val text = buildString {
            content.otherLines.forEach { append(it).append('\n') }
            content.entries.forEach { append(it.line()).append('\n') }
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "cannot write ${file.absolutePath}" }
        }
    }

    companion object {
        private val LOCK = Any()

        /** 默认位置：`GitEnvironment.homeDir/ssh/known_hosts`。 */
        fun default(logger: SyncLogger): VaultKnownHosts {
            val home = GitEnvironment.homeDir ?: error("GitEnvironment.install() has not been called")
            return VaultKnownHosts(File(File(home, "ssh"), "known_hosts"), logger)
        }

        const val GITHUB_SEED_DATE = "2026-09-21"

        /** GitHub 公布的主机密钥（同时覆盖 22 端口的 github.com 与 443 端口的 ssh.github.com）。 */
        val GITHUB_SEED_LINES: List<String> = listOf(
            "github.com,ssh.github.com,[ssh.github.com]:443 ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl",
            "github.com,ssh.github.com,[ssh.github.com]:443 ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBEmKSENjQEezOmxkZMy7opKgwFB9nkt5YRrYMjNuG5N87uRgg6CLrbo5wAdT/y6v0mKV0U2w0WZ2YB/++Tpockg=",
            "github.com,ssh.github.com,[ssh.github.com]:443 ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABgQCj7ndNxQowgcQnjshcLrqPEiiphnt+VTTvDP6mHBL9j1aNUkY4Ue1gvwnGLVlOhGeYrnZaMgRK6+PKCUXaDbC7qtbW8gIkhL7aGCsOr/C56SJMy/BCZfxd1nWzAOxSDPgVsmerOBYfNqltV9/hWCqBywINIR+5dIg6JTJ72pcEpEjcYgXkE2YEFXV1JHnsKgbLWNlhScqb2UmyRkQyytRLtL+38TGxkxCflmO+5Z8CSSNY7GidjMIZ7Q4zMjA2n1nGrlTDkzwDCsw+wqFPGQA179cnfGWOWRVruj16z6XyvxvjJwbz0wQZ75XK5tKSb7FNyeIEs4TT4jk+S4dhPeAUC5y+bDYirYgM4GC7uEnztnZyaVWQ7B381AK4Qdrwt51ZqExKbQpTUNn+EjqoTwvqNj4kqx5QUCI0ThS/YkOxJCXmPUWZbhjpCg56i+2aB6CmK2JGhn57K5mj0MNdBXA4/WnwH6XoPWJzK5Nyu2zB3nAZp+S5hpQs+p1vN1/wsjk=",
        )
    }
}
