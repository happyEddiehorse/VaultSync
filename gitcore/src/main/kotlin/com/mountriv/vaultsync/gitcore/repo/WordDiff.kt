package com.mountriv.vaultsync.gitcore.repo

import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.Sequence
import org.eclipse.jgit.diff.SequenceComparator

/**
 * 字词级对比（D-031，docs/SYNC_ENGINE.md §10.1）：把一对删除行 / 新增行切成 token 再对比，找出两行各自真正改动的字符区间，
 * 提交详情页据此在行底色上再加深一档。
 *
 * 切分：中日韩文字每字一个；字母、数字、下划线连成一个；连续空白一个；其它字符（标点、符号、表情）每个一个。
 * 只有一处连续改动（行尾追加、中间插入或替换一段）时，两行有相同部分就给区间；
 * 改动有多处时，相同部分不到较长一行的一半就不给区间——那等于整行重写，零散的加深没有意义。
 */
object WordDiff {

    /** 超过这个长度的行不做字词对比，免得一行特别长的文本拖慢展开。 */
    const val MAX_LINE_CHARS = 10_000

    /** 两行各自改动的字符区间（`start until end`，按字符下标，已排序、不重叠）。 */
    data class Result(val oldChanged: List<IntRange>, val newChanged: List<IntRange>) {
        companion object {
            val NONE = Result(emptyList(), emptyList())
        }
    }

    fun compare(old: String, new: String): Result {
        if (old == new || old.length > MAX_LINE_CHARS || new.length > MAX_LINE_CHARS) return Result.NONE
        val a = Tokens(old)
        val b = Tokens(new)
        val edits = HistogramDiff().diff(TokenComparator, a, b)
        val oldChanged = ArrayList<IntRange>()
        val newChanged = ArrayList<IntRange>()
        var changedOldChars = 0
        for (e in edits) {
            if (e.endA > e.beginA) {
                oldChanged += a.start(e.beginA) until a.end(e.endA - 1)
                changedOldChars += a.end(e.endA - 1) - a.start(e.beginA)
            }
            if (e.endB > e.beginB) newChanged += b.start(e.beginB) until b.end(e.endB - 1)
        }
        // token 覆盖整行，所以没改动的字符数 = 旧行长度 - 旧行改动的字符数（两行相同部分一样长）
        val common = old.length - changedOldChars
        if (common == 0 || edits.size > 1 && common * 2 < maxOf(old.length, new.length)) return Result.NONE
        return Result(oldChanged, newChanged)
    }

    /** 按上面的规则切分，返回每个 token 的字符区间（`start until end`）；这些区间首尾相接，覆盖整行。 */
    fun tokenize(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val kind = kindOf(cp)
            var j = i + Character.charCount(cp)
            if (kind == Kind.WORD || kind == Kind.SPACE) {
                while (j < text.length) {
                    val next = text.codePointAt(j)
                    if (kindOf(next) != kind) break
                    j += Character.charCount(next)
                }
            }
            out += i until j
            i = j
        }
        return out
    }

    private enum class Kind { CJK, WORD, SPACE, OTHER }

    private fun kindOf(cp: Int): Kind = when {
        isCjk(cp) -> Kind.CJK
        Character.isLetterOrDigit(cp) || cp == '_'.code -> Kind.WORD
        Character.isWhitespace(cp) -> Kind.SPACE
        else -> Kind.OTHER
    }

    /** 汉字、日文假名、韩文。`Character.UnicodeScript` 在 Android API 24 起可用（minSdk 29）。 */
    private fun isCjk(cp: Int): Boolean = Character.isIdeographic(cp) || when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL,
        -> true
        else -> false
    }

    /** 一行文本的 token 序列，供 JGit 的 diff 算法使用。 */
    private class Tokens(val text: String) : Sequence() {
        private val starts: IntArray
        private val ends: IntArray

        init {
            val ranges = tokenize(text)
            starts = IntArray(ranges.size) { ranges[it].first }
            ends = IntArray(ranges.size) { ranges[it].last + 1 }
        }

        override fun size(): Int = starts.size

        fun start(i: Int): Int = starts[i]

        /** 第 [i] 个 token 的结束位置（不含）。 */
        fun end(i: Int): Int = ends[i]
    }

    private object TokenComparator : SequenceComparator<Tokens>() {
        override fun equals(a: Tokens, ai: Int, b: Tokens, bi: Int): Boolean {
            val length = a.end(ai) - a.start(ai)
            return length == b.end(bi) - b.start(bi) && a.text.regionMatches(a.start(ai), b.text, b.start(bi), length)
        }

        override fun hash(seq: Tokens, ptr: Int): Int {
            var h = 0
            for (i in seq.start(ptr) until seq.end(ptr)) h = 31 * h + seq.text[i].code
            return h
        }
    }
}
