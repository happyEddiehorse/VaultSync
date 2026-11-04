package com.mountriv.vaultsync.gitcore

import com.mountriv.vaultsync.gitcore.repo.WordDiff
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** M6（D-031）：字词级对比的切分与区间。 */
class WordDiffTest {

    private fun tokens(text: String): List<String> = WordDiff.tokenize(text).map { text.substring(it.first, it.last + 1) }

    private fun parts(text: String, ranges: List<IntRange>): List<String> = ranges.map { text.substring(it.first, it.last + 1) }

    @Test
    fun `chinese splits per character, words and whitespace runs stay together`() {
        assertEquals(listOf("今", "天", "去", "了", "图", "书", "馆", "，"), tokens("今天去了图书馆，"))
        assertEquals(listOf("hello", " ", "world_1", "  ", "foo", ",", "bar", "!"), tokens("hello world_1  foo,bar!"))
        assertEquals(listOf("用", "Git", " ", "同", "步", "v2", "。"), tokens("用Git 同步v2。"))
        assertEquals(listOf("ひ", "ら", "カ", "タ", "한", "글"), tokens("ひらカタ한글"))
        // 表情是一个码点（两个 char），不能被切开
        assertEquals(listOf("a", "😀", "b"), tokens("a😀b"))
        assertEquals(emptyList(), tokens(""))
        // 区间首尾相接、覆盖整行
        val text = "Mixed 中文 and_words, 123!"
        val ranges = WordDiff.tokenize(text)
        assertEquals(0, ranges.first().first)
        assertEquals(text.length - 1, ranges.last().last)
        ranges.zipWithNext().forEach { (x, y) -> assertEquals(x.last + 1, y.first) }
    }

    @Test
    fun `changed chinese character is highlighted on both lines`() {
        val old = "今天去了图书馆，借了三本书。"
        val new = "今天去了图书馆，借了两本书。"
        val r = WordDiff.compare(old, new)
        assertEquals(listOf("三"), parts(old, r.oldChanged))
        assertEquals(listOf("两"), parts(new, r.newChanged))
    }

    @Test
    fun `changed english word is highlighted as a whole word`() {
        val old = "The quick brown fox jumps over the lazy dog"
        val new = "The quick red fox jumps over the lazy dog"
        val r = WordDiff.compare(old, new)
        assertEquals(listOf("brown"), parts(old, r.oldChanged))
        assertEquals(listOf("red"), parts(new, r.newChanged))
    }

    @Test
    fun `insertion and deletion give ranges only on the side that has them`() {
        val old = "买牛奶和面包"
        val new = "买牛奶、鸡蛋和面包"
        val r = WordDiff.compare(old, new)
        assertEquals(emptyList(), r.oldChanged)
        assertEquals(listOf("、鸡蛋"), parts(new, r.newChanged))

        val back = WordDiff.compare(new, old)
        assertEquals(listOf("、鸡蛋"), parts(new, back.oldChanged))
        assertEquals(emptyList(), back.newChanged)
    }

    @Test
    fun `a single change is highlighted even when the lines share little`() {
        // 真机 D1：在短行末尾追加一大段，相同部分远不到一半，但改动只有一处
        val old = "随便改"
        val new = "随便改M6 N1 phone edit 19:38"
        val r = WordDiff.compare(old, new)
        assertEquals(emptyList(), r.oldChanged)
        assertEquals(listOf("M6 N1 phone edit 19:38"), parts(new, r.newChanged))

        val replaced = WordDiff.compare("# 旧", "# 全新的标题")
        assertEquals(listOf("旧"), parts("# 旧", replaced.oldChanged))
        assertEquals(listOf("全新的标题"), parts("# 全新的标题", replaced.newChanged))
    }

    @Test
    fun `lines that share less than half give no ranges`() {
        // 多处改动：只剩一个"的"相同
        assertEquals(WordDiff.Result.NONE, WordDiff.compare("完全不同的一句话", "另外写了别的内容"))
        assertEquals(WordDiff.Result.NONE, WordDiff.compare("alpha beta", "gamma delta"))
        assertEquals(WordDiff.Result.NONE, WordDiff.compare("", "新的一行"))
        assertEquals(WordDiff.Result.NONE, WordDiff.compare("same", "same"))
        // 恰好一半相同：给区间；英文单词是一个整体，"ab"→"ac" 等于整词替换
        val r = WordDiff.compare("甲乙", "甲丙")
        assertEquals(listOf("乙"), parts("甲乙", r.oldChanged))
        assertEquals(WordDiff.Result.NONE, WordDiff.compare("ab", "ac"))
    }

    @Test
    fun `very long lines are not compared`() {
        val long = "字".repeat(WordDiff.MAX_LINE_CHARS + 1)
        assertEquals(WordDiff.Result.NONE, WordDiff.compare(long, long + "尾"))
    }
}
