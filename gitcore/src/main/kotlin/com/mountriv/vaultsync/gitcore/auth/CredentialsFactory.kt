package com.mountriv.vaultsync.gitcore.auth

import com.mountriv.vaultsync.gitcore.log.SyncLogger
import com.mountriv.vaultsync.gitcore.model.AuthSpec
import com.mountriv.vaultsync.gitcore.model.FailureKind
import com.mountriv.vaultsync.gitcore.model.RepoSpec
import com.mountriv.vaultsync.gitcore.model.SecretProvider
import com.mountriv.vaultsync.gitcore.model.SyncException
import com.mountriv.vaultsync.gitcore.model.SyncStage
import org.eclipse.jgit.transport.URIish
import java.net.URISyntaxException
import java.nio.CharBuffer

/**
 * 把 [RepoSpec.auth] 变成本次传输用的 [TransportAuth]（docs/SYNC_ENGINE.md §8）。
 * 秘密只在这里短暂经手：HTTPS 令牌交给 JGit 的凭据提供者，SSH 私钥交给 [VaultSshSessionFactory]。
 */
object CredentialsFactory {

    /** JSch 里给应用密钥起的名字（只出现在 JSch 的日志里）。 */
    const val SSH_IDENTITY_NAME = "vaultsync"

    fun forSpec(spec: RepoSpec, secrets: SecretProvider, logger: SyncLogger): TransportAuth = when (val auth = spec.auth) {
        is AuthSpec.HttpsToken -> {
            val token = secrets.secret(auth.secretId)
                ?: throw SyncException(
                    SyncStage.PRECHECK,
                    "missing HTTPS token for '${spec.name}' (secret '${auth.secretId}')",
                    kind = FailureKind.CREDENTIAL_MISSING,
                )
            TransportAuth.https(auth.username, token)
        }
        is AuthSpec.SshKey -> {
            val keyChars = secrets.secret(auth.keyId)
                ?: throw SyncException(
                    SyncStage.PRECHECK,
                    "SSH key '${auth.keyId}' not found; generate or import one in Settings",
                    kind = FailureKind.CREDENTIAL_MISSING,
                )
            val key = utf8(keyChars)
            val passphrase = auth.passphraseId?.let { secrets.secret(it) }?.let { utf8(it) }
            val fingerprint = SshKeys.publicKeyOf(key)?.fingerprint
            val factory = VaultSshSessionFactory(SSH_IDENTITY_NAME, key, passphrase, VaultKnownHosts.default(logger), logger)
            key.fill(0)
            passphrase?.fill(0)
            TransportAuth.ssh(factory, fingerprint)
        }
        AuthSpec.None -> {
            if (usesSsh(spec.remoteUrl)) {
                throw SyncException(
                    SyncStage.PRECHECK,
                    "remote '${RemoteUrl.redact(spec.remoteUrl)}' uses SSH but no SSH key is selected for '${spec.name}'",
                    kind = FailureKind.CREDENTIAL_MISSING,
                )
            }
            TransportAuth.NONE
        }
    }

    /** 与 JGit 选择 `TransportGitSsh` 的条件一致：`ssh://` 系列 scheme，或没有 scheme 的 scp 写法（`git@host:path`）。 */
    fun usesSsh(url: String): Boolean = try {
        val uri = URIish(url)
        uri.scheme in SSH_SCHEMES || (uri.scheme == null && uri.isRemote)
    } catch (e: URISyntaxException) {
        false
    }

    private val SSH_SCHEMES = setOf("ssh", "ssh+git", "git+ssh")

    private fun utf8(chars: CharArray): ByteArray {
        val bb = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        val bytes = ByteArray(bb.remaining())
        bb.get(bytes)
        if (bb.hasArray()) bb.array().fill(0)
        chars.fill('\u0000')
        return bytes
    }
}
