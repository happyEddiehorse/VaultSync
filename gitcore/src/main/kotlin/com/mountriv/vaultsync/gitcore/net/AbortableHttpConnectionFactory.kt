package com.mountriv.vaultsync.gitcore.net

import org.eclipse.jgit.transport.HttpTransport
import org.eclipse.jgit.transport.http.HttpConnection
import org.eclipse.jgit.transport.http.HttpConnectionFactory2
import org.eclipse.jgit.transport.http.JDKHttpConnection
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * 包一层 JGit 的 HTTP 连接工厂：在 [Aborter.bind] 里建的连接，取消时直接关掉它的 socket（D-035、L-040）。
 * 其余行为（含 `HttpConnectionFactory2` 的会话、SSL 校验）全部交给原来的工厂。由 GitEnvironment 装一次。
 *
 * 为什么关 socket 而不是 `HttpURLConnection.disconnect()`：JDK 的 disconnect 先去关输入流，要等正在读的线程让出锁，根本断不开。
 * HTTPS 连接换上一个记录 socket 的 [SSLSocketFactory]（包着连接原来的工厂，证书校验不变）；纯 HTTP 没有这个口子，退回 disconnect。
 * JGit 的 [HttpConnection] 不暴露底层连接；[JDKHttpConnection] 把它放在包内可见的字段 `wrappedUrlConnection` 里，这里用反射取
 * （JGit 锁定 5.13，D-003；release 的混淆规则保留 JGit 的类名与字段名）。取不到就不登记，取消退回到 JGit 自己的检查。
 */
internal class AbortableHttpConnectionFactory(private val delegate: HttpConnectionFactory2) : HttpConnectionFactory2 by delegate {

    override fun create(url: URL): HttpConnection = track(delegate.create(url))

    override fun create(url: URL, proxy: Proxy): HttpConnection = track(delegate.create(url, proxy))

    private fun track(conn: HttpConnection): HttpConnection {
        val aborter = Aborter.current() ?: return conn
        val raw = urlConnectionOf(conn) ?: return conn
        if (raw is HttpsURLConnection) {
            raw.sslSocketFactory = TrackingSocketFactory(raw.sslSocketFactory, aborter)
        } else {
            aborter.register { raw.disconnect() }
        }
        return conn
    }

    /** 新建的 socket 都登记到 [aborter]，取消时 `close()`；正在读它的线程马上得到 SocketException。 */
    private class TrackingSocketFactory(private val d: SSLSocketFactory, private val aborter: Aborter) : SSLSocketFactory() {
        private fun <T : Socket> track(s: T): T = s.also { aborter.register { it.close() } }

        override fun getDefaultCipherSuites(): Array<String> = d.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = d.supportedCipherSuites
        override fun createSocket(): Socket = track(d.createSocket())
        override fun createSocket(s: Socket, host: String?, port: Int, autoClose: Boolean): Socket = track(d.createSocket(s, host, port, autoClose))
        override fun createSocket(host: String?, port: Int): Socket = track(d.createSocket(host, port))
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            track(d.createSocket(host, port, localHost, localPort))
        override fun createSocket(host: InetAddress?, port: Int): Socket = track(d.createSocket(host, port))
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            track(d.createSocket(address, port, localAddress, localPort))
    }

    companion object {
        private val field = runCatching {
            JDKHttpConnection::class.java.getDeclaredField("wrappedUrlConnection").apply { isAccessible = true }
        }.getOrNull()

        internal fun urlConnectionOf(conn: HttpConnection): HttpURLConnection? =
            if (conn is JDKHttpConnection) runCatching { field?.get(conn) as? HttpURLConnection }.getOrNull() else null

        /** 把全局工厂换成包过的（幂等）。原工厂不是 `HttpConnectionFactory2` 时不动。 */
        fun install() {
            val current = HttpTransport.getConnectionFactory()
            if (current is AbortableHttpConnectionFactory || current !is HttpConnectionFactory2) return
            HttpTransport.setConnectionFactory(AbortableHttpConnectionFactory(current))
        }
    }
}
