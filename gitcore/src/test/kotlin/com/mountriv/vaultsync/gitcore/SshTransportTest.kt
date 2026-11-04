package com.mountriv.vaultsync.gitcore

import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import com.mountriv.vaultsync.gitcore.auth.CredentialsFactory
import com.mountriv.vaultsync.gitcore.auth.SshKeys
import com.mountriv.vaultsync.gitcore.auth.SshSupport
import com.mountriv.vaultsync.gitcore.auth.VaultKnownHosts
import com.mountriv.vaultsync.gitcore.log.InMemorySyncLogger
import com.mountriv.vaultsync.gitcore.model.AuthSpec
import com.mountriv.vaultsync.gitcore.model.FailureKind
import com.mountriv.vaultsync.gitcore.model.ProgressSink
import com.mountriv.vaultsync.gitcore.model.RepoSpec
import com.mountriv.vaultsync.gitcore.model.SecretProvider
import com.mountriv.vaultsync.gitcore.model.SyncOutcome
import com.mountriv.vaultsync.gitcore.model.SyncStage
import com.mountriv.vaultsync.gitcore.net.Aborter
import com.mountriv.vaultsync.gitcore.repo.VaultRepository
import com.mountriv.vaultsync.gitcore.sync.RepoOnboarding
import org.apache.sshd.common.util.buffer.ByteArrayBuffer
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.junit.ssh.SshTestGitServer
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 真实 git-over-ssh：JGit 自带的测试服务器（MINA sshd）+ 我们的 JSch 会话工厂、应用密钥与 known_hosts。
 * 覆盖 L-012 第 1 项在 JVM 上能覆盖的部分（BouncyCastle 的 ed25519 签名与 X25519 密钥交换）。
 */
class SshTransportTest {

    private lateinit var server: SshTestGitServer
    private lateinit var serverRepo: Repository
    private lateinit var bare: File
    private lateinit var url: String
    private lateinit var hostKey: java.security.KeyPair
    private lateinit var appKey: SshKeys.KeyMaterial
    private lateinit var knownHosts: VaultKnownHosts
    private var port = 0
    private val logger = InMemorySyncLogger()

    private val host: String get() = "[localhost]:$port"

    @BeforeEach
    fun setUp(@TempDir dir: File) {
        TestEnv.ensure()
        bare = File(dir, "remote.git")
        Git.init().setBare(true).setDirectory(bare).setInitialBranch("main").call().close()
        serverRepo = FileRepositoryBuilder().setGitDir(bare).setBare().build()
        hostKey = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        appKey = SshKeys.generateEd25519("phone@test")
        val authorized = File(dir, "authorized.pub").apply { writeText(appKey.publicKey.line + "\n") }
        server = SshTestGitServer("git", authorized.toPath(), serverRepo, hostKey)
        port = server.start()
        url = "ssh://git@localhost:$port/vault.git"
        knownHosts = VaultKnownHosts.default(logger)
        knownHosts.forget(host)
    }

    @AfterEach
    fun tearDown() {
        server.stop()
        serverRepo.close()
        TestEnv.closeDesktops()
    }

    private fun secrets(material: SshKeys.KeyMaterial) = SecretProvider { id ->
        if (id == KEY_ID) String(material.privateKey, Charsets.UTF_8).toCharArray() else null
    }

    private fun spec(vault: File): RepoSpec = TestEnv.spec(vault, url).copy(auth = AuthSpec.SshKey(KEY_ID))

    private fun sshFingerprint(key: PublicKey): String {
        val buf = ByteArrayBuffer()
        buf.putRawPublicKey(key)
        return SshSupport.fingerprint(buf.compactData)
    }

    @Test
    fun `clone and round trip over ssh with the app key`(@TempDir dir: File) {
        val desktop = TestEnv.cloneDesktop(bare.toURI().toString(), File(dir, "desktop"))
        desktop.write("Note.md", "from desktop\n")
        desktop.commitAll("desktop 1")
        desktop.push()

        val vault = File(dir, "phone")
        val result = RepoOnboarding.clone(spec(vault), secrets(appKey), logger)
        assertIs<RepoOnboarding.Result.Ready>(result, logger.toString())
        assertEquals("from desktop\n", vault.readVaultFile("Note.md"))
        assertTrue(logger.lines.any { it.contains("auth: ssh-key(${appKey.publicKey.fingerprint})") }, logger.toString())

        // 首次连接记录主机密钥（带端口的主机名），指纹与服务器密钥一致
        val recorded = knownHosts.hosts().filter { it.host == host }
        assertEquals(1, recorded.size, recorded.toString())
        assertEquals("ecdsa-sha2-nistp256", recorded.single().type)
        assertEquals(sshFingerprint(hostKey.public), recorded.single().fingerprint)
        assertEquals(1, logger.lines.count { it.contains("first connection to '$host'") }, logger.toString())

        // 手机改动 → 同步（fetch + push 都走 ssh）→ 桌面看到
        vault.writeVaultFile("Phone.md", "from phone\n")
        val syncLogger = InMemorySyncLogger()
        // TestEnv.sync 用 SecretProvider.NONE：没有私钥时必须在 PRECHECK 就给出明确错误
        val outcome = TestEnv.sync(TestEnv.engine(), spec(vault), syncLogger)
        assertIs<SyncOutcome.Failed>(outcome)
        assertEquals(SyncStage.PRECHECK, outcome.stage)
        assertTrue(outcome.message.contains("SSH key 'ssh-test-key' not found"), outcome.message)
        assertEquals(FailureKind.CREDENTIAL_MISSING, outcome.kind)

        val ok = TestEnv.engine().sync(spec(vault), secrets(appKey), TestEnv.phone, syncLogger)
        assertIs<SyncOutcome.Success>(ok, syncLogger.toString())
        assertEquals("OK", ok.summary.pushStatus)
        assertEquals(1, ok.summary.localFilesCommitted)
        assertTrue(syncLogger.lines.none { it.contains("first connection") }, syncLogger.toString())
        desktop.pull()
        assertEquals("from phone\n", desktop.read("Phone.md"))
    }

    /**
     * D-035：取消时断开 SSH 会话，正在下载的克隆马上失败（不用等大文件下完）。
     * 在"接收数据"的第一次进度回调里停住读线程，另一个线程 abort；读线程继续后只剩缓冲区里的一点数据，随即读到连接结束。
     */
    @Test
    fun `abort disconnects a running ssh clone`(@TempDir dir: File) {
        val desktop = TestEnv.cloneDesktop(bare.toURI().toString(), File(dir, "desktop"))
        desktop.writeBytes("big.bin", ByteArray(8 shl 20).also { java.util.Random(1).nextBytes(it) })
        desktop.commitAll("desktop big")
        desktop.push()

        val aborter = Aborter()
        val receiving = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val sink = object : ProgressSink {
            override fun onTask(title: String, totalWork: Int) {
                if (title.startsWith("Receiving") && receiving.count > 0) {
                    receiving.countDown()
                    resume.await(10, TimeUnit.SECONDS)
                }
            }
        }
        val vault = File(dir, "phone")
        val result = AtomicReference<RepoOnboarding.Result>()
        val worker = Thread { result.set(RepoOnboarding.clone(spec(vault), secrets(appKey), logger, sink, aborter = aborter)) }
        worker.start()
        assertTrue(receiving.await(30, TimeUnit.SECONDS), "clone never started receiving: $logger")
        aborter.abort()
        Thread.sleep(300)
        val started = System.nanoTime()
        resume.countDown()
        worker.join(10_000)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertFalse(worker.isAlive, "clone still running after abort")
        assertIs<RepoOnboarding.Result.Failed>(result.get(), logger.toString())
        assertTrue(ms < 3_000, "clone took $ms ms to stop after abort")
        assertFalse(File(vault, ".git").exists(), "failed clone is cleaned up")
    }

    @Test
    fun `changed host key is refused until the host is forgotten`(@TempDir dir: File) {
        val vault = File(dir, "phone")
        assertIs<RepoOnboarding.Result.Ready>(RepoOnboarding.clone(spec(vault), secrets(appKey), logger), logger.toString())

        val buf = ByteArrayBuffer().also { it.putRawPublicKey(hostKey.public) }
        val tampered = buf.compactData.also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        knownHosts.putForTest(host, "ecdsa-sha2-nistp256", tampered)

        val failLogger = InMemorySyncLogger()
        val failed = TestEnv.engine().sync(spec(vault), secrets(appKey), TestEnv.phone, failLogger)
        assertIs<SyncOutcome.Failed>(failed, failLogger.toString())
        assertEquals(SyncStage.FETCH, failed.stage)
        assertTrue(failed.message.contains("HostKey has been changed"), failed.message)
        assertEquals(FailureKind.HOST_KEY_CHANGED, failed.kind)
        assertTrue(failLogger.lines.any { it.contains("HOST KEY CHANGED for '$host'") }, failLogger.toString())

        assertEquals(1, knownHosts.forget(host))
        val okLogger = InMemorySyncLogger()
        val ok = TestEnv.engine().sync(spec(vault), secrets(appKey), TestEnv.phone, okLogger)
        assertIs<SyncOutcome.Success>(ok, okLogger.toString())
        assertEquals(sshFingerprint(hostKey.public), knownHosts.hosts().single { it.host == host }.fingerprint)
    }

    @Test
    fun `a key the server does not know is rejected`(@TempDir dir: File) {
        val stranger = SshKeys.generateEd25519("stranger")
        val vault = File(dir, "phone")
        val result = RepoOnboarding.clone(spec(vault), secrets(stranger), logger)
        assertIs<RepoOnboarding.Result.Failed>(result)
        assertEquals(SyncStage.FETCH, result.stage)
        assertTrue(result.message.contains("Auth fail"), result.message)
        assertEquals(FailureKind.AUTH_SSH_KEY, result.kind)
        // 主机密钥仍然被记录（认证失败发生在密钥交换之后）
        assertEquals(1, knownHosts.hosts().count { it.host == host })
    }

    @Test
    fun `imported keys authenticate`(@TempDir dir: File) {
        val jsch = JSch()
        val candidates = ArrayList<Pair<String, SshKeys.KeyMaterial>>()

        // 带口令的 OpenSSH v1 ed25519（ssh-keygen -t ed25519 的默认输出）
        KeyPair.genKeyPair(jsch, KeyPair.ED25519).let { kp ->
            val out = ByteArrayOutputStream()
            kp.writeOpenSSHv1PrivateKey(out, "pass".toByteArray())
            kp.dispose()
            candidates += "openssh-v1-encrypted-ed25519" to SshKeys.import(out.toString("UTF-8"), "pass".toCharArray())
        }
        // 传统 PEM 的 RSA 与 ECDSA（ssh-keygen -m PEM）
        KeyPair.genKeyPair(jsch, KeyPair.RSA, 2048).let { kp ->
            val out = ByteArrayOutputStream()
            kp.writePrivateKey(out)
            kp.dispose()
            candidates += "pem-rsa" to SshKeys.import(out.toString("UTF-8"), null)
        }
        KeyPair.genKeyPair(jsch, KeyPair.ECDSA, 256).let { kp ->
            val out = ByteArrayOutputStream()
            kp.writePrivateKey(out)
            kp.dispose()
            candidates += "pem-ecdsa" to SshKeys.import(out.toString("UTF-8"), null)
        }

        for ((name, material) in candidates) {
            val pub = File(dir, "$name.pub").apply { writeText(material.publicKey.line + "\n") }
            server.setTestUserPublicKey(pub.toPath())
            val log = InMemorySyncLogger()
            val auth = CredentialsFactory.forSpec(spec(File(dir, "unused")), secrets(material), log)
            try {
                val heads = VaultRepository.lsRemoteHeads(url, auth, 30)
                assertTrue(heads.isEmpty(), "$name: $heads")
            } finally {
                auth.dispose()
            }
        }
    }

    companion object {
        private const val KEY_ID = "ssh-test-key"
    }
}
