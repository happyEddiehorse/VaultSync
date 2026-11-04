package com.mountriv.vaultsync.gitcore

import com.mountriv.vaultsync.gitcore.log.InMemorySyncLogger
import com.mountriv.vaultsync.gitcore.model.SecretProvider
import com.mountriv.vaultsync.gitcore.model.SyncOutcome
import com.mountriv.vaultsync.gitcore.model.SyncStage
import com.mountriv.vaultsync.gitcore.sync.RepoOnboarding
import com.mountriv.vaultsync.gitcore.sync.SyncEngine
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** 推送时远端又有新提交 → 自动重新抓取合并后再推；超过重试次数则失败。 */
class PushRejectedTest {

    @BeforeEach
    fun setUp() = TestEnv.ensure()

    @AfterEach
    fun tearDown() = TestEnv.closeDesktops()

    private fun setup(dir: File): Triple<String, TestEnv.Desktop, File> {
        val url = TestEnv.bareRemote(File(dir, "remote.git"))
        val desktop = TestEnv.cloneDesktop(url, File(dir, "desktop"))
        desktop.write("A.md", "a\n")
        desktop.commitAll("desktop 1")
        desktop.push()
        val vault = File(dir, "phone-vault")
        assertIs<RepoOnboarding.Result.Ready>(RepoOnboarding.clone(TestEnv.spec(vault, url), SecretProvider.NONE, InMemorySyncLogger()))
        return Triple(url, desktop, vault)
    }

    @Test
    fun `push rejected once then succeeds after re-fetch`(@TempDir dir: File) {
        val (url, desktop, vault) = setup(dir)
        val spec = TestEnv.spec(vault, url)
        val engine = SyncEngine(deviceName = "phone", clock = TestEnv.fixedClock)
        var pushes = 0
        engine.beforePushHook = {
            if (pushes++ == 0) {
                desktop.write("B.md", "b\n")
                desktop.commitAll("desktop sneaks in")
                desktop.push()
            }
        }
        vault.writeVaultFile("C.md", "c\n")
        val logger = InMemorySyncLogger()
        val o = TestEnv.sync(engine, spec, logger)
        assertIs<SyncOutcome.Success>(o, logger.toString())
        assertEquals(1, o.summary.retries)
        assertEquals("OK", o.summary.pushStatus)
        assertEquals("MERGED", o.summary.mergeStatus)
        assertEquals("b\n", vault.readVaultFile("B.md"))
        desktop.pull()
        assertEquals("c\n", desktop.read("C.md"))
    }

    @Test
    fun `gives up after max retries`(@TempDir dir: File) {
        val (url, desktop, vault) = setup(dir)
        val spec = TestEnv.spec(vault, url)
        val engine = SyncEngine(deviceName = "phone", maxPushRetries = 2, clock = TestEnv.fixedClock)
        var n = 0
        engine.beforePushHook = {
            desktop.write("D${n++}.md", "d\n")
            desktop.commitAll("desktop keeps pushing")
            desktop.push()
        }
        vault.writeVaultFile("C.md", "c\n")
        val o = engine.sync(spec, SecretProvider.NONE, TestEnv.phone, InMemorySyncLogger())
        assertIs<SyncOutcome.Failed>(o)
        assertEquals(SyncStage.PUSH, o.stage)
        assertEquals(3, n, "initial attempt + 2 retries")
    }
}
