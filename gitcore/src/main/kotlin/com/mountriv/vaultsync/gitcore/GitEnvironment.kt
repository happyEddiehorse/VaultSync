package com.mountriv.vaultsync.gitcore

import com.mountriv.vaultsync.gitcore.auth.SshSupport
import com.mountriv.vaultsync.gitcore.net.AbortableHttpConnectionFactory
import org.eclipse.jgit.storage.file.WindowCacheConfig
import org.eclipse.jgit.util.FS
import java.io.File

/**
 * 进程级 JGit 环境。整个进程只调用一次 [install]（app 的 Application.onCreate、测试的 @BeforeAll）。
 *
 * 做的事（原因见 memory/LESSONS.md L-006）：
 * - 把 `user.home` 指向可写目录：Android 上默认是 `/`，JGit 读写 `~/.gitconfig`、`~/.config/jgit/config` 会失败；
 * - 阻止 JGit 用 `git --version` 探测系统配置（Android 没有 git 可执行文件）；
 * - 文件系统时间戳分辨率探测放后台，避免首次打开仓库阻塞；
 * - 关闭 pack 文件 mmap，限制缓存与大对象阈值，避免共享存储上的不可靠 mmap 与大附件撑爆堆。
 */
object GitEnvironment {

    @Volatile
    private var installedHome: File? = null

    /** 当前已安装的 home 目录；未安装时为 null。 */
    val homeDir: File?
        get() = installedHome

    /**
     * @param backgroundFsProbe 文件系统时间戳分辨率探测是否放后台。生产环境 true（避免首次打开仓库阻塞）；
     *   测试传 false：探测会在仓库的 `.git/objects` 里写 `.probe-*` 临时文件，后台模式下可能比测试活得久，
     *   导致 Windows 上 @TempDir 清理失败（LESSONS L-006）。
     */
    @Synchronized
    fun install(homeDir: File, backgroundFsProbe: Boolean = true) {
        installedHome?.let { existing ->
            check(existing.canonicalPath == homeDir.canonicalPath) {
                "GitEnvironment already installed with home=$existing, requested $homeDir"
            }
            return
        }
        homeDir.mkdirs()
        File(homeDir, "ssh").mkdirs()

        System.setProperty("user.home", homeDir.absolutePath)
        FS.DETECTED.setUserHome(homeDir)
        FS.DETECTED.setGitSystemConfig(File(homeDir, "gitconfig-system"))
        FS.FileStoreAttributes.setBackground(backgroundFsProbe)

        WindowCacheConfig().apply {
            isPackedGitMMAP = false
            packedGitLimit = 32L * 1024 * 1024
            deltaBaseCacheLimit = 16 * 1024 * 1024
            streamFileThreshold = 32 * 1024 * 1024
        }.install()

        // JSch 全局设置：日志经 SLF4J，ed25519/X25519 走 BouncyCastle（docs/SYNC_ENGINE.md §8）
        SshSupport.install()
        // HTTP 连接登记到当前任务，取消时能立即断开（D-035）
        AbortableHttpConnectionFactory.install()

        installedHome = homeDir
    }

    /** 仅供测试：允许在同一 JVM 内换 home 目录。 */
    @Synchronized
    internal fun resetForTests() {
        installedHome = null
    }
}
