package com.mountriv.vaultsync.gitcore

import com.jcraft.jsch.JSchException
import com.mountriv.vaultsync.gitcore.model.FailureClassifier
import com.mountriv.vaultsync.gitcore.model.FailureKind
import com.mountriv.vaultsync.gitcore.model.SyncException
import com.mountriv.vaultsync.gitcore.model.SyncOutcome
import com.mountriv.vaultsync.gitcore.model.SyncStage
import org.eclipse.jgit.api.errors.TransportException as ApiTransportException
import org.eclipse.jgit.errors.NoRemoteRepositoryException
import org.eclipse.jgit.errors.TransportException
import org.eclipse.jgit.transport.URIish
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.assertEquals

/** 失败原因分类：界面靠它给出中文的"可能原因 + 建议操作"。消息样本取自 JGit 5.13 / JSch 2.28 的原文。 */
class FailureClassifierTest {

    private val https = URIish("https://github.com/owner/vault.git")
    private val ssh = URIish("ssh://git@ssh.github.com:443/owner/vault.git")

    /** JGit 的 API 层异常包着传输层异常（与 FetchCommand / PushCommand 抛出的形状一致）。 */
    private fun api(inner: Throwable) = ApiTransportException(inner.message, inner)

    private fun kind(t: Throwable) = FailureClassifier.classify(t.message, t)

    @Test
    fun `https authentication and permission problems`() {
        assertEquals(FailureKind.AUTH_TOKEN, kind(api(TransportException(https, "not authorized"))))
        assertEquals(
            FailureKind.AUTH_TOKEN,
            kind(api(TransportException(https, "Authentication is required but no CredentialsProvider has been registered"))),
        )
        assertEquals(
            FailureKind.PERMISSION_DENIED,
            kind(api(TransportException(https, "git-receive-pack not permitted on 'https://github.com/owner/vault.git/'"))),
        )
        assertEquals(
            FailureKind.REPO_NOT_FOUND,
            kind(api(NoRemoteRepositoryException(https, "https://github.com/owner/vault.git/info/refs?service=git-upload-pack not found: Not Found"))),
        )
    }

    @Test
    fun `ssh authentication, host key and missing repository`() {
        assertEquals(
            FailureKind.AUTH_SSH_KEY,
            kind(api(TransportException(ssh, "Auth fail for methods 'publickey'", JSchException("Auth fail for methods 'publickey'")))),
        )
        assertEquals(
            FailureKind.HOST_KEY_CHANGED,
            kind(api(TransportException(ssh, "HostKey has been changed: [ssh.github.com]:443"))),
        )
        assertEquals(FailureKind.REPO_NOT_FOUND, kind(api(NoRemoteRepositoryException(ssh, "ERROR: Repository not found."))))
        assertEquals(
            FailureKind.PERMISSION_DENIED,
            kind(api(TransportException(ssh, "ERROR: The key you are authenticating with has been marked as read only."))),
        )
    }

    @Test
    fun `network, timeout, proxy and tls`() {
        assertEquals(FailureKind.NETWORK, kind(api(TransportException(https, "github.com", UnknownHostException("github.com")))))
        assertEquals(FailureKind.NETWORK, kind(api(TransportException(https, "Connection refused", ConnectException("Connection refused")))))
        assertEquals(FailureKind.TIMEOUT, kind(api(TransportException(https, "connect timed out", SocketTimeoutException("connect timed out")))))
        assertEquals(
            FailureKind.PROXY,
            kind(api(TransportException(ssh, "ProxyHTTP: java.net.SocketTimeoutException: connect timed out", JSchException("ProxyHTTP: timeout")))),
        )
        assertEquals(FailureKind.TLS, kind(api(TransportException(https, "handshake", SSLHandshakeException("PKIX path building failed")))))
    }

    @Test
    fun `explicit kinds win and survive wrapping`() {
        val e = SyncException(SyncStage.PRECHECK, "missing HTTPS token", kind = FailureKind.CREDENTIAL_MISSING)
        assertEquals(FailureKind.CREDENTIAL_MISSING, FailureClassifier.classify("anything", RuntimeException("wrapper", e)))
        assertEquals(FailureKind.UNKNOWN, FailureClassifier.classify("something odd happened", IllegalStateException("odd")))
        // SyncOutcome.Failed 默认由分类器补上 kind
        val failed = SyncOutcome.Failed(SyncStage.FETCH, "not authorized", TransportException(https, "not authorized"))
        assertEquals(FailureKind.AUTH_TOKEN, failed.kind)
    }
}
