package com.mountriv.vaultsync.gitcore.repo

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.RepositoryState
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File

/**
 * 仓库状态检测（只读，不改动任何文件）。修复动作在 [VaultRepository]（removeIndexLock / abortMerge / completeMerge），
 * 对应关系见 docs/SYNC_ENGINE.md §7。
 */
object RepoInspector {

    /** 需要处理的残留状态。 */
    enum class Residue {
        /** `.git/index.lock` 存在（上次操作被打断）。修复：删除锁文件。 */
        STALE_INDEX_LOCK,

        /** 合并进行中且仍有冲突。修复：进入冲突页，或放弃合并。 */
        MERGE_WITH_CONFLICTS,

        /** 冲突已全部解决但合并提交还没做。修复：完成合并。 */
        MERGE_RESOLVED_UNCOMMITTED,

        /** rebase / cherry-pick / revert / bisect 等本应用不支持的状态。 */
        UNSUPPORTED_STATE,

        /** HEAD 分离。 */
        DETACHED_HEAD,

        /** 没有配置 origin。 */
        MISSING_REMOTE,

        /** 分支还没有任何提交（刚初始化或克隆自空远端），仅提示。 */
        UNBORN_BRANCH,
    }

    /** 一次只读检查的结果。 */
    data class RepoInfo(
        val path: String,
        val dirExists: Boolean,
        val isRepository: Boolean,
        val branch: String? = null,
        val headId: String? = null,
        val state: RepositoryState? = null,
        val remoteUrl: String? = null,
        val hasIndexLock: Boolean = false,
        val hasMergeHead: Boolean = false,
        val modified: Int = 0,
        val untracked: Int = 0,
        val missing: Int = 0,
        val conflicting: Int = 0,
        val configFileMode: String? = null,
        val configGcAuto: String? = null,
        val residues: List<Residue> = emptyList(),
        val elapsedMs: Long = 0,
    )

    /**
     * 只读检查 [dir]。不存在或不是仓库时返回对应标记；打开失败抛异常（调用方负责展示与记录）。
     */
    fun inspect(dir: File): RepoInfo {
        val started = System.currentTimeMillis()
        if (!dir.isDirectory) return RepoInfo(dir.absolutePath, dirExists = false, isRepository = false)
        val gitDir = File(dir, Constants.DOT_GIT)
        if (!File(gitDir, Constants.HEAD).isFile) return RepoInfo(dir.absolutePath, dirExists = true, isRepository = false)

        FileRepositoryBuilder()
            .setWorkTree(dir)
            .setGitDir(gitDir)
            .setMustExist(true)
            .build()
            .use { repo ->
                Git(repo).use { git ->
                    val status = git.status().call()
                    val config = repo.config
                    val state = repo.repositoryState
                    val headId = repo.resolve(Constants.HEAD)
                    val fullBranch = repo.fullBranch
                    val remoteUrl = config.getString("remote", Constants.DEFAULT_REMOTE_NAME, "url")
                    val hasIndexLock = File(gitDir, "index.lock").exists()

                    val residues = buildList {
                        if (hasIndexLock) add(Residue.STALE_INDEX_LOCK)
                        when (state) {
                            RepositoryState.SAFE -> Unit
                            RepositoryState.MERGING -> add(Residue.MERGE_WITH_CONFLICTS)
                            RepositoryState.MERGING_RESOLVED -> add(Residue.MERGE_RESOLVED_UNCOMMITTED)
                            else -> add(Residue.UNSUPPORTED_STATE)
                        }
                        if (fullBranch == null || !fullBranch.startsWith(Constants.R_HEADS)) add(Residue.DETACHED_HEAD)
                        if (remoteUrl.isNullOrBlank()) add(Residue.MISSING_REMOTE)
                        if (headId == null && fullBranch != null && fullBranch.startsWith(Constants.R_HEADS)) add(Residue.UNBORN_BRANCH)
                    }

                    return RepoInfo(
                        path = dir.absolutePath,
                        dirExists = true,
                        isRepository = true,
                        branch = repo.branch,
                        headId = headId?.name,
                        state = state,
                        remoteUrl = remoteUrl,
                        hasIndexLock = hasIndexLock,
                        hasMergeHead = File(gitDir, Constants.MERGE_HEAD).exists(),
                        modified = status.modified.size + status.changed.size,
                        untracked = status.untracked.size,
                        missing = status.missing.size + status.removed.size,
                        conflicting = status.conflicting.size,
                        configFileMode = config.getString("core", null, "filemode"),
                        configGcAuto = config.getString("gc", null, "auto"),
                        residues = residues,
                        elapsedMs = System.currentTimeMillis() - started,
                    )
                }
            }
    }
}
