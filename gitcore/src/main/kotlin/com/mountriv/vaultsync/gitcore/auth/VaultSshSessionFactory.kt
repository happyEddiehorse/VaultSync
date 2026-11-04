package com.mountriv.vaultsync.gitcore.auth

import com.jcraft.jsch.ConfigRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.ProxyHTTP
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import com.mountriv.vaultsync.gitcore.log.SyncLogger
import com.mountriv.vaultsync.gitcore.net.Aborter
import com.mountriv.vaultsync.gitcore.net.ProxyConfig
import org.eclipse.jgit.transport.JschConfigSessionFactory
import org.eclipse.jgit.transport.OpenSshConfig
import org.eclipse.jgit.util.FS

/**
 * 应用自己的 SSH 会话工厂（docs/SYNC_ENGINE.md §8）。每次同步新建一个实例并通过 `TransportConfigCallback`
 * 挂到当次传输上（[TransportAuth]），不改 JGit 的全局 `SshSessionFactory`，密钥换了也不会串。
 *
 * 与 JGit 默认实现的区别：
 * - 不读 `~/.ssh/id_*`、`~/.ssh/config`、`~/.ssh/known_hosts`；身份只有 [privateKey]，主机密钥只认 [knownHosts]；
 * - `StrictHostKeyChecking=yes`：TOFU 由 [VaultKnownHosts.check] 负责，密钥变化时 JSch 抛 `HostKey has been changed`；
 * - 只用 publickey 认证（JGit 不会给 SSH 会话设 `UserInfo`，keyboard-interactive / password 也没有意义）；
 * - 设置里开了代理时经 HTTP CONNECT（[ProxyHTTP]）连接；连代理本身最多等 [PROXY_CONNECT_TIMEOUT_MS]。
 */
class VaultSshSessionFactory(
    private val identityName: String,
    privateKey: ByteArray,
    passphrase: ByteArray?,
    private val knownHosts: VaultKnownHosts,
    private val logger: SyncLogger,
) : JschConfigSessionFactory() {

    // JSch 会把传给 addIdentity 的数组清零（KeyPair.load 的 finally），所以保留自己的副本，每次建 JSch 时再复制一份
    private val privateKey: ByteArray = privateKey.copyOf()
    private val passphrase: ByteArray? = passphrase?.copyOf()

    override fun createDefaultJSch(fs: FS): JSch {
        val jsch = JSch()
        jsch.setConfigRepository(ConfigRepository.nullConfig)
        jsch.hostKeyRepository = knownHosts
        jsch.addIdentity(identityName, privateKey.copyOf(), null, passphrase?.copyOf())
        return jsch
    }

    override fun configure(hc: OpenSshConfig.Host, session: Session) {
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "publickey")
        val proxy = ProxyConfig.current
        if (proxy.isUsable) {
            session.setProxy(CappedTimeoutProxy(ProxyHTTP(proxy.host, proxy.port), PROXY_CONNECT_TIMEOUT_MS))
            logger.info("ssh ${session.host}:${session.port} via HTTP proxy ${proxy.host}:${proxy.port}")
        } else {
            logger.info("ssh ${session.host}:${session.port} as ${session.userName} (publickey)")
        }
    }

    /** 新建的会话登记到当前任务，取消时直接断开（D-035）。 */
    override fun createSession(hc: OpenSshConfig.Host, user: String, host: String, port: Int, fs: FS): Session =
        super.createSession(hc, user, host, port, fs).also { s -> Aborter.current()?.register { s.disconnect() } }

    /** 用完清零私钥副本。工厂随 [TransportAuth] 一起丢弃时调用。 */
    fun dispose() {
        privateKey.fill(0)
        passphrase?.fill(0)
    }

    /**
     * 连接代理（TCP 连接 + CONNECT 应答）的超时取会话超时与 [capMs] 的较小值：代理地址不通（例如电脑上的代理没开放局域网）时
     * 不必等满整个传输超时。SSH 握手阶段的读超时之后仍由会话恢复为原值。
     */
    private class CappedTimeoutProxy(private val delegate: Proxy, private val capMs: Int) : Proxy by delegate {
        override fun connect(socketFactory: SocketFactory?, host: String?, port: Int, timeout: Int) {
            delegate.connect(socketFactory, host, port, if (timeout <= 0) capMs else minOf(timeout, capMs))
        }
    }

    companion object {
        const val PROXY_CONNECT_TIMEOUT_MS = 15_000
    }
}
