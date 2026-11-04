package com.mountriv.vaultsync.gitcore.repo

import com.mountriv.vaultsync.gitcore.log.SyncLogger
import org.eclipse.jgit.internal.storage.file.FileRepository
import org.eclipse.jgit.internal.storage.file.GC
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.storage.pack.PackConfig
import java.util.Collections

/**
 * 手动仓库维护（docs/SYNC_ENGINE.md §10、D-014）：`packRefs` → `repack`（内含 `prunePacked`）→ `prune`。
 * 不调用 `GC.gc()`：它经过 `GC.PidLock`，在 Android 上因缺少 `java.lang.management` 崩溃（L-002）；
 * 这里用到的方法都不碰 PidLock（已对照 5.13.5 源码）。
 * 不可达的松散对象按 `gc.pruneExpire`（默认两周）保留，与 git 默认一致；旧 pack 则立即替换（JGit 默认保留一小时内的 pack，
 * 是为了不误删并发进程刚写入的 pack——维护与同步在应用内串行执行，没有这种并发；旧 pack 里不可达的对象会先转成松散对象再按两周规则处理）。
 */
object RepoMaintenance {

    /** 对象存储的概况。 */
    data class Stats(
        val looseObjects: Long,
        val looseBytes: Long,
        val packFiles: Long,
        val packedObjects: Long,
        val packBytes: Long,
        val looseRefs: Long,
    ) {
        val totalBytes: Long get() = looseBytes + packBytes

        /** 松散对象超过 1000 个或 pack 超过 20 个时建议维护（docs/SYNC_ENGINE.md §10）。 */
        val recommended: Boolean get() = looseObjects > LOOSE_OBJECTS_THRESHOLD || packFiles > PACK_FILES_THRESHOLD
    }

    data class Result(val before: Stats, val after: Stats, val durationMs: Long)

    const val LOOSE_OBJECTS_THRESHOLD = 1000L
    const val PACK_FILES_THRESHOLD = 20L

    fun stats(repo: VaultRepository): Stats = toStats(gc(repo).statistics)

    /** 执行维护。阻塞，调用方放 IO 线程；[monitor] 的取消会在各步骤之间生效。 */
    fun run(repo: VaultRepository, logger: SyncLogger, monitor: ProgressMonitor = NullProgressMonitor.INSTANCE): Result {
        val started = System.currentTimeMillis()
        val gc = gc(repo).setProgressMonitor(monitor)
        val before = toStats(gc.statistics)
        logger.info("maintenance start: $before")
        gc.packRefs()
        val packs = gc.repack()
        logger.info("repacked into ${packs.size} pack(s)")
        gc.prune(Collections.emptySet())
        val after = toStats(gc.statistics)
        val result = Result(before, after, System.currentTimeMillis() - started)
        logger.info("maintenance done in ${result.durationMs} ms: $after")
        return result
    }

    private fun gc(repo: VaultRepository): GC {
        val fileRepo = repo.repository as? FileRepository
            ?: throw IllegalStateException("maintenance needs a file-based repository")
        return GC(fileRepo).apply {
            setPackExpireAgeMillis(0)
            // 手机内存有限：单线程压缩、限制增量搜索内存；大文件（多为图片/PDF，本身已压缩）不做增量
            setPackConfig(
                PackConfig(fileRepo).apply {
                    threads = 1
                    bigFileThreshold = 16 * 1024 * 1024
                    deltaSearchMemoryLimit = 64L * 1024 * 1024
                    deltaCacheSize = 16L * 1024 * 1024
                },
            )
        }
    }

    private fun toStats(s: GC.RepoStatistics) = Stats(
        looseObjects = s.numberOfLooseObjects,
        looseBytes = s.sizeOfLooseObjects,
        packFiles = s.numberOfPackFiles,
        packedObjects = s.numberOfPackedObjects,
        packBytes = s.sizeOfPackedObjects,
        looseRefs = s.numberOfLooseRefs,
    )
}
