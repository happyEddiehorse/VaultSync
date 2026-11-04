package com.mountriv.vaultsync.gitcore

import com.mountriv.vaultsync.gitcore.auth.TransportAuth
import com.mountriv.vaultsync.gitcore.net.Aborter
import com.mountriv.vaultsync.gitcore.repo.VaultRepository
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * D-035：HTTPS 传输中取消时关掉连接的 socket。服务器发了响应头和几个字节后就不动了（模拟大文件下载中途），
 * JGit 卡在读上；abort 后应在一两秒内抛异常返回。证书用 keytool 临时生成，测试期间设为默认信任。
 */
class HttpAbortTest {
    private lateinit var server: HttpsServer
    private val release = CountDownLatch(1)
    private lateinit var savedFactory: SSLSocketFactory
    private lateinit var savedVerifier: HostnameVerifier

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        TestEnv.ensure()
        val ks = File(dir, "test.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val p = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "t", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=127.0.0.1",
            "-ext", "SAN=ip:127.0.0.1", "-validity", "2", "-storetype", "PKCS12", "-keystore", ks.path, "-storepass", PASS, "-keypass", PASS,
        ).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "keytool failed: $out" }
        val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, PASS.toCharArray()) } }
        val ctx = SSLContext.getInstance("TLS").apply {
            init(
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, PASS.toCharArray()) }.keyManagers,
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }.trustManagers,
                null,
            )
        }
        savedFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
        savedVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        HttpsURLConnection.setDefaultSSLSocketFactory(ctx.socketFactory)

        server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.httpsConfigurator = HttpsConfigurator(ctx)
        server.createContext("/") { ex ->
            ex.responseHeaders.add("Content-Type", "application/x-git-upload-pack-advertisement")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.write("001e# service=git-upload-pack\n".toByteArray())
            ex.responseBody.flush()
            release.await(30, TimeUnit.SECONDS)
            runCatching { ex.close() }
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        release.countDown()
        server.stop(0)
        HttpsURLConnection.setDefaultSSLSocketFactory(savedFactory)
        HttpsURLConnection.setDefaultHostnameVerifier(savedVerifier)
    }

    @Test
    fun `abort closes a stalled https transfer`() {
        val url = "https://127.0.0.1:${server.address.port}/vault.git"
        val aborter = Aborter()
        val error = AtomicReference<Throwable>()
        val worker = Thread {
            try {
                aborter.bind { VaultRepository.lsRemoteHeads(url, TransportAuth.NONE, timeoutSec = 30) }
            } catch (t: Throwable) {
                error.set(t)
            }
        }
        worker.start()
        Thread.sleep(1_500)
        assertTrue(worker.isAlive, "ls-remote should be stuck reading; error=${error.get()}")
        val started = System.nanoTime()
        aborter.abort()
        worker.join(5_000)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertFalse(worker.isAlive, "ls-remote still running after abort")
        assertNotNull(error.get(), "ls-remote should fail after abort")
        assertTrue(ms < 2_000, "took $ms ms to stop after abort")
    }

    private companion object {
        const val PASS = "changeit"
    }
}
