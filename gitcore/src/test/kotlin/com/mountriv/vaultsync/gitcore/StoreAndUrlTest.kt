package com.mountriv.vaultsync.gitcore

import com.mountriv.vaultsync.gitcore.auth.CredentialsFactory
import com.mountriv.vaultsync.gitcore.auth.RemoteUrl
import com.mountriv.vaultsync.gitcore.model.AuthSpec
import com.mountriv.vaultsync.gitcore.model.RepoSpec
import com.mountriv.vaultsync.gitcore.store.JsonFileStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 配置持久化（D-019）与远端 URL 解析（D-018）。 */
class StoreAndUrlTest {

    private fun store(dir: File, version: Int = 1) = JsonFileStore(
        File(dir, "config/repos.json"),
        ListSerializer(RepoSpec.serializer()),
        version,
    ) { from, data ->
        // 模拟 v1 → v2：给每个仓库加 branch 字段
        if (from == 1) {
            kotlinx.serialization.json.JsonArray(
                data.jsonArray().map { JsonObject(it.jsonObject + ("branch" to JsonPrimitive("migrated"))) },
            )
        } else {
            data
        }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonArray() = this as kotlinx.serialization.json.JsonArray

    @Test
    fun `round trip with sealed auth and atomic write`(@TempDir dir: File) {
        val s = store(dir)
        assertIs<JsonFileStore.ReadResult.Missing>(s.read())
        val specs = listOf(
            RepoSpec("id1", "Vault", "/sdcard/Documents/Vault", "https://github.com/o/r.git", auth = AuthSpec.HttpsToken("me", "https-id1")),
            RepoSpec("id2", "Other", "/sdcard/x", "ssh://git@ssh.github.com:443/o/r.git", auth = AuthSpec.SshKey("key1")),
        )
        s.write(specs)
        assertFalse(File(dir, "config/repos.json.tmp").exists())
        val r = s.read()
        assertIs<JsonFileStore.ReadResult.Ok<List<RepoSpec>>>(r)
        assertEquals(specs, r.value)
        assertEquals(1, r.version)
        s.write(specs.take(1))
        assertEquals(1, (s.read() as JsonFileStore.ReadResult.Ok).value.size)
    }

    @Test
    fun `corrupt file is quarantined not overwritten`(@TempDir dir: File) {
        val s = store(dir)
        s.file.parentFile.mkdirs()
        s.file.writeText("{not json")
        val r = s.read()
        assertIs<JsonFileStore.ReadResult.Corrupt>(r)
        assertNotNull(r.backup)
        assertTrue(r.backup!!.name.startsWith("repos.json.bak-"))
        assertEquals("{not json", r.backup!!.readText())
        assertFalse(s.file.exists())
    }

    @Test
    fun `unknown keys are ignored and old versions migrate`(@TempDir dir: File) {
        val s1 = store(dir, version = 1)
        s1.file.parentFile.mkdirs()
        s1.file.writeText(
            """{"version":1,"data":[{"id":"a","name":"A","localPath":"/p","remoteUrl":"https://h/o/r.git","futureField":42}]}""",
        )
        val r1 = s1.read()
        assertIs<JsonFileStore.ReadResult.Ok<List<RepoSpec>>>(r1)
        assertEquals("main", r1.value.single().branch)

        val s2 = store(dir, version = 2)
        val r2 = s2.read()
        assertIs<JsonFileStore.ReadResult.Ok<List<RepoSpec>>>(r2)
        assertEquals("migrated", r2.value.single().branch)
        assertEquals(1, r2.version)
    }

    @Test
    fun `remote url parsing and normalization`() {
        val https = RemoteUrl.parse("https://github.com/happyEddiehorse/vaultsync-test-2026091901.git")
        assertEquals(RemoteUrl.Kind.HTTPS, https.kind)
        assertEquals("github.com", https.host)
        assertEquals("vaultsync-test-2026091901", https.repoName)

        val scp = RemoteUrl.parse("git@github.com:o/r.git")
        assertEquals(RemoteUrl.Kind.SSH, scp.kind)
        assertEquals("git", scp.user)
        assertEquals(22, scp.port)
        assertEquals("ssh://git@github.com/o/r.git", scp.normalized())
        assertEquals("ssh://git@github.com:2222/o/r.git", RemoteUrl.withPort("git@github.com:o/r.git", 2222))
        assertEquals("ssh://git@ssh.github.com:443/o/r.git", RemoteUrl.toGitHubPort443("git@github.com:o/r.git"))
        assertEquals("ssh://git@ssh.github.com:443/o/r.git", RemoteUrl.toGitHubPort443("ssh://git@ssh.github.com:443/o/r.git"))
        assertEquals("git@example.com:o/r.git", RemoteUrl.toGitHubPort443("git@example.com:o/r.git"))
        assertEquals("ssh://git@github.com/o/r.git", RemoteUrl.toGitHubPort22("ssh://git@ssh.github.com:443/o/r.git"))
        assertEquals("ssh://git@github.com/o/r.git", RemoteUrl.toGitHubPort22(RemoteUrl.toGitHubPort443("git@github.com:o/r.git")))
        assertEquals("git@github.com:o/r.git", RemoteUrl.toGitHubPort22("git@github.com:o/r.git"))
        assertEquals("https://github.com/o/r.git", RemoteUrl.toGitHubPort22("https://github.com/o/r.git"))

        val ssh = RemoteUrl.parse("ssh://git@ssh.github.com:443/o/r.git")
        assertEquals(443, ssh.port)
        assertEquals("ssh.github.com", ssh.host)
        assertEquals("r", ssh.repoName)

        assertEquals(RemoteUrl.Kind.UNSUPPORTED, RemoteUrl.parse("http://github.com/o/r.git").kind)
        assertEquals(RemoteUrl.Kind.UNSUPPORTED, RemoteUrl.parse("not a url").kind)
        // Windows 上 File.toURI() 是单斜杠的 file:/C:/…；盘符路径 C:/… 也不能被当成 scp 风格
        assertEquals(RemoteUrl.Kind.UNSUPPORTED, RemoteUrl.parse("file:/C:/Users/x/remote.git/").kind)
        assertEquals(RemoteUrl.Kind.UNSUPPORTED, RemoteUrl.parse("file:///C:/Users/x/remote.git/").kind)
        assertEquals(RemoteUrl.Kind.UNSUPPORTED, RemoteUrl.parse("C:/Users/x/remote.git").kind)
        assertEquals(RemoteUrl.Kind.UNSUPPORTED, RemoteUrl.parse("git://github.com/o/r.git").kind)
        assertEquals(RemoteUrl.Kind.SSH, RemoteUrl.parse("git@nas.local:/srv/git/vault.git").kind)
        assertEquals("https://***@h/o/r", RemoteUrl.redact("https://user:tok@h/o/r"))

        assertTrue(CredentialsFactory.usesSsh("git@github.com:o/r.git"))
        assertTrue(CredentialsFactory.usesSsh("ssh://git@ssh.github.com:443/o/r.git"))
        assertFalse(CredentialsFactory.usesSsh("https://github.com/o/r.git"))
        assertFalse(CredentialsFactory.usesSsh("file:/C:/Users/x/remote.git/"))
        assertFalse(CredentialsFactory.usesSsh("file:///tmp/remote.git/"))
        assertFalse(CredentialsFactory.usesSsh("/tmp/remote.git"))
    }
}
