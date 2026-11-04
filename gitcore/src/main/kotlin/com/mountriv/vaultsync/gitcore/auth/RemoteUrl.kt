package com.mountriv.vaultsync.gitcore.auth

/**
 * 远端 URL 的识别与规范化（向导用，见 docs/UI.md §3、D-018）。
 *
 * 支持三种写法：
 * - `https://host/owner/repo.git`（HTTPS，用令牌认证）；
 * - `ssh://git@host[:port]/owner/repo.git`（SSH，标准形式，存储时统一用这种）；
 * - `git@host:owner/repo.git`（scp 风格，等价于 ssh 端口 22；向导里加端口后转成 `ssh://` 形式）。
 * 明文 `http://` 不支持（应用 `usesCleartextTraffic=false`）。
 */
object RemoteUrl {

    enum class Kind { HTTPS, SSH, UNSUPPORTED }

    data class Parsed(
        val kind: Kind,
        val user: String? = null,
        val host: String = "",
        /** SSH 端口；HTTPS 与无法解析时为 null。 */
        val port: Int? = null,
        /** 不含前导 `/` 的路径，例如 `owner/repo.git`。 */
        val path: String = "",
    ) {
        /** 规范化后的 URL：HTTPS 原样；SSH 统一为 `ssh://user@host[:port]/path`。 */
        fun normalized(): String = when (kind) {
            Kind.HTTPS -> "https://$host/$path"
            Kind.SSH -> buildString {
                append("ssh://")
                if (!user.isNullOrEmpty()) append(user).append('@')
                append(host)
                if (port != null && port != DEFAULT_SSH_PORT) append(':').append(port)
                append('/').append(path)
            }
            Kind.UNSUPPORTED -> ""
        }

        /** 去掉 `.git` 后缀的最后一段，作为仓库显示名的默认值。 */
        val repoName: String
            get() = path.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
    }

    const val DEFAULT_SSH_PORT = 22
    const val GITHUB_HOST = "github.com"
    const val GITHUB_SSH_443_HOST = "ssh.github.com"
    const val GITHUB_SSH_443_PORT = 443

    private val HTTPS = Regex("""^https://(?:[^@/]+@)?([^/:]+)(?::\d+)?/(.+)$""")
    private val SSH_URL = Regex("""^ssh://(?:([^@/]+)@)?([^/:]+)(?::(\d+))?/(.+)$""")
    private val SCP = Regex("""^(?:([^@/:]+)@)?([^/:]+):(?!//)(.+)$""")

    /** 其它带 scheme 的写法（`file:/…`、`http://…`、`git://…`）以及 Windows 盘符 `C:/…`，不能被当成 scp 风格的 SSH。 */
    private val OTHER_SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.\-]*:/""")

    fun parse(input: String): Parsed {
        val s = input.trim()
        HTTPS.matchEntire(s)?.let { m ->
            return Parsed(Kind.HTTPS, host = m.groupValues[1], path = m.groupValues[2])
        }
        SSH_URL.matchEntire(s)?.let { m ->
            val port = m.groupValues[3].toIntOrNull() ?: DEFAULT_SSH_PORT
            return Parsed(Kind.SSH, user = m.groupValues[1].ifEmpty { null }, host = m.groupValues[2], port = port, path = m.groupValues[4])
        }
        if (OTHER_SCHEME.containsMatchIn(s)) return Parsed(Kind.UNSUPPORTED)
        SCP.matchEntire(s)?.let { m ->
            return Parsed(Kind.SSH, user = m.groupValues[1].ifEmpty { null }, host = m.groupValues[2], port = DEFAULT_SSH_PORT, path = m.groupValues[3])
        }
        return Parsed(Kind.UNSUPPORTED)
    }

    /** 把 SSH 远端换成指定端口（scp 风格会被转成 `ssh://` 形式）。非 SSH 原样返回。 */
    fun withPort(input: String, port: Int): String {
        val p = parse(input)
        if (p.kind != Kind.SSH) return input
        return p.copy(port = port).normalized()
    }

    /** GitHub 的 443 端口备用地址：`ssh://git@ssh.github.com:443/owner/repo.git`（D-018）。非 GitHub 的 SSH 地址原样返回。 */
    fun toGitHubPort443(input: String): String {
        val p = parse(input)
        if (p.kind != Kind.SSH) return input
        if (p.host != GITHUB_HOST && p.host != GITHUB_SSH_443_HOST) return input
        return p.copy(host = GITHUB_SSH_443_HOST, port = GITHUB_SSH_443_PORT).normalized()
    }

    /** [toGitHubPort443] 的反向：`ssh.github.com` 换回 `github.com` 的 22 端口（`ssh://git@github.com/owner/repo.git`）。其它地址原样返回。 */
    fun toGitHubPort22(input: String): String {
        val p = parse(input)
        if (p.kind != Kind.SSH || p.host != GITHUB_SSH_443_HOST) return input
        return p.copy(host = GITHUB_HOST, port = DEFAULT_SSH_PORT).normalized()
    }

    /** 去掉 userinfo（令牌可能被用户塞进 URL），用于日志与展示。 */
    fun redact(url: String): String = url.replace(Regex("//[^@/]+@"), "//***@")
}
