package com.mountriv.vaultsync.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.mountriv.vaultsync.AppGraph
import com.mountriv.vaultsync.MainActivity
import com.mountriv.vaultsync.R
import com.mountriv.vaultsync.platform.AppLocale
import com.mountriv.vaultsync.ui.Texts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 手动同步期间的前台服务（docs/ANDROID_PLATFORM.md §3、D-009、D-024、D-028）。
 * 只负责：前台化 + 通知 + 唤醒锁 + 在 IO 协程里调用 [SyncManager.drain]；同步逻辑不在这里。
 *
 * 通知：进行中只有一条进度通知（当前库、第几个/共几个、排队的库），内容始终从 [SyncManager.progress] 取，新入队不会打乱它；
 * 新一批开始时先清掉上一批的结果通知，整批跑完后只发一条结果汇总（应用在前台时也照常发）。
 * 全部成功走"同步完成"渠道（静默），有冲突或失败走"需要处理"渠道。
 * 队列空了就 `stopForeground` + `stopSelf`。`START_NOT_STICKY`：被杀后不自动重启。
 */
class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var running = false

    // 进度通知节流：JGit 的进度回调很密，通知系统对单个应用有频率限制
    @Volatile
    private var lastNotifyAt = 0L

    @Volatile
    private var lastNotifyKey: String? = null

    // 通知文字按应用内的语言设置（D-030）；服务在一批任务期间存活，切换语言后从下一批开始生效
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase, AppGraph.language))
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = AppGraph.syncManager
        when (intent?.action) {
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_REPO_ID)?.let(manager::cancel)
            ACTION_CANCEL_ALL -> manager.cancelAll()
        }
        // 点了取消就立刻把通知换成"正在取消…"，不等下一次进度回调
        if (intent?.action == ACTION_CANCEL || intent?.action == ACTION_CANCEL_ALL) lastNotifyKey = null
        startForeground(NOTIFICATION_ID, buildProgress(this, manager.progress()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (!running && !manager.hasPendingWork) {
            // 没活可干（例如取消动作在队列已空后才到达）：立刻退出，避免前台通知一直挂着
            finish()
            return START_NOT_STICKY
        }
        if (!running) {
            // 服务从空闲开始跑 = 新的一批：上一批的结果通知立即收起（同一批里续跑不经过这里）
            getSystemService(NotificationManager::class.java).cancel(RESULT_NOTIFICATION_ID)
            launchDrain()
        }
        return START_NOT_STICKY
    }

    /**
     * 在 IO 协程里排空队列。结束后（主线程）若又有新任务入队就再跑一轮，否则发结果汇总并退出服务。
     * [running] 只在主线程读写：onStartCommand 与这里的 finally 都在主线程，避免"刚排空就来新任务"的漏跑。
     */
    private fun launchDrain() {
        val manager = AppGraph.syncManager
        running = true
        acquireWakeLock()
        scope.launch {
            // 每秒给当前任务的流量与用时取样，并强制刷新一次通知：JGit 的进度回调可能很久不来（大文件），
            // 节流时被跳过的最后一次更新也由这里补上，通知与界面最多差一秒（D-034）
            val ticker = launch {
                while (true) {
                    delay(TICK_MS)
                    if (manager.tick()) notifyProgress(force = true)
                }
            }
            try {
                manager.drain { notifyProgress() }
            } finally {
                ticker.cancel()
                withContext(Dispatchers.Main) {
                    if (manager.hasPendingWork) launchDrain() else finish()
                }
            }
        }
    }

    private fun notifyProgress(force: Boolean = false) {
        val p = AppGraph.syncManager.progress()
        val key = "${p.repoId}|${p.type}|${p.stage}|${p.task}|${p.batchSize}"
        val now = SystemClock.elapsedRealtime()
        if (!force && key == lastNotifyKey && now - lastNotifyAt < PROGRESS_INTERVAL_MS) return
        lastNotifyKey = key
        lastNotifyAt = now
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildProgress(this, p))
    }

    private fun finish() {
        running = false
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        postSummary(AppGraph.syncManager.takeBatch())
        stopSelf()
    }

    /**
     * 一批任务的结果汇总（D-028）。批次里只有成功、冲突、失败的任务（取消的没有记入）；为空就不发。
     * 一个任务：标题是库名，正文是结果。多个任务：逐行"库名：结果"，标题按任务计数——全是同步时说"N 个库"，混有其它任务时说"N 项"。
     */
    private fun postSummary(results: List<SyncManager.JobResult>) {
        if (results.isEmpty()) return
        val nm = getSystemService(NotificationManager::class.java)
        val problems = results.filter {
            it.outcome == SyncManager.JobResult.Outcome.FAILED || it.outcome == SyncManager.JobResult.Outcome.CONFLICTS
        }
        val successes = results.size - problems.size
        val channel = if (problems.isNotEmpty()) CHANNEL_ATTENTION_ID else CHANNEL_DONE_ID
        val builder = Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setAutoCancel(true)
            .setShowWhen(true)
        if (results.size == 1) {
            val r = results.single()
            val text = Texts.jobResult(this, r)
            builder.setContentTitle(r.repoName).setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
        } else {
            val allSync = results.all { it.type == JobType.SYNC }
            val title = when {
                problems.isEmpty() -> resources.getQuantityString(if (allSync) R.plurals.notif_summary_all_ok else R.plurals.notif_summary_tasks_all_ok, successes, successes)
                successes == 0 -> resources.getQuantityString(
                    if (allSync) R.plurals.notif_summary_all_problems else R.plurals.notif_summary_tasks_all_problems,
                    problems.size,
                    problems.size,
                )
                // 两个数字的标题按"需要处理"的个数选单复数（英文 "1 needs" / "2 need"）
                else -> resources.getQuantityString(
                    if (allSync) R.plurals.notif_summary_mixed else R.plurals.notif_summary_tasks_mixed,
                    problems.size,
                    successes,
                    problems.size,
                )
            }
            val style = Notification.InboxStyle().setBigContentTitle(title)
            results.forEach { style.addLine(getString(R.string.notif_summary_line, it.repoName, Texts.jobResult(this, it))) }
            builder.setContentTitle(title)
                .setContentText(Texts.joinList(this, results.map { it.repoName }.distinct()))
                .setStyle(style)
                .setNumber(results.size)
        }
        // 点击：只有一个库需要处理（或只有一个任务）时直接打开它的详情；否则只把应用切到前台，停在原来的页面
        val target = (problems.map { it.repoId }.distinct().singleOrNull() ?: results.singleOrNull()?.repoId)
            ?.takeIf { id -> AppGraph.repos.get(id) != null }
        builder.setContentIntent(openAppIntent(this, target))
        nm.notify(RESULT_NOTIFICATION_ID, builder.build())
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VaultSync:sync").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL_ID = "sync"
        const val CHANNEL_DONE_ID = "sync_done"

        /** 沿用 M2 的"同步结果"渠道 id，用户已有的渠道设置（声音、是否显示）继续有效。 */
        const val CHANNEL_ATTENTION_ID = "sync_result"
        const val NOTIFICATION_ID = 1001
        const val RESULT_NOTIFICATION_ID = 1002
        const val EXTRA_REPO_ID = "repoId"
        const val ACTION_RUN = "com.mountriv.vaultsync.action.RUN"
        const val ACTION_CANCEL = "com.mountriv.vaultsync.action.CANCEL"
        const val ACTION_CANCEL_ALL = "com.mountriv.vaultsync.action.CANCEL_ALL"
        private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60_000L
        private const val PROGRESS_INTERVAL_MS = 500L
        private const val TICK_MS = 1_000L

        /** 让服务开始（或继续）处理 [SyncManager] 的队列。 */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java).setAction(ACTION_RUN))
        }

        /** 创建（或更新名称与说明）通知渠道。重要性创建后由用户在系统设置里管理，这里不改。 */
        fun ensureChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel_sync), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = context.getString(R.string.notification_channel_sync_desc) },
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE_ID, context.getString(R.string.notification_channel_done), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = context.getString(R.string.notification_channel_done_desc) },
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ATTENTION_ID, context.getString(R.string.notification_channel_attention), NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = context.getString(R.string.notification_channel_attention_desc) },
            )
        }

        fun buildProgress(context: Context, p: SyncManager.Progress): Notification {
            val name = p.repoName
            val title = if (name == null) {
                context.getString(R.string.notif_progress_preparing)
            } else {
                context.getString(R.string.notif_progress_title, Texts.jobVerb(context, p.type), name)
            }
            // 收起时也看得出在干活：正文带上流量（大文件下载时百分比会停很久），用时由系统在标题栏走表
            val line = if (p.cancelling) {
                context.getString(R.string.progress_cancelling)
            } else {
                listOfNotNull(
                    Texts.progressLine(context, p.type, p.stage, p.task, p.completed, p.total),
                    p.meter?.let { Texts.transferText(context, p.type, p.stage, it) },
                ).joinToString(" · ")
            }
            // 小图标转圈：不下拉通知栏也看得出还在干活；结果通知用静态的 ic_stat_sync。资源 id 不变，每秒刷新时状态栏不会重载图标，动画不断
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_sync_anim)
                .setContentTitle(title)
                .setContentText(line)
                .setContentIntent(openAppIntent(context, p.repoId?.takeIf { p.type == JobType.SYNC || p.type == JobType.MAINTAIN || p.type == JobType.RECLONE }))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            if (p.meter != null) {
                builder.setShowWhen(true).setWhen(p.meter.startedAt).setUsesChronometer(true)
            } else {
                builder.setShowWhen(false)
            }
            if (p.batchSize > 1) builder.setSubText(context.getString(R.string.notif_progress_position, p.position, p.batchSize))
            if (p.queuedNames.isNotEmpty()) {
                builder.setStyle(
                    Notification.BigTextStyle().bigText(line + "\n" + context.getString(R.string.notif_progress_queued, Texts.joinList(context, p.queuedNames))),
                )
            }
            if (p.total > 0) builder.setProgress(p.total, p.completed.coerceIn(0, p.total), false) else builder.setProgress(0, 0, true)
            p.repoId?.takeIf { !p.cancelling }?.let { id ->
                val cancel = PendingIntent.getService(
                    context,
                    REQUEST_CANCEL,
                    Intent(context, SyncService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_REPO_ID, id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                builder.addAction(Notification.Action.Builder(null, context.getString(R.string.notif_action_cancel), cancel).build())
            }
            if (p.queuedNames.isNotEmpty()) {
                val cancelAll = PendingIntent.getService(
                    context,
                    REQUEST_CANCEL_ALL,
                    Intent(context, SyncService::class.java).setAction(ACTION_CANCEL_ALL),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                builder.addAction(Notification.Action.Builder(null, context.getString(R.string.notif_action_cancel_all), cancelAll).build())
            }
            return builder.build()
        }

        /** 打开应用；[repoId] 非空时进入该库详情（前进导航，不弹出当前页面），为空时只把应用切到前台、停在原来的页面。 */
        private fun openAppIntent(context: Context, repoId: String?): PendingIntent {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (repoId != null) intent.putExtra(MainActivity.EXTRA_OPEN_REPO_ID, repoId)
            return PendingIntent.getActivity(
                context,
                repoId?.hashCode() ?: 0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private const val REQUEST_CANCEL = 1
        private const val REQUEST_CANCEL_ALL = 2
    }
}
