package com.mountriv.vaultsync.gitcore.model

import org.eclipse.jgit.errors.NoRemoteRepositoryException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 失败原因的粗分类，界面据此给出"可能原因 + 建议操作"（docs/UI.md §10）；原始英文信息仍保留在日志与"技术信息"里。
 * 已知原因由抛出处在 [SyncException.kind] 里给定，其余由 [FailureClassifier] 按异常类型与 JGit / JSch 的固定字样推断。
 */
enum class FailureKind {
    /** HTTPS 认证失败：令牌错误、过期，或令牌无权访问该仓库（401）。 */
    AUTH_TOKEN,

    /** SSH 公钥认证失败：公钥没有添加到远端，或选错了密钥。 */
    AUTH_SSH_KEY,

    /** 认证通过但没有权限（403、只读的 deploy key、令牌缺少写权限）。 */
    PERMISSION_DENIED,

    /** 远端仓库不存在，或当前凭据看不到它（GitHub 对无权访问的私有仓库也报"不存在"）。 */
    REPO_NOT_FOUND,

    /** SSH 主机密钥与已记录的不一致。 */
    HOST_KEY_CHANGED,

    /** 域名解析失败、网络不可达、连接被拒绝。 */
    NETWORK,

    /** 连接或读取超时。 */
    TIMEOUT,

    /** 经应用内 HTTP 代理连接失败。 */
    PROXY,

    /** TLS 握手或证书校验失败。 */
    TLS,

    /** 远端拒绝推送（分支保护、服务端钩子等）。 */
    PUSH_REJECTED,

    /** 推送重试多次仍被拒：推送期间远端一直有新提交。 */
    PUSH_RACE,

    /** 合并会覆盖本机未被 Git 跟踪（或被忽略）的同名文件。 */
    LOCAL_FILES_IN_WAY,

    /** 库文件夹不存在。 */
    VAULT_MISSING,

    /** 文件夹里没有 Git 仓库（`.git`）。 */
    NOT_A_REPOSITORY,

    /** 文件夹里已经有 Git 仓库（初始化场景）。 */
    ALREADY_A_REPOSITORY,

    /** 需要的令牌或 SSH 密钥没有保存，或没有选择。 */
    CREDENTIAL_MISSING,

    /** 令牌或密钥无法解密（系统密钥库发生变化）。 */
    CREDENTIAL_UNREADABLE,

    /** 仓库处于本应用不处理的状态：rebase / cherry-pick 进行中、HEAD 分离、检出的分支与设置不一致。 */
    REPOSITORY_STATE,

    /** 没有设置提交者姓名与邮箱。 */
    IDENTITY_MISSING,

    /** 克隆的目标文件夹不是空的。 */
    DIRECTORY_NOT_EMPTY,

    /** 初始化并推送时，远端已经有内容。 */
    REMOTE_NOT_EMPTY,

    /** 远端没有指定的分支。 */
    BRANCH_NOT_FOUND,

    /** 仓库没有配置远端，向导里也没有填写。 */
    REMOTE_MISSING,

    /** 库里没有任何可提交的文件（初始化场景）。 */
    VAULT_EMPTY,

    /** 读写本机文件失败（存储权限、空间不足）。 */
    STORAGE,

    UNKNOWN,
}

/** 从异常链与消息文字推断 [FailureKind]。只做字符串与类型匹配，不访问网络或文件。 */
object FailureClassifier {

    fun classify(message: String?, cause: Throwable?): FailureKind {
        val chain = generateSequence(cause) { t -> t.cause?.takeIf { it !== t } }.take(12).toList()
        chain.firstNotNullOfOrNull { (it as? SyncException)?.kind }?.let { return it }
        val text = (listOfNotNull(message) + chain.map { it.toString() }).joinToString("\n")
        val lower = text.lowercase()

        if (chain.any { it is NoRemoteRepositoryException } || "repository not found" in lower) return FailureKind.REPO_NOT_FOUND
        if ("hostkey has been changed" in lower) return FailureKind.HOST_KEY_CHANGED
        if ("auth fail" in lower || "auth cancel" in lower) return FailureKind.AUTH_SSH_KEY
        if ("not authorized" in lower || "authentication is required" in lower || "authentication not supported" in lower) {
            return FailureKind.AUTH_TOKEN
        }
        if ("not permitted" in lower || "read only" in lower || "read-only" in lower || ("permission to" in lower && "denied" in lower)) {
            return FailureKind.PERMISSION_DENIED
        }
        if ("proxyhttp" in lower || "unable to tunnel through proxy" in lower) return FailureKind.PROXY
        if (chain.any { it is SSLException } || "certificate" in lower || "sslhandshake" in lower) return FailureKind.TLS
        if (chain.any { it is UnknownHostException } || "unknownhostexception" in lower || "unknown host" in lower ||
            "unable to resolve host" in lower
        ) {
            return FailureKind.NETWORK
        }
        if (chain.any { it is SocketTimeoutException } || "timed out" in lower || "timeout" in lower || "time out" in lower) {
            return FailureKind.TIMEOUT
        }
        if (chain.any { it is ConnectException || it is NoRouteToHostException } || "connection refused" in lower ||
            "network is unreachable" in lower || "failed to connect" in lower || "connection reset" in lower
        ) {
            return FailureKind.NETWORK
        }
        if ("enospc" in lower || "no space left" in lower || "eacces" in lower ||
            ("filenotfoundexception" in lower && "permission denied" in lower)
        ) {
            return FailureKind.STORAGE
        }
        return FailureKind.UNKNOWN
    }
}
