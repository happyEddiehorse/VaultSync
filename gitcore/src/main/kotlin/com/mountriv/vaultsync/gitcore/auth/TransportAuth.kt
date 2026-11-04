package com.mountriv.vaultsync.gitcore.auth

import org.eclipse.jgit.api.TransportCommand
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.SshSessionFactory
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

/**
 * 一次传输（fetch / push / clone / ls-remote）用的认证材料：HTTPS 是 [CredentialsProvider]，SSH 是挂到
 * `SshTransport` 上的 [SshSessionFactory]。用 [applyTo] 装到 JGit 命令上。
 */
class TransportAuth private constructor(
    val credentials: CredentialsProvider?,
    val sshSessionFactory: SshSessionFactory?,
    /** 写日志用的描述，不含秘密。 */
    val description: String,
) {
    fun applyTo(cmd: TransportCommand<*, *>) {
        credentials?.let { cmd.setCredentialsProvider(it) }
        sshSessionFactory?.let { factory ->
            cmd.setTransportConfigCallback { transport ->
                if (transport is SshTransport) transport.sshSessionFactory = factory
            }
        }
    }

    /** 释放持有的秘密副本（SSH 私钥）。 */
    fun dispose() {
        (sshSessionFactory as? VaultSshSessionFactory)?.dispose()
    }

    override fun toString(): String = description

    companion object {
        val NONE = TransportAuth(null, null, "none")

        fun https(username: String, token: CharArray): TransportAuth =
            TransportAuth(UsernamePasswordCredentialsProvider(username, token), null, "https-token($username)")

        fun ssh(factory: SshSessionFactory, fingerprint: String?): TransportAuth =
            TransportAuth(null, factory, "ssh-key(${fingerprint ?: "unknown"})")
    }
}
