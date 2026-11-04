package com.mountriv.vaultsync.gitcore.repo

import java.io.File

/** 推荐的 Obsidian 库 `.gitignore`（与 docs/SYNC_ENGINE.md §9 一致）。只在库中没有 `.gitignore` 时写入，或由用户手动追加。 */
object GitIgnoreTemplate {

    const val HEADER = "# VaultSync recommended"

    /** 推荐规则（不含注释头）。 */
    val RULES: List<String> = listOf(
        ".obsidian/workspace.json",
        ".obsidian/workspace-mobile.json",
        ".obsidian/workspace.json.bak",
        ".obsidian/cache/",
        ".trash/",
        ".DS_Store",
        "Thumbs.db",
        "conflict-files-obsidian-git.md",
    )

    val RECOMMENDED: String = (listOf(HEADER) + RULES).joinToString("\n") + "\n"

    /** 写入模板；已存在则不动。返回是否写入。 */
    fun writeIfMissing(vaultDir: File): Boolean {
        val file = File(vaultDir, ".gitignore")
        if (file.exists()) return false
        file.writeText(RECOMMENDED, Charsets.UTF_8)
        return true
    }

    /** 推荐规则中库的 `.gitignore` 还没有的行（按行精确比较，忽略首尾空白）。文件不存在时返回全部规则。 */
    fun missingRules(vaultDir: File): List<String> {
        val file = File(vaultDir, ".gitignore")
        if (!file.isFile) return RULES
        val existing = file.readLines(Charsets.UTF_8).map { it.trim() }.toSet()
        return RULES.filter { it !in existing }
    }

    /**
     * 把缺少的推荐规则追加到 `.gitignore` 末尾（前面加注释头；文件不存在则新建）。返回追加的规则。
     * 已被 Git 跟踪的文件不会因此停止跟踪——这里不做 `git rm --cached`，否则下次同步会把它们从远端和电脑上删掉。
     */
    fun appendMissing(vaultDir: File): List<String> {
        val missing = missingRules(vaultDir)
        if (missing.isEmpty()) return missing
        val file = File(vaultDir, ".gitignore")
        if (!file.exists()) {
            file.writeText(RECOMMENDED, Charsets.UTF_8)
            return missing
        }
        val current = file.readText(Charsets.UTF_8)
        val lineEnding = if (current.contains("\r\n")) "\r\n" else "\n"
        val text = buildString {
            append(current)
            if (current.isNotEmpty() && !current.endsWith("\n")) append(lineEnding)
            if (current.isNotEmpty()) append(lineEnding)
            append(HEADER).append(lineEnding)
            missing.forEach { append(it).append(lineEnding) }
        }
        file.writeText(text, Charsets.UTF_8)
        return missing
    }
}
