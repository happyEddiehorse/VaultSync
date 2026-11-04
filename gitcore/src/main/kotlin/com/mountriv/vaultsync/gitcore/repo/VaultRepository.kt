package com.mountriv.vaultsync.gitcore.repo

import com.mountriv.vaultsync.gitcore.auth.TransportAuth
import com.mountriv.vaultsync.gitcore.log.SyncLogger
import com.mountriv.vaultsync.gitcore.model.FailureKind
import com.mountriv.vaultsync.gitcore.model.GitIdentity
import com.mountriv.vaultsync.gitcore.model.SyncException
import com.mountriv.vaultsync.gitcore.model.SyncStage
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeCommand
import org.eclipse.jgit.api.MergeResult
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.Status
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.dircache.DirCacheEntry
import org.eclipse.jgit.lib.ConfigConstants
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.NullProgressMonitor
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectInserter
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.FetchResult
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.TreeFilter
import java.io.Closeable
import java.io.File

/**
 * 单个 Obsidian 库仓库的操作封装（JGit Porcelain 之上）。`.git` 位于库目录内。
 *
 * 生命周期：[open] / [init] / [clone] 得到实例 → 使用 → [close]。实例不是线程安全的；同一仓库的并发由 SyncEngine 互斥。
 * 完整同步流程见 docs/SYNC_ENGINE.md。
 */
class VaultRepository private constructor(
    val workTree: File,
    val git: Git,
    private val logger: SyncLogger,
    /**
     * 是否由本类负责关闭 [Repository]。`Git.init()` / `Git.cloneRepository()` 返回的 [Git] 自己会关闭仓库；
     * 而 `Git(repo)` 包装已有仓库时 **不会**（JGit 的 `closeRepo=false`），必须由我们关闭，否则 pack 文件句柄一直被占用
     * （Windows 上表现为临时目录删不掉；Android 上表现为句柄泄漏）。
     */
    private val ownsRepository: Boolean,
) : Closeable {

    val repository: Repository get() = git.repository

    val gitDir: File get() = repository.directory

    /** 当前分支短名（如 `main`，未出生分支也返回名字）；HEAD 分离时为 null。 */
    val currentBranch: String?
        get() = repository.branch?.takeIf { !ObjectId.isId(it) }

    val state: RepositoryState get() = repository.repositoryState

    /**
     * 写入共享存储（sdcardfs/FUSE）下必需的配置（幂等）。原因见 memory/LESSONS.md L-007 与 L-002。
     * 打开或创建仓库后必须调用。传入 [remoteUrl] / [branch] 时同时写 remote / branch 配置。
     */
    fun ensureSharedStorageConfig(remoteUrl: String? = null, branch: String? = null) {
        val config = repository.config
        var changed = false

        fun setBool(section: String, key: String, value: Boolean) {
            if (config.getString(section, null, key)?.equals(value.toString(), ignoreCase = true) != true) {
                config.setBoolean(section, null, key, value)
                changed = true
            }
        }

        fun setInt(section: String, key: String, value: Int) {
            if (config.getString(section, null, key) != value.toString()) {
                config.setInt(section, null, key, value)
                changed = true
            }
        }

        setBool(ConfigConstants.CONFIG_CORE_SECTION, ConfigConstants.CONFIG_KEY_FILEMODE, false)
        setBool(ConfigConstants.CONFIG_CORE_SECTION, ConfigConstants.CONFIG_KEY_SYMLINKS, false)
        // JGit 5.13 的 ConfigConstants 没有 ignorecase 常量
        setBool(ConfigConstants.CONFIG_CORE_SECTION, "ignorecase", true)
        setBool(ConfigConstants.CONFIG_CORE_SECTION, ConfigConstants.CONFIG_KEY_AUTOCRLF, false)
        // JGit 的自动 GC 在 Android 上会因缺少 java.lang.management 崩溃（L-002）
        setInt(ConfigConstants.CONFIG_GC_SECTION, ConfigConstants.CONFIG_KEY_AUTO, 0)
        setInt(ConfigConstants.CONFIG_GC_SECTION, ConfigConstants.CONFIG_KEY_AUTOPACKLIMIT, 0)

        if (remoteUrl != null) {
            val existing = config.getString(ConfigConstants.CONFIG_REMOTE_SECTION, Constants.DEFAULT_REMOTE_NAME, "url")
            if (existing != remoteUrl) {
                config.setString(ConfigConstants.CONFIG_REMOTE_SECTION, Constants.DEFAULT_REMOTE_NAME, "url", remoteUrl)
                changed = true
            }
            if (config.getStringList(ConfigConstants.CONFIG_REMOTE_SECTION, Constants.DEFAULT_REMOTE_NAME, "fetch")
                    .none { it == FETCH_ALL_HEADS.toString() }
            ) {
                config.setString(
                    ConfigConstants.CONFIG_REMOTE_SECTION, Constants.DEFAULT_REMOTE_NAME, "fetch",
                    FETCH_ALL_HEADS.toString(),
                )
                changed = true
            }
        }
        if (branch != null) {
            if (config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_REMOTE)
                != Constants.DEFAULT_REMOTE_NAME
            ) {
                config.setString(
                    ConfigConstants.CONFIG_BRANCH_SECTION, branch,
                    ConfigConstants.CONFIG_KEY_REMOTE, Constants.DEFAULT_REMOTE_NAME,
                )
                changed = true
            }
            val mergeRef = Constants.R_HEADS + branch
            if (config.getString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_MERGE) != mergeRef) {
                config.setString(ConfigConstants.CONFIG_BRANCH_SECTION, branch, ConfigConstants.CONFIG_KEY_MERGE, mergeRef)
                changed = true
            }
        }

        if (changed) {
            config.save()
            logger.info("repository config updated for shared storage: ${workTree.name}")
        }
    }

    /** 工作区状态（含未跟踪文件）。 */
    fun status(): Status = git.status().call()

    fun headId(): ObjectId? = repository.resolve(Constants.HEAD)

    fun remoteTrackingId(branch: String): ObjectId? = repository.exactRef(remoteTrackingRef(branch))?.objectId

    /** [ancestor] 是否是 [tip] 的祖先（或相同）。 */
    fun isAncestor(ancestor: ObjectId, tip: ObjectId): Boolean = RevWalk(repository).use { walk ->
        walk.isMergedInto(walk.parseCommit(ancestor), walk.parseCommit(tip))
    }

    fun commitAll(message: String, identity: GitIdentity): ObjectId? = commitAll(identity) { message }

    /**
     * 暂存全部变更（新增、修改、删除）并提交；[messageFor] 收到本次提交涉及的路径（已排序）。没有变更时返回 null。
     * 先用 `status` 找出变了的路径，只把它们交给 `AddCommand` / `RmCommand --cached`（L-039）：
     * JGit 5.13 的 `add .` 会把每个文件都读两遍、压缩写一个临时对象再丢掉，库里有 200 MB 附件时什么都没改也要两分钟。
     * 读要暂存的文件时按 KiB 报进度（任务 [TASK_STAGING]），并在读的过程中检查 [cancel]；取消时抛 [ProgressFileTreeIterator.Cancelled]（包在 JGit 的异常里）。
     */
    fun commitAll(
        identity: GitIdentity,
        monitor: ProgressMonitor = NullProgressMonitor.INSTANCE,
        cancel: () -> Boolean = { false },
        messageFor: (List<String>) -> String,
    ): ObjectId? {
        val before = status()
        val toAdd = (before.untracked + before.modified).sorted()
        val toRemove = before.missing.sorted()
        if (toRemove.isNotEmpty()) {
            val rm = git.rm().setCached(true)
            toRemove.forEach(rm::addFilepattern)
            rm.call()
        }
        if (toAdd.isNotEmpty()) {
            val kib = toAdd.sumOf { File(workTree, it).length() } / 1024
            monitor.beginTask(TASK_STAGING, kib.coerceIn(1, Int.MAX_VALUE.toLong()).toInt())
            val add = git.add().setWorkingTreeIterator(ProgressFileTreeIterator.create(repository, monitor, cancel))
            toAdd.forEach(add::addFilepattern)
            add.call()
            monitor.endTask()
        }
        val status = if (toAdd.isEmpty() && toRemove.isEmpty()) before else status()
        val paths = (status.added + status.changed + status.removed).sorted()
        if (paths.isEmpty()) {
            logger.info("nothing to commit")
            return null
        }
        val large = paths.filter { File(workTree, it).length() > LARGE_FILE_WARN_BYTES }
        if (large.isNotEmpty()) {
            logger.warn("committing ${large.size} file(s) larger than ${LARGE_FILE_WARN_BYTES / (1024 * 1024)} MB: $large")
        }
        val ident = PersonIdent(identity.name, identity.email)
        val commit = git.commit()
            .setMessage(messageFor(paths))
            .setAuthor(ident)
            .setCommitter(ident)
            .call()
        logger.info("committed ${commit.name.take(7)}: ${paths.size} file(s)")
        return commit.id
    }

    /**
     * 抓取 origin 的全部分支到 `refs/remotes/origin/` 下（远端为空也不会报错），并清理远端已删除的分支。
     */
    fun fetch(
        auth: TransportAuth,
        monitor: ProgressMonitor = NullProgressMonitor.INSTANCE,
        timeoutSec: Int = DEFAULT_TIMEOUT_SEC,
    ): FetchResult {
        val cmd = git.fetch()
            .setRemote(Constants.DEFAULT_REMOTE_NAME)
            .setRefSpecs(FETCH_ALL_HEADS)
            .setRemoveDeletedRefs(true)
            .setTimeout(timeoutSec)
            .setProgressMonitor(monitor)
        auth.applyTo(cmd)
        return cmd.call()
    }

    /** 把 refs/remotes/origin/[branch] 合并进当前分支（允许快进，自动提交）。 */
    fun mergeRemote(branch: String, message: String, monitor: ProgressMonitor = NullProgressMonitor.INSTANCE): MergeResult {
        val ref = repository.exactRef(remoteTrackingRef(branch))
            ?: throw SyncException(SyncStage.MERGE, "remote-tracking branch ${remoteTrackingRef(branch)} not found (fetch first)")
        return git.merge()
            .include(ref)
            .setFastForward(MergeCommand.FastForwardMode.FF)
            .setCommit(true)
            .setMessage(message)
            .setProgressMonitor(monitor)
            .call()
    }

    data class PushOutcome(val status: RemoteRefUpdate.Status, val message: String?)

    /** 推送 refs/heads/[branch] 到 origin 的同名分支。 */
    fun push(
        branch: String,
        auth: TransportAuth,
        monitor: ProgressMonitor = NullProgressMonitor.INSTANCE,
        timeoutSec: Int = DEFAULT_TIMEOUT_SEC,
    ): PushOutcome {
        val refName = Constants.R_HEADS + branch
        val cmd = git.push()
            .setRemote(Constants.DEFAULT_REMOTE_NAME)
            .setRefSpecs(RefSpec("$refName:$refName"))
            .setTimeout(timeoutSec)
            .setProgressMonitor(monitor)
        auth.applyTo(cmd)
        val results = cmd.call().toList()
        val updates = results.flatMap { it.remoteUpdates }
        val update = updates.firstOrNull { it.remoteName == refName } ?: updates.firstOrNull()
        val message = update?.message ?: results.joinToString("\n") { it.messages }.trim().ifEmpty { null }
        return PushOutcome(update?.status ?: RemoteRefUpdate.Status.NOT_ATTEMPTED, message)
    }

    /** 当前仍有冲突（index 中有 stage>0 条目）的路径，已排序。 */
    fun conflictingPaths(): List<String> = status().conflicting.sorted()

    /** 冲突全部解决后（状态 MERGING_RESOLVED）创建合并提交；JGit 会自动把 MERGE_HEAD 作为第二父提交。 */
    fun completeMerge(identity: GitIdentity): ObjectId {
        val st = state
        if (st != RepositoryState.MERGING_RESOLVED) {
            throw SyncException(SyncStage.RESOLVE_CONFLICTS, "cannot complete merge in state $st")
        }
        val message = repository.readMergeCommitMsg()?.takeIf { it.isNotBlank() } ?: "Merge remote-tracking branch"
        val ident = PersonIdent(identity.name, identity.email)
        val commit = git.commit().setMessage(message).setAuthor(ident).setCommitter(ident).call()
        logger.info("merge completed: ${commit.name.take(7)}")
        return commit.id
    }

    /** 放弃进行中的合并：工作区与 index 回到 HEAD（本地提交不丢，远端提交仍在 refs/remotes）。 */
    fun abortMerge() {
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(Constants.HEAD).call()
        logger.warn("merge aborted; working tree reset to HEAD")
    }

    /**
     * 合并中途出错（不是冲突，例如检出某个文件时抛异常，L-036）后回到合并前：分支、index、工作区都回到 [preMergeHead]，
     * 清掉 MERGE_HEAD / MERGE_MSG（否则仓库停在"冲突已全部处理"的假状态，下次同步会把检出了一半的结果提交），
     * 再删掉检出中断时 JGit 留下的临时文件（`._<文件名><数字>.tmp`，否则下次同步会把它当新文件提交）。
     * 只在这次合并涉及的文件所在的目录里找临时文件。
     */
    fun rollBackFailedMerge(preMergeHead: ObjectId, mergedId: ObjectId) {
        val dirs = changedDirs(preMergeHead, mergedId)
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(preMergeHead.name).call()
        repository.writeMergeHeads(null)
        repository.writeMergeCommitMsg(null)
        var removed = 0
        for (dir in dirs) {
            val files = File(workTree, dir).listFiles() ?: continue
            for (f in files) {
                if (f.isFile && CHECKOUT_TEMP.matches(f.name) && f.delete()) removed++
            }
        }
        logger.warn("failed merge rolled back to ${preMergeHead.name.take(7)}; removed $removed checkout temp file(s)")
    }

    /** 两个提交之间有改动的文件所在的目录（相对工作区，根目录为 ""）。 */
    private fun changedDirs(a: ObjectId, b: ObjectId): Set<String> = RevWalk(repository).use { walk ->
        TreeWalk(repository).use { tw ->
            tw.isRecursive = true
            tw.filter = TreeFilter.ANY_DIFF
            tw.addTree(walk.parseCommit(a).tree)
            tw.addTree(walk.parseCommit(b).tree)
            val dirs = HashSet<String>()
            while (tw.next()) dirs += tw.pathString.substringBeforeLast('/', "")
            dirs
        }
    }

    /** 未出生分支上直接采用远端提交（等价于首次检出）。仅在本地没有任何提交时使用。 */
    fun resetHardTo(id: ObjectId) {
        git.reset().setMode(ResetCommand.ResetType.HARD).setRef(id.name).call()
    }

    /**
     * 检出（克隆、合并、reset）之后重写一次 index，去掉"可能不干净"标记（L-037）。
     * JGit 检出前就锁了 index，锁文件的时间早于检出的文件，同一秒内写出的文件在 index 里都被标成 smudged（长度记 0），
     * 之后每次 `status` 都要把它们整个读一遍算哈希，而 `status` 不回写 index，大文件因此每次都慢。
     * 这里先等到时钟走过这些文件最晚修改时间所在的那一秒，再锁 index 写回：JGit 写入时核对一次内容并去掉标记，
     * 新 index 的时间也晚于这些文件，不会再被标上。只在有 smudged 条目时做；返回去掉标记的条目数。
     * 失败只影响之后的速度，不影响正确性：记一条 warning，返回 0，不往外抛。
     */
    fun refreshIndex(monitor: ProgressMonitor = NullProgressMonitor.INSTANCE): Int = try {
        refreshIndexOrThrow(monitor)
    } catch (e: Exception) {
        logger.warn("index refresh skipped: $e", e)
        0
    }

    private fun refreshIndexOrThrow(monitor: ProgressMonitor): Int {
        val before = DirCache.read(repository).let { dc -> (0 until dc.entryCount).map(dc::getEntry).filter { it.isSmudged } }
        if (before.isEmpty()) return 0
        val newest = before.maxOf { File(workTree, it.pathString).lastModified() }
        val waitMs = (newest / 1000 + 1) * 1000 - System.currentTimeMillis()
        if (waitMs > 0) Thread.sleep(waitMs)
        monitor.beginTask(TASK_VERIFYING, before.size)
        val dc = repository.lockDirCache()
        try {
            // 自己核对内容再写回长度与时间：只靠 DirCache.write 里 JGit 的核对，手机上实测（vivo）标记没有去掉
            for (i in 0 until dc.entryCount) {
                val e = dc.getEntry(i)
                if (!e.isSmudged) continue
                if (e.fileMode == FileMode.REGULAR_FILE || e.fileMode == FileMode.EXECUTABLE_FILE) unsmudgeIfSame(e)
                monitor.update(1)
            }
            dc.write()
            dc.commit()
        } finally {
            dc.unlock()
        }
        monitor.endTask()
        val left = DirCache.read(repository).let { d -> (0 until d.entryCount).map(d::getEntry).filter { it.isSmudged } }
        logger.info("index refreshed: ${before.size - left.size} of ${before.size} smudged entr(ies) verified")
        if (left.isNotEmpty()) {
            val idx = File(gitDir, "index")
            logger.warn(
                "still smudged: " + left.take(5).joinToString { e ->
                    val f = File(workTree, e.pathString)
                    "${e.pathString} (entry mtime ${e.lastModifiedInstant}, file ${repository.fs.lastModifiedInstant(f)}, ${f.length()} B)"
                } + "; index mtime ${repository.fs.lastModifiedInstant(idx)}",
            )
        }
        return before.size - left.size
    }

    /** 文件内容的哈希等于 index 里记的 id 时，把文件的真实长度和修改时间写回 [e]（去掉 smudged 标记）。哈希期间文件变了就不动。 */
    private fun unsmudgeIfSame(e: DirCacheEntry) {
        val f = File(workTree, e.pathString)
        if (!f.isFile) return
        val fs = repository.fs
        val mtime = fs.lastModifiedInstant(f)
        val length = f.length()
        val id = ObjectInserter.Formatter().use { fmt -> f.inputStream().use { fmt.idFor(Constants.OBJ_BLOB, length, it) } }
        if (id != e.objectId || fs.lastModifiedInstant(f) != mtime || f.length() != length) return
        e.setLength(length)
        e.setLastModified(mtime)
    }

    fun hasIndexLock(): Boolean = File(gitDir, INDEX_LOCK).exists()

    fun removeIndexLock(): Boolean = File(gitDir, INDEX_LOCK).delete()

    override fun close() {
        git.close()
        if (ownsRepository) repository.close()
    }

    companion object {
        const val DEFAULT_TIMEOUT_SEC = 60
        private const val INDEX_LOCK = "index.lock"

        /** [refreshIndex] 的进度任务名（界面翻译见 app 的 Texts.task）。 */
        const val TASK_VERIFYING = "Verifying files"

        /** [commitAll] 读要暂存的文件时的进度任务名（单位 KiB）。 */
        const val TASK_STAGING = "Reading changed files"

        /** JGit 检出时的临时文件名：`File.createTempFile("._" + 文件名, null, 目录)`（`dircache/Checkout`）。 */
        private val CHECKOUT_TEMP = Regex("""^\._.+\d+\.tmp$""")

        /** 单文件超过此大小时提交仍然进行，但记一条 warning（docs/SYNC_ENGINE.md §5）。 */
        const val LARGE_FILE_WARN_BYTES: Long = 25L * 1024 * 1024

        val FETCH_ALL_HEADS: RefSpec = RefSpec("+${Constants.R_HEADS}*:${Constants.R_REMOTES}${Constants.DEFAULT_REMOTE_NAME}/*")

        fun remoteTrackingRef(branch: String): String = "${Constants.R_REMOTES}${Constants.DEFAULT_REMOTE_NAME}/$branch"

        /** 打开 [workTree] 下已有的仓库（`workTree/.git` 必须存在）。 */
        fun open(workTree: File, logger: SyncLogger): VaultRepository {
            val gitDir = File(workTree, Constants.DOT_GIT)
            if (!gitDir.isDirectory) {
                throw SyncException(SyncStage.PRECHECK, "not a git repository: ${workTree.absolutePath}", kind = FailureKind.NOT_A_REPOSITORY)
            }
            val repo = FileRepositoryBuilder()
                .setWorkTree(workTree)
                .setGitDir(gitDir)
                .setMustExist(true)
                .build()
            return VaultRepository(workTree, Git(repo), logger, ownsRepository = true)
        }

        /** 在已有目录 [workTree] 上初始化新仓库，初始分支为 [branch]。目录必须存在且不含 `.git`。 */
        fun init(workTree: File, branch: String, logger: SyncLogger): VaultRepository {
            if (!workTree.isDirectory) {
                throw SyncException(SyncStage.PRECHECK, "directory does not exist: ${workTree.absolutePath}", kind = FailureKind.VAULT_MISSING)
            }
            if (File(workTree, Constants.DOT_GIT).exists()) {
                throw SyncException(
                    SyncStage.PRECHECK,
                    "already a git repository: ${workTree.absolutePath}",
                    kind = FailureKind.ALREADY_A_REPOSITORY,
                )
            }
            val git = Git.init()
                .setDirectory(workTree)
                .setInitialBranch(branch)
                .call()
            logger.info("initialized repository at ${workTree.absolutePath} (branch $branch)")
            return VaultRepository(workTree, git, logger, ownsRepository = false)
        }

        /**
         * 克隆 [url] 到 [workTree]（不存在或为空目录）并检出 [branch]。
         * 远端为空时 JGit 只初始化仓库，这里把 HEAD 指向 [branch]（未出生），首次同步会推送初始提交。
         */
        fun clone(
            url: String,
            workTree: File,
            branch: String,
            auth: TransportAuth,
            logger: SyncLogger,
            monitor: ProgressMonitor = NullProgressMonitor.INSTANCE,
            timeoutSec: Int = DEFAULT_TIMEOUT_SEC,
        ): VaultRepository {
            val cmd = Git.cloneRepository()
                .setURI(url)
                .setDirectory(workTree)
                .setBranch(branch)
                .setCloneAllBranches(false)
                .setTimeout(timeoutSec)
                .setProgressMonitor(monitor)
            auth.applyTo(cmd)
            val git = cmd.call()
            val repo = VaultRepository(workTree, git, logger, ownsRepository = false)
            if (repo.headId() == null) {
                repo.repository.updateRef(Constants.HEAD).link(Constants.R_HEADS + branch)
                logger.info("remote is empty; HEAD linked to unborn branch $branch")
            }
            logger.info("cloned into ${workTree.absolutePath}")
            return repo
        }

        /** 列出远端的分支（`refs/heads/` 下的引用 → id）。用于接入前检查远端是否为空、分支是否存在。 */
        fun lsRemoteHeads(
            url: String,
            auth: TransportAuth,
            timeoutSec: Int = DEFAULT_TIMEOUT_SEC,
        ): Map<String, ObjectId> {
            val cmd = Git.lsRemoteRepository().setRemote(url).setHeads(true).setTimeout(timeoutSec)
            auth.applyTo(cmd)
            return cmd.callAsMap().mapValues { it.value.objectId }
        }

        /** [dir] 是否是一个可打开的仓库（有 `.git/HEAD`）。 */
        fun isRepository(dir: File): Boolean = File(dir, "${Constants.DOT_GIT}/${Constants.HEAD}").isFile
    }
}
