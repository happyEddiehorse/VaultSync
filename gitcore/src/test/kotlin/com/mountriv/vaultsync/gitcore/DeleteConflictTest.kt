package com.mountriv.vaultsync.gitcore

import com.mountriv.vaultsync.gitcore.log.InMemorySyncLogger
import com.mountriv.vaultsync.gitcore.merge.ConflictKind
import com.mountriv.vaultsync.gitcore.merge.MergeModel
import com.mountriv.vaultsync.gitcore.model.SecretProvider
import com.mountriv.vaultsync.gitcore.model.SyncOutcome
import com.mountriv.vaultsync.gitcore.repo.VaultRepository
import com.mountriv.vaultsync.gitcore.sync.RepoOnboarding
import org.eclipse.jgit.lib.RepositoryState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 一侧删除、二进制文件的冲突只允许整文件选择。 */
class DeleteConflictTest {

    @BeforeEach
    fun setUp() = TestEnv.ensure()

    @AfterEach
    fun tearDown() = TestEnv.closeDesktops()

    private fun setup(dir: File, png: ByteArray): Triple<String, TestEnv.Desktop, File> {
        val url = TestEnv.bareRemote(File(dir, "remote.git"))
        val desktop = TestEnv.cloneDesktop(url, File(dir, "desktop"))
        desktop.write("Note.md", "note\n")
        desktop.writeBytes("attachments/pic.png", png)
        desktop.commitAll("desktop 1")
        desktop.push()
        val vault = File(dir, "phone-vault")
        assertIs<RepoOnboarding.Result.Ready>(RepoOnboarding.clone(TestEnv.spec(vault, url), SecretProvider.NONE, InMemorySyncLogger()))
        return Triple(url, desktop, vault)
    }

    private fun fakePng(seed: Int): ByteArray = ByteArray(2048) { i -> if (i % 7 == 0) 0 else ((i * 31 + seed) and 0xff).toByte() }

    @Test
    fun `deleted on desktop, modified on phone`(@TempDir dir: File) {
        val (url, desktop, vault) = setup(dir, fakePng(1))
        val spec = TestEnv.spec(vault, url)
        desktop.delete("Note.md")
        desktop.commitAll("desktop deletes")
        desktop.push()
        vault.writeVaultFile("Note.md", "note edited on phone\n")

        val o = TestEnv.sync(TestEnv.engine(), spec)
        assertIs<SyncOutcome.Conflicts>(o)
        VaultRepository.open(vault, InMemorySyncLogger()).use { repo ->
            val file = MergeModel.listConflicts(repo).single()
            assertEquals(ConflictKind.DELETED_BY_THEM, file.kind)
            assertNull(file.blocks)
            assertTrue(file.hasOurs)
            assertFalse(file.hasTheirs)
            // 保留本地版本
            MergeModel.resolveTakeOurs(repo, "Note.md")
            assertEquals(RepositoryState.MERGING_RESOLVED, repo.state)
        }
        assertIs<SyncOutcome.Success>(TestEnv.sync(TestEnv.engine(), spec))
        desktop.pull()
        assertEquals("note edited on phone\n", desktop.read("Note.md"))
    }

    @Test
    fun `deleted on phone, modified on desktop, resolved by deleting`(@TempDir dir: File) {
        val (url, desktop, vault) = setup(dir, fakePng(2))
        val spec = TestEnv.spec(vault, url)
        desktop.write("Note.md", "note edited on desktop\n")
        desktop.commitAll("desktop edits")
        desktop.push()
        File(vault, "Note.md").delete()

        assertIs<SyncOutcome.Conflicts>(TestEnv.sync(TestEnv.engine(), spec))
        VaultRepository.open(vault, InMemorySyncLogger()).use { repo ->
            val file = MergeModel.listConflicts(repo).single()
            assertEquals(ConflictKind.DELETED_BY_US, file.kind)
            assertFalse(file.hasOurs)
            assertTrue(file.hasTheirs)
            MergeModel.resolveDelete(repo, "Note.md")
            assertEquals(RepositoryState.MERGING_RESOLVED, repo.state)
        }
        assertIs<SyncOutcome.Success>(TestEnv.sync(TestEnv.engine(), spec))
        desktop.pull()
        assertFalse(File(desktop.dir, "Note.md").exists())
    }

    @Test
    fun `binary conflict offers whole-file choice only`(@TempDir dir: File) {
        val (url, desktop, vault) = setup(dir, fakePng(3))
        val spec = TestEnv.spec(vault, url)
        val desktopPng = fakePng(4)
        val phonePng = fakePng(5)
        desktop.writeBytes("attachments/pic.png", desktopPng)
        desktop.commitAll("desktop pic")
        desktop.push()
        File(vault, "attachments/pic.png").writeBytes(phonePng)

        assertIs<SyncOutcome.Conflicts>(TestEnv.sync(TestEnv.engine(), spec))
        VaultRepository.open(vault, InMemorySyncLogger()).use { repo ->
            val file = MergeModel.listConflicts(repo).single()
            assertEquals(ConflictKind.BOTH_MODIFIED, file.kind)
            assertTrue(file.isBinary)
            assertNull(file.blocks)
            assertEquals(2048L, file.oursSize)
            MergeModel.resolveTakeTheirs(repo, "attachments/pic.png")
        }
        assertContentEquals(desktopPng, File(vault, "attachments/pic.png").readBytes())
        assertIs<SyncOutcome.Success>(TestEnv.sync(TestEnv.engine(), spec))
    }
}
