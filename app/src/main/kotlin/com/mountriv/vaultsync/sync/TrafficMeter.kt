package com.mountriv.vaultsync.sync

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock

/**
 * 一个任务的"还在干活"指标（docs/UI.md §3.1）：用时，以及本应用从任务开始起收发的字节数与最近几秒的速度。
 * JGit 的进度按对象个数算，一个大文件就是一个对象，下载它时百分比会停很久；字节数和速度每秒都在变，用户看得出没卡住。
 * [rxBytes] / [txBytes] 为 -1 表示系统不提供按应用的流量统计（只显示用时）。
 */
data class Meter(
    val startedAt: Long,
    val elapsedMs: Long,
    val rxBytes: Long,
    val txBytes: Long,
    val rxPerSec: Long,
    val txPerSec: Long,
) {
    companion object {
        val ZERO = Meter(0, 0, 0, 0, 0, 0)
    }
}

/**
 * 按应用 uid 统计流量（[TrafficStats]，与传输方式无关：HTTPS、SSH、经代理或 VPN 都算在本应用名下）。
 * 同步任务串行执行，任务期间本应用没有别的联网，所以差值就是这次任务的流量。每次 [sample] 记一个点，速度取最近 [WINDOW_MS] 内的平均。
 */
class TrafficMeter {
    private val uid = Process.myUid()
    private val startedAt = System.currentTimeMillis()
    private val start = SystemClock.elapsedRealtime()
    private val rx0 = TrafficStats.getUidRxBytes(uid)
    private val tx0 = TrafficStats.getUidTxBytes(uid)
    private val supported = rx0 != TrafficStats.UNSUPPORTED.toLong() && tx0 != TrafficStats.UNSUPPORTED.toLong()

    private class Point(val at: Long, val rx: Long, val tx: Long)

    private val points = ArrayDeque<Point>()

    @Synchronized
    fun sample(): Meter {
        val now = SystemClock.elapsedRealtime()
        if (!supported) return Meter(startedAt, now - start, -1, -1, 0, 0)
        val rx = (TrafficStats.getUidRxBytes(uid) - rx0).coerceAtLeast(0)
        val tx = (TrafficStats.getUidTxBytes(uid) - tx0).coerceAtLeast(0)
        points.addLast(Point(now, rx, tx))
        while (points.size > 2 && now - points.first().at > WINDOW_MS) points.removeFirst()
        val first = points.first()
        val span = now - first.at
        val rxRate = if (span > 0) (rx - first.rx) * 1000 / span else 0
        val txRate = if (span > 0) (tx - first.tx) * 1000 / span else 0
        return Meter(startedAt, now - start, rx, tx, rxRate, txRate)
    }

    private companion object {
        const val WINDOW_MS = 3_000L
    }
}
