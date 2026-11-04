package com.mountriv.vaultsync.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.net.toUri

/**
 * 存储权限策略（见 docs/ANDROID_PLATFORM.md §1、memory/LESSONS.md L-005）。
 *
 * - Android 10（API 29）：legacy 外部存储 + READ/WRITE_EXTERNAL_STORAGE 运行时权限 → 真实路径读写共享存储。
 * - Android 11+（API 30+）：MANAGE_EXTERNAL_STORAGE（"所有文件访问权限"），需跳转系统设置页由用户开启。
 */
object StoragePermissions {

    /** 是否已经可以用 java.io.File 读写整个共享存储。 */
    fun isGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val write = context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val read = context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            write == PackageManager.PERMISSION_GRANTED &&
                read == PackageManager.PERMISSION_GRANTED &&
                Environment.isExternalStorageLegacy()
        }
    }

    /** Android 10 上需要通过标准对话框申请的权限。 */
    val legacyRuntimePermissions: Array<String> = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE,
    )

    /** 是否走"系统设置页开关"流程（Android 11+）。 */
    val usesAllFilesAccessSetting: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Android 11+：打开本应用的"所有文件访问权限"设置页；低版本返回 null。 */
    fun allFilesAccessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:${context.packageName}".toUri(),
        )
    }

    /** Android 11+ 的兜底：某些 ROM 没有按包跳转的页面，退回到总列表。 */
    fun allFilesAccessFallbackIntent(): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }

    /** 共享存储根目录（`/storage/emulated/0`）。 */
    @Suppress("DEPRECATION")
    fun sharedStorageRoot(): java.io.File = Environment.getExternalStorageDirectory()
}
