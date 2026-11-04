package com.mountriv.vaultsync.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * 提交详情里文件改动的配色（D-031，docs/UI.md §4.2）：删除红、新增绿，各两档——整行浅底，改动的字词再深一档。
 * 固定色值，不随 Android 12+ 的动态取色变化；浅色、深色模式各一套。
 */
@Immutable
data class DiffColors(
    val deletedLine: Color,
    val deletedWord: Color,
    val addedLine: Color,
    val addedWord: Color,
)

private val LightDiffColors = DiffColors(
    deletedLine = Color(0xFFFFEBE9),
    deletedWord = Color(0xFFFFB3AD),
    addedLine = Color(0xFFE6FFEC),
    addedWord = Color(0xFF9FE8B1),
)

private val DarkDiffColors = DiffColors(
    deletedLine = Color(0xFF3D2023),
    deletedWord = Color(0xFF7D2F35),
    addedLine = Color(0xFF1B3526),
    addedWord = Color(0xFF2A6B3E),
)

@Composable
fun diffColors(darkTheme: Boolean = isSystemInDarkTheme()): DiffColors = if (darkTheme) DarkDiffColors else LightDiffColors
