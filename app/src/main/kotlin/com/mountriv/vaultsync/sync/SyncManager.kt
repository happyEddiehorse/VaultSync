package com.mountriv.vaultsync.sync

import android.os.Build
import com.mountriv.vaultsync.BuildConfig
import com.mountriv.vaultsync.data.AppSettings
import com.mountriv.vaultsync.data.RepoStatus
import com.mountriv.vaultsync.data.RepoStore
import com.mountriv.vaultsync.data.SettingsStore
import com.mountriv.vaultsync.data.StatusStore
import com.mountriv.vaultsync.gitcore.auth.CredentialsFactory
import com.mountriv.vaultsync.gitcore.auth.KnownHostEntry
import com.mountriv.vaultsync.gitcore.auth.RemoteUrl
import com.mountriv.vaultsync.gitcore.auth.forgetKnownHost
import com.mountriv.vaultsync.gitcore.auth.knownHostEntries
import com.mountriv.vaultsync.gitcore.log.SyncLogger
import com.mountriv.vaultsync.gitcore.merge.ConflictFile
import com.mountriv.vaultsync.gitcore.merge.MergeModel
import com.mountriv.vaultsync.gitcore.merge.Side
import com.mountriv.vaultsync.gitcore.model.AuthSpec
import com.mountriv.vaultsync.gitcore.model.FailureClassifier
import com.mountriv.vaultsync.gitcore.model.FailureKind
import com.mountriv.vaultsync.gitcore.model.GitIdentity
import com.mountriv.vaultsync.gitcore.model.ProgressSink
import com.mountriv.vaultsync.gitcore.model.RepoSpec
import com.mountriv.vaultsync.gitcore.model.SyncOutcome
import com.mountriv.vaultsync.gitcore.model.SyncStage
import com.mountriv.vaultsync.gitcore.model.SyncSummary
import com.mountriv.vaultsync.gitcore.net.Aborter
import com.mountriv.vaultsync.gitcore.net.ProxyConfig
import com.mountriv.vaultsync.gitcore.repo.GitIgnoreTemplate
import com.mountriv.vaultsync.gitcore.repo.RepoHistory
import com.mountriv.vaultsync.gitcore.repo.RepoInspector
import com.mountriv.vaultsync.gitcore.repo.RepoMaintenance
import com.mountriv.vaultsync.gitcore.repo.VaultRepository
import com.mountriv.vaultsync.gitcore.sync.JGitProgress
import com.mountriv.vaultsync.gitcore.sync.RepoOnboarding
import com.mountriv.vaultsync.gitcore.sync.SyncEngine
import com.mountriv.vaultsync.platform.KeystoreSecretStore
import com.mountriv.vaultsync.platform.LogStore
import com.mountriv.vaultsync.platform.SshKeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.eclipse.jgit.lib.RepositoryState
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** 队列里的任务种类（通知与结果汇总用）。[RECLONE]：库文件夹不在了，按已有的配置重新克隆到原位置（D-039）。 */
enum class JobType { SYNC, CLONE, INIT, ADOPT, MAINTAIN, RECLONE }

/** 一个仓库在本进程内的状态（docs/ANDROID_PLATFORM.md §4）。 */
sealed interface RepoSyncState {
    data object Idle : RepoSyncState

    data class Queued(val type: JobType) : RepoSyncState

    data class Running(
        val type: JobType,
        val stage: SyncStage?,
        val task: String?,
        val completed: Int,
        val total: Int,
        val meter: Meter = Meter.ZERO,
    ) : RepoSyncState

    data class Conflicts(val files: List<String>, val at: Long) : RepoSyncState

    data class Error(val stage: SyncStage, val message: String, val kind: FailureKind, val at: Long) : RepoSyncState

    data class Done(val summary: SyncSummary, val at: Long) : RepoSyncState

    data class Cancelled(val at: Long) : RepoSyncState

    val isBusy: Boolean get() = this is Queued || this is Running
}

/** 接入向导（克隆 / 初始化 / 接管）的进度与结果。 */
sealed interface OnboardState {
    data object Idle : OnboardState

    data class Running(val task: String?, val completed: Int, val total: Int, val meter: Meter = Meter.ZERO) : OnboardState

    data class Success(val spec: RepoSpec, val notes: List<RepoOnboarding.Note>) : OnboardState

    data class Failed(val stage: SyncStage, val message: String, val kind: FailureKind) : OnboardState

    data object Cancelled : OnboardState
}

/** 最近一次仓库维护的结果（仓库设置页展示）。 */
sealed interface MaintenanceResult {
    data class Done(val result: RepoMaintenance.Result, val at: Long) : MaintenanceResult

    data class Failed(val message: String, val kind: FailureKind, val at: Long) : MaintenanceResult
}

enum class OnboardMode { CLONE, INIT, ADOPT }

/**
 * 进程内单例：持有队列与状态，真正执行在 [SyncService] 的协程里（调用 [drain]）。
 * 同步逻辑全部在 gitcore；这里只做：取秘密、代理、日志文件、结果落盘、状态流、给通知用的批次汇总。
 */
class SyncManager(
    private val repos: RepoStore,
    private val settings: SettingsStore,
    private val statuses: StatusStore,
    private val secrets: KeystoreSecretStore,
    private val sshKeys: SshKeyStore,
    private val logs: LogStore,
    private val appLogger: SyncLogger,
) {
    /** 待执行的任务。 */
    sealed class Job {
        abstract val repoId: String
        abstract val type: JobType

        data class Sync(override val repoId: String) : Job() {
            override val type get() = JobType.SYNC
        }

        data class Onboard(val mode: OnboardMode, val spec: RepoSpec) : Job() {
            override val repoId get() = spec.id
            override val type
                get() = when (mode) {
                    OnboardMode.CLONE -> JobType.CLONE
                    OnboardMode.INIT -> JobType.INIT
                    OnboardMode.ADOPT -> JobType.ADOPT
                }
        }

        data class Maintain(override val repoId: String) : Job() {
            override val type get() = JobType.MAINTAIN
        }

        data class Reclone(override val repoId: String) : Job() {
            override val type get() = JobType.RECLONE
        }
    }

    /**
     * 一个任务结束后的结果，攒成一批给结果通知用（[takeBatch]）。
     * 用户取消的任务不记入（D-028：结果汇总不列出、不计数；进度通知的"第几个，共几个"也随之不算它）。
     */
    data class JobResult(
        val repoId: String,
        val repoName: String,
        val type: JobType,
        val outcome: Outcome,
        val conflictCount: Int = 0,
        val failureKind: FailureKind? = null,
        val stage: SyncStage? = null,
        /** 维护释放的字节数（可能为负：新 pack 比松散对象总和略大时）。 */
        val freedBytes: Long = 0,
        /** 同步成功时的摘要（提交了几个文件、合并与推送结果），通知据此写"做了什么"。 */
        val summary: SyncSummary? = null,
    ) {
        enum class Outcome { SUCCESS, CONFLICTS, FAILED }
    }

    /** 进度通知要显示的全部信息。[position] 从 1 起，是当前任务在本批里的序号。 */
    data class Progress(
        val repoId: String?,
        val repoName: String?,
        val type: JobType?,
        val stage: SyncStage?,
        val task: String?,
        val completed: Int,
        val total: Int,
        val position: Int,
        val batchSize: Int,
        val queuedNames: List<String>,
        val meter: Meter?,
        /** 当前任务已点了取消、还没停下来。 */
        val cancelling: Boolean,
    )

    private val engine = SyncEngine(deviceName = deviceName())

    private val _states = MutableStateFlow<Map<String, RepoSyncState>>(emptyMap())
    val states: StateFlow<Map<String, RepoSyncState>> = _states.asStateFlow()

    private val _onboard = MutableStateFlow<OnboardState>(OnboardState.Idle)
    val onboard: StateFlow<OnboardState> = _onboard.asStateFlow()

    /** 已点了取消、还没停下来的库（界面与通知据此显示"正在取消…"，D-035）。 */
    private val _cancelling = MutableStateFlow<Set<String>>(emptySet())
    val cancelling: StateFlow<Set<String>> = _cancelling.asStateFlow()

    private val _maintenance = MutableStateFlow<Map<String, MaintenanceResult>>(emptyMap())
    val maintenance: StateFlow<Map<String, MaintenanceResult>> = _maintenance.asStateFlow()

    private val queue = ArrayDeque<Job>()
    private val cancelled = HashSet<String>()
    private val draining = AtomicBoolean(false)
    private val batch = ArrayList<JobResult>()

    /** 当前正在执行的任务。 */
    @Volatile
    var currentJob: Job? = null
        private set

    /** 当前任务的进度（通知用）。 */
    @Volatile
    private var currentProgress = Triple<SyncStage?, String?, Pair<Int, Int>>(null, null, 0 to 0)

    /** 当前任务的流量与用时；[tick] 每秒取一次样（D-034）。 */
    @Volatile
    private var meter: TrafficMeter? = null

    @Volatile
    private var lastMeter = Meter.ZERO

    /**
     * 当前任务打开的网络连接；取消时全部断开，正在下载 / 上传的大文件立即停下（D-035）。
     * 本地步骤（提交、合并、检出、整理）不受影响，靠 JGit 与 [VaultRepository.commitAll] 自己的取消检查。
     */
    @Volatile
    private var aborter: Aborter? = null

    fun stateOf(repoId: String): RepoSyncState = _states.value[repoId] ?: RepoSyncState.Idle

    /** 入队。返回 false 表示该仓库已在队列或运行中。调用方随后启动 [SyncService]。 */
    @Synchronized
    fun enqueueSync(repoId: String): Boolean = enqueueRepoJob(Job.Sync(repoId))

    /** 仓库维护入队（与同步共用队列，互斥）。 */
    @Synchronized
    fun enqueueMaintain(repoId: String): Boolean = enqueueRepoJob(Job.Maintain(repoId))

    /** 重新克隆入队（库详情页，文件夹不存在或为空时，D-039）。 */
    @Synchronized
    fun enqueueReclone(repoId: String): Boolean = enqueueRepoJob(Job.Reclone(repoId))

    private fun enqueueRepoJob(job: Job): Boolean {
        if (stateOf(job.repoId).isBusy) return false
        if (queue.any { it.repoId == job.repoId }) return false
        queue.addLast(job)
        cancelled.remove(job.repoId)
        setState(job.repoId, RepoSyncState.Queued(job.type))
        return true
    }

    @Synchronized
    fun enqueueOnboard(mode: OnboardMode, spec: RepoSpec): Boolean {
        if (_onboard.value is OnboardState.Running) return false
        if (queue.any { it is Job.Onboard }) return false
        queue.addLast(Job.Onboard(mode, spec))
        cancelled.remove(spec.id)
        _onboard.value = OnboardState.Running(null, 0, 0)
        return true
    }

    fun resetOnboard() {
        _onboard.value = OnboardState.Idle
    }

    /** 请求取消：排队中的直接移除；运行中的通过 cancel 回调让 JGit 中止。 */
    @Synchronized
    fun cancel(repoId: String) {
        val removed = queue.filter { it.repoId == repoId }
        queue.removeAll { it.repoId == repoId }
        cancelled.add(repoId)
        removed.forEach(::markRemovedFromQueue)
        if (currentJob?.repoId == repoId) stopCurrent()
    }

    /** 当前任务：记为"正在取消"，并断开它的网络连接。调用方持有 `this`。 */
    private fun stopCurrent() {
        val job = currentJob ?: return
        _cancelling.update { it + job.repoId }
        aborter?.abort()
    }

    /** 停止全部：清空队列并取消当前任务。 */
    @Synchronized
    fun cancelAll() {
        val removed = queue.toList()
        queue.clear()
        removed.forEach { cancelled.add(it.repoId); markRemovedFromQueue(it) }
        currentJob?.let { cancelled.add(it.repoId) }
        stopCurrent()
    }

    /** 排队中被取消：只更新状态，不记入本批结果（见 [JobResult]）。 */
    private fun markRemovedFromQueue(job: Job) {
        val now = System.currentTimeMillis()
        when (job) {
            is Job.Onboard -> _onboard.value = OnboardState.Cancelled
            is Job.Sync, is Job.Maintain, is Job.Reclone -> {
                if (stateOf(job.repoId) is RepoSyncState.Queued) {
                    if (job is Job.Sync) setState(job.repoId, RepoSyncState.Cancelled(now)) else clearState(job.repoId)
                }
            }
        }
    }

    val hasPendingWork: Boolean
        @Synchronized get() = queue.isNotEmpty() || currentJob != null

    /** 本批次到目前为止的结果，并清空（服务在队列跑完、准备退出时调用）。 */
    @Synchronized
    fun takeBatch(): List<JobResult> = ArrayList(batch).also { batch.clear() }

    /** 进度通知的快照。 */
    @Synchronized
    fun progress(): Progress {
        val job = currentJob ?: queue.firstOrNull()
        val (stage, task, counts) = currentProgress
        val running = currentJob != null
        return Progress(
            repoId = job?.repoId,
            repoName = job?.let(::nameOf),
            type = job?.type,
            stage = if (running) stage else null,
            task = if (running) task else null,
            completed = if (running) counts.first else 0,
            total = if (running) counts.second else 0,
            position = batch.size + 1,
            batchSize = batch.size + queue.size + (if (running) 1 else 0),
            queuedNames = queue.filter { it !== job }.map(::nameOf),
            meter = if (running) lastMeter else null,
            cancelling = running && job?.repoId in _cancelling.value,
        )
    }

    /**
     * 串行执行队列直到为空。由服务在 IO 线程调用；同一时刻只有一个 drain 在跑。
     * [onChange] 在进度或当前任务变化时调用（服务据此刷新通知，自行节流）。
     */
    fun drain(onChange: () -> Unit) {
        if (!draining.compareAndSet(false, true)) return
        try {
            while (true) {
                val job = synchronized(this) {
                    queue.removeFirstOrNull()?.also {
                        currentJob = it
                        currentProgress = Triple(null, null, 0 to 0)
                        meter = TrafficMeter().also { m -> lastMeter = m.sample() }
                        aborter = Aborter()
                    }
                } ?: break
                onChange()
                try {
                    when (job) {
                        is Job.Sync -> runSync(job.repoId, onChange)
                        is Job.Onboard -> runOnboard(job, onChange)
                        is Job.Maintain -> runMaintain(job.repoId, onChange)
                        is Job.Reclone -> runReclone(job.repoId, onChange)
                    }
                } catch (t: Throwable) {
                    appLogger.error("job ${job.type} ${job.repoId} crashed", t)
                    val kind = FailureClassifier.classify(t.message, t)
                    val now = System.currentTimeMillis()
                    when (job) {
                        is Job.Sync, is Job.Reclone -> setState(job.repoId, RepoSyncState.Error(SyncStage.PRECHECK, t.toString(), kind, now))
                        is Job.Onboard -> _onboard.value = OnboardState.Failed(SyncStage.PRECHECK, t.toString(), kind)
                        is Job.Maintain -> {
                            clearState(job.repoId)
                            _maintenance.update { it + (job.repoId to MaintenanceResult.Failed(t.toString(), kind, now)) }
                        }
                    }
                    record(JobResult(job.repoId, nameOf(job), job.type, JobResult.Outcome.FAILED, failureKind = kind))
                } finally {
                    synchronized(this) {
                        currentJob = null
                        meter = null
                        aborter = null
                        _cancelling.update { it - job.repoId }
                    }
                }
            }
        } finally {
            draining.set(false)
        }
    }

    /**
     * 服务每秒调用一次：给当前任务的流量与用时取样，写进它的 Running 状态（界面与通知都每秒刷新一次）。
     * 大文件下载时 JGit 的进度很久不动，靠这里让用户看到还在干活。没有任务在跑时什么也不做，返回 false。
     */
    fun tick(): Boolean {
        val job = currentJob ?: return false
        val m = meter?.sample() ?: return false
        lastMeter = m
        when (job) {
            is Job.Onboard -> _onboard.update { if (it is OnboardState.Running) it.copy(meter = m) else it }
            is Job.Sync, is Job.Maintain, is Job.Reclone -> _states.update { map ->
                val st = map[job.repoId]
                if (st is RepoSyncState.Running) map + (job.repoId to st.copy(meter = m)) else map
            }
        }
        return true
    }

    // ---- 执行 ----

    private fun progressSink(repoId: String, type: JobType, onChange: () -> Unit, reportToState: Boolean) = object : ProgressSink {
        var stage: SyncStage? = if (type == JobType.SYNC) SyncStage.PRECHECK else null
        var task: String? = null
        var total = 0

        private fun publish(completed: Int) {
            currentProgress = Triple(stage, task, completed to total)
            if (reportToState) setState(repoId, RepoSyncState.Running(type, stage, task, completed, total, lastMeter))
            onChange()
        }

        override fun onStage(stage: SyncStage) {
            this.stage = stage
            task = null
            total = 0
            publish(0)
        }

        override fun onTask(title: String, totalWork: Int) {
            task = title
            total = totalWork
            publish(0)
        }

        override fun onProgress(completed: Int) = publish(completed)
    }

    private fun runSync(repoId: String, onChange: () -> Unit) {
        val spec = repos.get(repoId)
        val now0 = System.currentTimeMillis()
        if (spec == null) {
            setState(repoId, RepoSyncState.Error(SyncStage.PRECHECK, "repository not found", FailureKind.UNKNOWN, now0))
            return
        }
        val s = settings.state.value
        val identity = identityFor(spec, s)
        if (identity == null) {
            val message = "author name/email not set (Settings)"
            setState(repoId, RepoSyncState.Error(SyncStage.PRECHECK, message, FailureKind.IDENTITY_MISSING, now0))
            safely { statuses.put(RepoStatus(repoId, now0, RepoStatus.FAILED, "[PRECHECK] $message", null, SyncStage.PRECHECK.name, FailureKind.IDENTITY_MISSING.name)) }
            record(JobResult(repoId, spec.name, JobType.SYNC, JobResult.Outcome.FAILED, failureKind = FailureKind.IDENTITY_MISSING, stage = SyncStage.PRECHECK))
            return
        }
        val session = logs.begin(repoId, header(spec, "sync"))
        applyRuntimeSettings(s)
        setState(repoId, RepoSyncState.Running(JobType.SYNC, SyncStage.PRECHECK, null, 0, 0, lastMeter))
        val sink = progressSink(repoId, JobType.SYNC, onChange, reportToState = true)
        val outcome = engine.sync(spec, secrets.provider, identity, session, sink, aborter ?: Aborter()) { isCancelled(repoId) }
        val now = System.currentTimeMillis()
        val file = session.file.name
        val (state, status, result) = when (outcome) {
            is SyncOutcome.Success -> {
                val sum = outcome.summary
                session.finish(RepoStatus.SUCCESS, summaryLine(sum))
                Triple(
                    RepoSyncState.Done(sum, now),
                    RepoStatus(
                        repoId, now, RepoStatus.SUCCESS, summaryLine(sum), file,
                        committed = sum.localFilesCommitted, mergeStatus = sum.mergeStatus, pushStatus = sum.pushStatus, durationMs = sum.durationMs,
                    ),
                    JobResult(repoId, spec.name, JobType.SYNC, JobResult.Outcome.SUCCESS, summary = sum),
                )
            }
            is SyncOutcome.Conflicts -> {
                session.finish(RepoStatus.CONFLICTS, "${outcome.files.size} file(s)")
                Triple(
                    RepoSyncState.Conflicts(outcome.files, now),
                    RepoStatus(
                        repoId, now, RepoStatus.CONFLICTS, "${outcome.files.size} file(s): ${outcome.files.take(5).joinToString()}", file,
                        conflictCount = outcome.files.size,
                    ),
                    JobResult(repoId, spec.name, JobType.SYNC, JobResult.Outcome.CONFLICTS, conflictCount = outcome.files.size),
                )
            }
            is SyncOutcome.Cancelled -> {
                session.finish(RepoStatus.CANCELLED, "at ${outcome.stage}")
                Triple(
                    RepoSyncState.Cancelled(now),
                    RepoStatus(repoId, now, RepoStatus.CANCELLED, "cancelled at ${outcome.stage}", file, stage = outcome.stage.name),
                    null,
                )
            }
            is SyncOutcome.Failed -> {
                session.finish(RepoStatus.FAILED, "[${outcome.stage}] ${outcome.message} (kind=${outcome.kind})")
                Triple(
                    RepoSyncState.Error(outcome.stage, outcome.message, outcome.kind, now),
                    RepoStatus(
                        repoId, now, RepoStatus.FAILED, "[${outcome.stage}] ${outcome.message}", file,
                        stage = outcome.stage.name, failureKind = outcome.kind.name,
                    ),
                    JobResult(repoId, spec.name, JobType.SYNC, JobResult.Outcome.FAILED, failureKind = outcome.kind, stage = outcome.stage),
                )
            }
        }
        setState(repoId, state)
        safely { statuses.put(status) }
        safely { logs.prune(repoId, s.logKeep) }
        result?.let(::record)
    }

    private fun runOnboard(job: Job.Onboard, onChange: () -> Unit) {
        val spec = job.spec
        val s = settings.state.value
        val session = logs.begin(spec.id, header(spec, job.mode.name.lowercase()))
        applyRuntimeSettings(s)
        _onboard.value = OnboardState.Running(null, 0, 0, lastMeter)
        val sink = object : ProgressSink {
            var task: String? = null
            var total = 0

            override fun onTask(title: String, totalWork: Int) {
                task = title
                total = totalWork
                currentProgress = Triple(null, title, 0 to totalWork)
                _onboard.value = OnboardState.Running(title, 0, totalWork, lastMeter)
                onChange()
            }

            override fun onProgress(completed: Int) {
                currentProgress = Triple(null, task, completed to total)
                _onboard.value = OnboardState.Running(task, completed, total, lastMeter)
                onChange()
            }
        }
        val cancel = { isCancelled(spec.id) }
        val result = when (job.mode) {
            OnboardMode.CLONE -> RepoOnboarding.clone(spec, secrets.provider, session, sink, cancel, aborter = aborter ?: Aborter())
            OnboardMode.INIT -> {
                val identity = identityFor(spec, s)
                if (identity == null) {
                    RepoOnboarding.Result.Failed(SyncStage.PRECHECK, "author name/email not set (Settings)", kind = FailureKind.IDENTITY_MISSING)
                } else {
                    RepoOnboarding.initAndPush(spec, secrets.provider, identity, session, sink, cancel, deviceName(), aborter = aborter ?: Aborter())
                }
            }
            OnboardMode.ADOPT -> RepoOnboarding.adopt(spec, session)
        }
        when (result) {
            is RepoOnboarding.Result.Ready -> {
                // 接管场景：仓库里已配置的 origin 是 SSH 而向导没选认证方式时，默认用默认密钥（有的话）
                val ready = result.spec.let { r ->
                    val defaultKey = if (r.auth is AuthSpec.None && CredentialsFactory.usesSsh(r.remoteUrl)) {
                        runCatching { sshKeys.refresh().default }.getOrNull()?.takeIf { !it.unreadable }
                    } else {
                        null
                    }
                    if (defaultKey != null) r.copy(auth = AuthSpec.SshKey(defaultKey.id)) else r
                }
                repos.put(ready)
                session.finish(RepoStatus.SUCCESS, "${job.mode} ok" + (if (result.notes.isNotEmpty()) "; " + result.notes.joinToString("; ") else ""))
                safely { statuses.put(RepoStatus(spec.id, System.currentTimeMillis(), RepoStatus.SUCCESS, "${job.mode.name.lowercase()} ok", session.file.name)) }
                _onboard.value = OnboardState.Success(ready, result.notes)
                record(JobResult(spec.id, spec.name, job.type, JobResult.Outcome.SUCCESS))
            }
            is RepoOnboarding.Result.Failed -> {
                // 用户点了取消就按取消处理（与同步一致）：中断线程后的异常可能被归成"超时""网络"，不再看 kind
                val wasCancelled = cancel()
                session.finish(
                    if (wasCancelled) RepoStatus.CANCELLED else RepoStatus.FAILED,
                    "[${result.stage}] ${result.message} (kind=${result.kind})",
                )
                _onboard.value = if (wasCancelled) OnboardState.Cancelled else OnboardState.Failed(result.stage, result.message, result.kind)
                if (!wasCancelled) {
                    record(JobResult(spec.id, spec.name, job.type, JobResult.Outcome.FAILED, failureKind = result.kind, stage = result.stage))
                }
            }
        }
    }

    private fun runMaintain(repoId: String, onChange: () -> Unit) {
        val spec = repos.get(repoId) ?: return clearState(repoId)
        val session = logs.begin(repoId, header(spec, "maintain"))
        setState(repoId, RepoSyncState.Running(JobType.MAINTAIN, null, null, 0, 0, lastMeter))
        val sink = progressSink(repoId, JobType.MAINTAIN, onChange, reportToState = true)
        try {
            val result = VaultRepository.open(File(spec.localPath), session).use { repo ->
                RepoMaintenance.run(repo, session, JGitProgress(sink) { isCancelled(repoId) })
            }
            val now = System.currentTimeMillis()
            val freed = result.before.totalBytes - result.after.totalBytes
            session.finish(RepoStatus.SUCCESS, "maintenance freed $freed bytes in ${result.durationMs} ms")
            _maintenance.update { it + (repoId to MaintenanceResult.Done(result, now)) }
            record(JobResult(repoId, spec.name, JobType.MAINTAIN, JobResult.Outcome.SUCCESS, freedBytes = freed))
        } catch (e: Exception) {
            val now = System.currentTimeMillis()
            val kind = FailureClassifier.classify(e.message, e)
            val cancelledNow = isCancelled(repoId)
            session.error("maintenance failed", e)
            session.finish(if (cancelledNow) RepoStatus.CANCELLED else RepoStatus.FAILED, "maintenance: ${e.message}")
            if (!cancelledNow) {
                _maintenance.update { it + (repoId to MaintenanceResult.Failed(e.message ?: e.toString(), kind, now)) }
                record(JobResult(repoId, spec.name, JobType.MAINTAIN, JobResult.Outcome.FAILED, failureKind = kind))
            }
        } finally {
            clearState(repoId)
            safely { logs.prune(repoId, settings.state.value.logKeep) }
        }
    }

    /**
     * 重新克隆到原位置（D-039）：库的 id、名称、远端、分支、认证都不变，调用与添加向导相同的 [RepoOnboarding.clone]
     * （文件夹必须不存在或为空，失败时删掉自己建的文件夹）。成功后清掉上次的同步结果（那是文件夹不见之前的事）。
     */
    private fun runReclone(repoId: String, onChange: () -> Unit) {
        val spec = repos.get(repoId) ?: return clearState(repoId)
        val session = logs.begin(repoId, header(spec, "clone"))
        applyRuntimeSettings(settings.state.value)
        setState(repoId, RepoSyncState.Running(JobType.RECLONE, null, null, 0, 0, lastMeter))
        val sink = progressSink(repoId, JobType.RECLONE, onChange, reportToState = true)
        val result = RepoOnboarding.clone(spec, secrets.provider, session, sink, { isCancelled(repoId) }, aborter = aborter ?: Aborter())
        val now = System.currentTimeMillis()
        when (result) {
            is RepoOnboarding.Result.Ready -> {
                session.finish(RepoStatus.SUCCESS, "re-clone ok")
                safely { statuses.remove(repoId) }
                clearState(repoId)
                record(JobResult(repoId, spec.name, JobType.RECLONE, JobResult.Outcome.SUCCESS))
            }
            is RepoOnboarding.Result.Failed -> {
                if (isCancelled(repoId)) {
                    session.finish(RepoStatus.CANCELLED, "[${result.stage}] ${result.message}")
                    setState(repoId, RepoSyncState.Cancelled(now))
                } else {
                    session.finish(RepoStatus.FAILED, "[${result.stage}] ${result.message} (kind=${result.kind})")
                    setState(repoId, RepoSyncState.Error(result.stage, result.message, result.kind, now))
                    safely {
                        statuses.put(
                            RepoStatus(
                                repoId, now, RepoStatus.FAILED, "[${result.stage}] ${result.message}", session.file.name,
                                stage = result.stage.name, failureKind = result.kind.name,
                            ),
                        )
                    }
                    record(JobResult(repoId, spec.name, JobType.RECLONE, JobResult.Outcome.FAILED, failureKind = result.kind, stage = result.stage))
                }
            }
        }
        safely { logs.prune(repoId, settings.state.value.logKeep) }
    }

    // ---- 残留修复与移除（详情页用，短操作，调用方放 IO 线程） ----

    fun inspect(spec: RepoSpec): RepoInspector.RepoInfo = RepoInspector.inspect(File(spec.localPath))

    fun removeIndexLock(spec: RepoSpec) {
        VaultRepository.open(File(spec.localPath), appLogger).use { it.removeIndexLock() }
        appLogger.info("removed index.lock for '${spec.name}'")
    }

    /** 放弃未完成的合并：reset --hard HEAD；远端提交仍在 origin/branch。 */
    fun abortMerge(spec: RepoSpec) {
        withRepo(spec) { it.abortMerge() }
        appLogger.info("aborted merge for '${spec.name}'")
        clearState(spec.id)
    }

    /** 冲突已全部解决但未提交：完成合并提交。 */
    fun completeMerge(spec: RepoSpec) {
        val identity = identityFor(spec, settings.state.value) ?: error("author name/email not set")
        withRepo(spec) { it.completeMerge(identity) }
        appLogger.info("completed merge for '${spec.name}'")
        clearState(spec.id)
    }

    /** 从列表移除（不删文件）：清掉状态、令牌、日志。SSH 密钥属于密钥列表，可能被别的库共用，不删。 */
    fun removeRepo(spec: RepoSpec) {
        repos.remove(spec.id)
        safely { statuses.remove(spec.id) }
        safely { secrets.delete(KeystoreSecretStore.httpsTokenId(spec.id)) }
        safely { logs.deleteAll(spec.id) }
        clearState(spec.id)
        _maintenance.update { it - spec.id }
    }

    /** 保存仓库设置（名称、远端、认证、身份、提交模板）。同步进行中拒绝。 */
    fun updateRepo(spec: RepoSpec) {
        check(!stateOf(spec.id).isBusy) { "repository '${spec.name}' is busy" }
        repos.put(spec)
        appLogger.info("repository settings updated: '${spec.name}' remote=${RemoteUrl.redact(spec.remoteUrl)} auth=${authLabel(spec.auth)}")
    }

    // ---- 只读查询（历史、维护统计；不与同步互斥，JGit 支持并发读） ----

    fun history(spec: RepoSpec, skip: Int, limit: Int): RepoHistory.Page =
        readRepo(spec) { RepoHistory.recent(it, spec.branch, skip, limit) }

    fun commitDetail(spec: RepoSpec, commitId: String): RepoHistory.CommitDetail =
        readRepo(spec) { RepoHistory.detail(it, spec.branch, commitId) }

    /** 提交详情里某个文件的改动（展开时才算，D-031）。 */
    fun fileDiff(spec: RepoSpec, change: RepoHistory.FileChange): RepoHistory.FileDiff =
        readRepo(spec) { RepoHistory.fileDiff(it, change.oldBlobId, change.newBlobId) }

    fun maintenanceStats(spec: RepoSpec): RepoMaintenance.Stats = readRepo(spec) { RepoMaintenance.stats(it) }

    fun gitignoreMissing(spec: RepoSpec): List<String> = GitIgnoreTemplate.missingRules(File(spec.localPath))

    /** 追加推荐的 .gitignore 规则（写库里的文件，与同步互斥）。返回追加的规则。 */
    fun appendGitignore(spec: RepoSpec): List<String> {
        check(!stateOf(spec.id).isBusy) { "repository '${spec.name}' is busy" }
        return GitIgnoreTemplate.appendMissing(File(spec.localPath)).also {
            appLogger.info("appended ${it.size} recommended .gitignore rule(s) to '${spec.name}'")
        }
    }

    private fun <T> readRepo(spec: RepoSpec, block: (VaultRepository) -> T): T =
        VaultRepository.open(File(spec.localPath), appLogger).use(block)

    // ---- 冲突解决（冲突页用；调用方放 IO 线程；该仓库正在同步时拒绝） ----

    /** 冲突列表页的数据：仍未解决的文件、本次合并最初冲突的全部路径、仓库状态。 */
    data class ConflictList(
        val unresolved: List<MergeModel.ConflictSummary>,
        val original: List<String>,
        val state: RepositoryState?,
    ) {
        val resolved: List<String> get() = original.filter { p -> unresolved.none { it.path == p } }
    }

    fun conflictList(spec: RepoSpec): ConflictList = withRepo(spec) { repo ->
        ConflictList(MergeModel.summaries(repo), MergeModel.originalConflictPaths(repo), repo.state)
    }

    fun loadConflict(spec: RepoSpec, path: String): ConflictFile? = withRepo(spec) { MergeModel.loadConflict(it, path) }

    fun readSideText(spec: RepoSpec, path: String, side: Side): String? = withRepo(spec) { MergeModel.readSideText(it, path, side) }

    fun resolveWithText(spec: RepoSpec, path: String, text: String) = withRepo(spec) { repo ->
        MergeModel.resolveWithText(repo, path, text)
        appLogger.info("conflict resolved with edited text: '${spec.name}' $path (${text.length} chars)")
        afterResolve(spec, repo)
    }

    fun resolveTakeSide(spec: RepoSpec, path: String, side: Side) = withRepo(spec) { repo ->
        MergeModel.resolveTakeSide(repo, path, side)
        appLogger.info("conflict resolved taking $side: '${spec.name}' $path")
        afterResolve(spec, repo)
    }

    fun resolveDelete(spec: RepoSpec, path: String) = withRepo(spec) { repo ->
        MergeModel.resolveDelete(repo, path)
        appLogger.info("conflict resolved by deleting: '${spec.name}' $path")
        afterResolve(spec, repo)
    }

    /** 所有未解决的文件都采用 [side]（该方不存在的文件按删除处理）。返回处理过的路径。 */
    fun resolveAll(spec: RepoSpec, side: Side): List<String> = withRepo(spec) { repo ->
        val paths = MergeModel.resolveAll(repo, side)
        appLogger.info("all conflicts resolved taking $side: '${spec.name}' ${paths.size} file(s)")
        afterResolve(spec, repo)
        paths
    }

    private fun <T> withRepo(spec: RepoSpec, block: (VaultRepository) -> T): T {
        check(!stateOf(spec.id).isBusy) { "repository '${spec.name}' is busy" }
        return VaultRepository.open(File(spec.localPath), appLogger).use(block)
    }

    /** 解决动作之后刷新进程内状态：还有冲突 → Conflicts(剩余)；全部解决 → 回到 Idle（详情页靠 RepoInspector 显示"待完成合并"）。 */
    private fun afterResolve(spec: RepoSpec, repo: VaultRepository) {
        val remaining = MergeModel.unresolvedPaths(repo)
        _states.update { m ->
            if (remaining.isEmpty()) m - spec.id else m + (spec.id to RepoSyncState.Conflicts(remaining, System.currentTimeMillis()))
        }
    }

    // ---- 内部 ----

    private fun isCancelled(id: String): Boolean = synchronized(this) { id in cancelled }

    private fun setState(repoId: String, state: RepoSyncState) {
        _states.update { it + (repoId to state) }
    }

    private fun clearState(repoId: String) {
        _states.update { it - repoId }
    }

    @Synchronized
    private fun record(result: JobResult) {
        batch += result
    }

    private fun nameOf(job: Job): String = when (job) {
        is Job.Onboard -> job.spec.name
        else -> repos.get(job.repoId)?.name ?: job.repoId
    }

    private fun applyRuntimeSettings(s: AppSettings) {
        ProxyConfig.apply(ProxyConfig.Settings(s.proxyEnabled, s.proxyHost, s.proxyPort))
        LogStore.debugEnabled = s.debugLogs
    }

    private fun identityFor(spec: RepoSpec, s: AppSettings): GitIdentity? =
        spec.identity?.takeIf { it.name.isNotBlank() && it.email.isNotBlank() }
            ?: if (s.hasIdentity) GitIdentity(s.authorName.trim(), s.authorEmail.trim()) else null

    private fun header(spec: RepoSpec, op: String): List<String> = listOf(
        "VaultSync ${BuildConfig.VERSION_NAME} · JGit ${BuildConfig.JGIT_VERSION} · Android ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL}",
        "op=$op repo=${spec.id} name='${spec.name}' branch=${spec.branch} remote=${RemoteUrl.redact(spec.remoteUrl)} path=${spec.localPath}",
        "auth=${authLabel(spec.auth)} proxy=${settings.state.value.let { if (it.proxyEnabled) "${it.proxyHost}:${it.proxyPort}" else "off" }}",
    )

    private fun authLabel(auth: AuthSpec): String = when (auth) {
        is AuthSpec.HttpsToken -> "https-token(${auth.username})"
        is AuthSpec.SshKey -> "ssh-key(${auth.keyId})"
        AuthSpec.None -> "none"
    }

    /** 设置页用：已知主机列表与"忘记"（JSch 类型不出 gitcore）。 */
    fun knownHosts(): List<KnownHostEntry> = knownHostEntries(appLogger)

    fun forgetHost(host: String): Int = forgetKnownHost(appLogger, host)

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            appLogger.warn("non-fatal: ${t.message}", t)
        }
    }

    companion object {
        fun deviceName(): String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "android"

        /** 写进日志与 status.json 的英文摘要；界面展示用结构化字段自己翻译。 */
        fun summaryLine(s: SyncSummary): String = buildString {
            append("committed=${s.localFilesCommitted}")
            append(" merge=${s.mergeStatus ?: "NO_REMOTE_BRANCH"}")
            append(" push=${s.pushStatus}")
            if (s.retries > 0) append(" retries=${s.retries}")
            append(" ${s.durationMs} ms")
        }
    }
}
