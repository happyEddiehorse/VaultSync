package com.mountriv.vaultsync.gitcore.net

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * HTTP 代理设置（docs/SYNC_ENGINE.md §8）。JGit 的 HTTP 传输通过 `ProxySelector.getDefault()` 取代理，
 * 所以这里用一个固定的 [ProxySelector] 覆盖进程默认值；关闭时恢复系统默认。SSH 走 JSch `ProxyHTTP`（M4）。
 */
object ProxyConfig {

    data class Settings(val enabled: Boolean = false, val host: String = "", val port: Int = 0) {
        val isUsable: Boolean get() = enabled && host.isNotBlank() && port in 1..65535
    }

    @Volatile
    private var systemDefault: ProxySelector? = null

    @Volatile
    var current: Settings = Settings()
        private set

    /** 应用代理设置到进程默认 [ProxySelector]。幂等；每次同步开始前调用一次即可。 */
    @Synchronized
    fun apply(settings: Settings) {
        if (systemDefault == null) systemDefault = ProxySelector.getDefault()
        current = settings
        if (settings.isUsable) {
            ProxySelector.setDefault(FixedProxySelector(settings.host, settings.port))
        } else {
            ProxySelector.setDefault(systemDefault)
        }
    }

    private class FixedProxySelector(host: String, port: Int) : ProxySelector() {
        private val proxies = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port)))

        override fun select(uri: URI?): List<Proxy> {
            val scheme = uri?.scheme?.lowercase()
            return if (scheme == "http" || scheme == "https") proxies else listOf(Proxy.NO_PROXY)
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
    }
}
