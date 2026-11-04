package com.mountriv.vaultsync.gitcore

import com.mountriv.vaultsync.gitcore.log.InMemorySyncLogger
import com.mountriv.vaultsync.gitcore.model.SecretProvider
import com.mountriv.vaultsync.gitcore.model.SyncOutcome
import com.mountriv.vaultsync.gitcore.repo.VaultRepository
import com.mountriv.vaultsync.gitcore.sync.RepoOnboarding
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.lib.Repository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * L-037：检出（克隆、合并快进）后 index 里不留 smudged 条目，否则之后每次 status 都要把这些文件整个重读一遍。
 * JGit 检出前就锁了 index，同一秒内写出的文件必然被标上；刷新后应全部去掉，工作区仍然干净。
 */
class RacyIndexTest {

    @BeforeEach
    fun setUp() = TestEnv.ensure()

    @AfterEach
    fun tearDown() = TestEnv.closeDesktops()

    private fun smudged(repo: Repository): List<String> {
        val dc = DirCache.read(repo)
        return (0 until dc.entryCount).map { dc.getEntry(it) }.filter { it.isSmudged }.map { it.pathString }
    }

    private fun big(seed: Int) = ByteArray(2 shl 20) { (it * 31 + seed).toByte() }

    private fun assertCleanIndex(vault: File) {
        VaultRepository.open(vault, InMemorySyncLogger()).use { repo ->
            assertEquals(emptyList(), smudged(repo.repository))
            assertTrue(repo.status().isClean, "working tree should be clean")
        }
    }

    @Test
    fun cloneLeavesNoSmudgedEntries(@TempDir dir: File) {
        val url = TestEnv.bareRemote(File(dir, "remote.git"))
        val desktop = TestEnv.cloneDesktop(url, File(dir, "desktop"))
        desktop.write("a.md", "a\n")
        desktop.writeBytes("big.bin", big(0))
        desktop.commitAll("desktop 1")
        desktop.push()
        val vault = File(dir, "phone-vault")
        val logger = InMemorySyncLogger()
        assertIs<RepoOnboarding.Result.Ready>(RepoOnboarding.clone(TestEnv.spec(vault, url), SecretProvider.NONE, logger))
        assertTrue("index refreshed" in logger.toString(), "clone should refresh the index:\n$logger")
        assertCleanIndex(vault)
    }

    @Test
    fun fastForwardLeavesNoSmudgedEntries(@TempDir dir: File) {
        val url = TestEnv.bareRemote(File(dir, "remote.git"))
        val desktop = TestEnv.cloneDesktop(url, File(dir, "desktop"))
        desktop.write("a.md", "a\n")
        desktop.commitAll("desktop 1")
        desktop.push()
        val vault = File(dir, "phone-vault")
        val spec = TestEnv.spec(vault, url)
        assertIs<RepoOnboarding.Result.Ready>(RepoOnboarding.clone(spec, SecretProvider.NONE, InMemorySyncLogger()))

        desktop.writeBytes("m7-big/big.bin", big(1))
        desktop.write("a.md", "a2\n")
        desktop.commitAll("desktop 2")
        desktop.push()
        assertIs<SyncOutcome.Success>(TestEnv.sync(TestEnv.engine(), spec))
        assertCleanIndex(vault)
        assertEquals("a2\n", vault.readVaultFile("a.md"))
    }

    @Test
    fun refreshKeepsRealModificationsVisible(@TempDir dir: File) {
        val url = TestEnv.bareRemote(File(dir, "remote.git"))
        val desktop = TestEnv.cloneDesktop(url, File(dir, "desktop"))
        desktop.write("a.md", "a\n")
        desktop.write("b.md", "b\n")
        desktop.commitAll("desktop 1")
        desktop.push()
        val vault = File(dir, "phone-vault")
        assertIs<RepoOnboarding.Result.Ready>(RepoOnboarding.clone(TestEnv.spec(vault, url), SecretProvider.NONE, InMemorySyncLogger()))
        VaultRepository.open(vault, InMemorySyncLogger()).use { repo ->
            // 同长度的改动：只靠长度和时间分辨不出来，必须核对内容
            vault.writeVaultFile("a.md", "A\n")
            File(repo.gitDir, "index").setLastModified(System.currentTimeMillis())
            repo.refreshIndex()
            assertEquals(setOf("a.md"), repo.status().modified)
        }
    }
}
