package com.mountriv.vaultsync.gitcore.sync

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 提交信息模板。占位符：`{{date}}`、`{{hostname}}`、`{{numFiles}}`、`{{files}}`（最多 10 个路径）。
 *
 * 日期格式按 obsidian-git（moment.js）写法，例如默认 `YYYY-MM-DD HH:mm:ss`；这里把 moment 的
 * `YYYY`/`YY` → `yyyy`/`yy`、`DD`/`D` → `dd`/`d` 转成 java.time 的写法，其余字母原样传给 [DateTimeFormatter]。
 */
object CommitMessageTemplate {

    private const val MAX_FILES_IN_MESSAGE = 10

    fun render(
        template: String,
        dateFormat: String,
        now: ZonedDateTime,
        hostname: String,
        files: List<String>,
    ): String {
        val date = try {
            now.format(DateTimeFormatter.ofPattern(momentToJava(dateFormat)))
        } catch (e: IllegalArgumentException) {
            now.format(DateTimeFormatter.ofPattern(momentToJava(DEFAULT_MOMENT_FORMAT)))
        }
        val shown = files.take(MAX_FILES_IN_MESSAGE).joinToString(", ") +
            if (files.size > MAX_FILES_IN_MESSAGE) " …" else ""
        return template
            .replace("{{date}}", date)
            .replace("{{hostname}}", hostname)
            .replace("{{numFiles}}", files.size.toString())
            .replace("{{files}}", shown)
    }

    /** moment.js 风格 → java.time 风格。只转换年、日两组大小写含义相反的字母。 */
    fun momentToJava(momentFormat: String): String {
        val sb = StringBuilder(momentFormat.length)
        var inQuote = false
        for (ch in momentFormat) {
            when {
                ch == '\'' -> { inQuote = !inQuote; sb.append(ch) }
                inQuote -> sb.append(ch)
                ch == 'Y' -> sb.append('y')
                ch == 'D' -> sb.append('d')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    const val DEFAULT_MOMENT_FORMAT = "YYYY-MM-DD HH:mm:ss"
}
