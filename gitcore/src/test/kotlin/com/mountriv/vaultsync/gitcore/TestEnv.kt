package com.mountriv.vaultsync.gitcore

import com.mountriv.vaultsync.gitcore.log.InMemorySyncLogger
import com.mountriv.vaultsync.gitcore.model.AuthSpec
import com.mountriv.vaultsync.gitcore.model.GitIdentity
import com.mountriv.vaultsync.gitcore.model.RepoSpec
import com.mountriv.vaultsync.gitcore.model.SecretProvider
import com.mountriv.vaultsync.gitcore.model.SyncOutcome
import com.mountriv.vaultsync.gitcore.sync.SyncEngine
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.RefSpec
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

/** 测试公用：进程级 JGit 环境（只装一次）、裸远端、"桌面端"工作副本、手机端 RepoSpec。 */
object TestEnv {
    val home: File by lazy {
        val dir = File(System.getProperty("java.io.tmpdir"), "vaultsync-test-githome-${UUID.randomUUID()}")
        GitEnvironment.resetForTests()
        // 同步探测：避免后台 .probe-* 文件导致 @TempDir 清理失败（LESSONS L-006）
        GitEnvironment.install(dir, backgroundFsProbe = false)
        dir
    }

    val phone = GitIdentity("Phone", "phone@example.com")
    val desktop = PersonIdent("Desktop", "desktop@example.com")
    val fixedClock: () -> ZonedDateTime = { ZonedDateTime.of(2026, 9, 19, 13, 5, 9, 0, ZoneId.of("Asia/Shanghai")) }

    fun ensure() {
        home
    }

    /** 创建裸远端，返回 file:// URL。 */
    fun bareRemote(dir: File, initialBranch: String = "main"): String {
        Git.init().setBare(true).setDirectory(dir).setInitialBranch(initialBranch).call().close()
        return dir.toURI().toString()
    }

    /** "桌面端"：用原生 JGit API 操作的工作副本。 */
    class Desktop(val dir: File, val git: Git) : AutoCloseable {
        private var closed = false

        fun write(rel: String, content: String) {
            val f = File(dir, rel)
            f.parentFile?.mkdirs()
            f.writeText(content, Charsets.UTF_8)
        }

        fun writeBytes(rel: String, content: ByteArray) {
            val f = File(dir, rel)
            f.parentFile?.mkdirs()
            f.writeBytes(content)
        }

        fun delete(rel: String) {
            check(File(dir, rel).delete())
        }

        fun read(rel: String): String = File(dir, rel).readText(Charsets.UTF_8)

        fun commitAll(message: String) {
            git.add().addFilepattern(".").call()
            git.add().addFilepattern(".").setUpdate(true).call()
            git.commit().setMessage(message).setAuthor(desktop).setCommitter(desktop).call()
        }

        fun push(branch: String = "main") {
            // 显式 refspec：PushCommand.add(name) 在本地分支不存在时会静默改推 HEAD，掩盖测试错误
            val results = git.push().setRemote("origin").setRefSpecs(RefSpec("refs/heads/$branch:refs/heads/$branch")).call()
            val statuses = results.flatMap { it.remoteUpdates }.map { it.status.name }
            check(statuses.all { it == "OK" || it == "UP_TO_DATE" }) { "desktop push failed: $statuses" }
        }

        fun pull(branch: String = "main") {
            git.fetch().setRemote("origin").call()
            val ref = git.repository.exactRef("refs/remotes/origin/$branch") ?: return
            val result = git.merge().include(ref).setFastForward(MergeCommand.FastForwardMode.FF).call()
            check(result.mergeStatus.isSuccessful) { "desktop pull failed: ${result.mergeStatus}" }
        }

        fun headName(): String = git.repository.resolve("HEAD")!!.name

        /** 关闭 Git 与仓库（释放 pack 文件句柄，否则 Windows 上 @TempDir 删不掉）。可重复调用。 */
        override fun close() {
            if (closed) return
            closed = true
            git.close()
        }
    }

    private val openDesktops = ArrayList<Desktop>()

    fun cloneDesktop(url: String, dir: File, branch: String = "main"): Desktop {
        val git = Git.cloneRepository().setURI(url).setDirectory(dir).setBranch(branch).call()
        // 远端为空时 JGit 5.13 的 CloneCommand 不会检出任何分支，HEAD 停在 init 默认的 master；手动指向目标分支
        if (git.repository.resolve("HEAD") == null) {
            git.repository.updateRef("HEAD").link("refs/heads/$branch")
        }
        return Desktop(dir, git).also { synchronized(openDesktops) { openDesktops += it } }
    }

    /** 每个测试结束时调用（@AfterEach），关闭所有没有显式关闭的桌面副本。 */
    fun closeDesktops() {
        val list = synchronized(openDesktops) { ArrayList(openDesktops).also { openDesktops.clear() } }
        list.forEach { it.close() }
    }

    fun spec(dir: File, url: String, branch: String = "main", name: String = dir.name): RepoSpec = RepoSpec(
        id = UUID.randomUUID().toString(),
        name = name,
        localPath = dir.absolutePath,
        remoteUrl = url,
        branch = branch,
        auth = AuthSpec.None,
    )

    fun engine(): SyncEngine = SyncEngine(deviceName = "phone", clock = fixedClock)

    /** 跑一次同步并返回结果；失败时把日志附在断言信息里。 */
    fun sync(engine: SyncEngine, spec: RepoSpec, logger: InMemorySyncLogger = InMemorySyncLogger()): SyncOutcome {
        val outcome = engine.sync(spec, SecretProvider.NONE, phone, logger)
        if (outcome is SyncOutcome.Failed) {
            System.err.println("sync failed: ${outcome.stage} ${outcome.message}\n$logger")
            outcome.cause?.printStackTrace()
        }
        return outcome
    }
}

fun File.writeVaultFile(rel: String, content: String) {
    val f = File(this, rel)
    f.parentFile?.mkdirs()
    f.writeText(content, Charsets.UTF_8)
}

fun File.readVaultFile(rel: String): String = File(this, rel).readText(Charsets.UTF_8)
