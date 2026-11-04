package com.mountriv.vaultsync.gitcore

import org.eclipse.jgit.lib.InflaterCache
import org.junit.jupiter.api.Test
import java.util.zip.Deflater
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

/**
 * D-033 / L-036：运行时用的是 gitcore 自带的 InflaterCache（不复用）。
 * Android 的 InflaterInputStream.close() 会把 JGit 借出的 Inflater end() 掉；JGit 原版在 release 时 reset() 一个已结束的 Inflater 会抛 NPE。
 */
class InflaterCachePatchTest {

    @Test
    fun `releasing an ended inflater is harmless and the next one works`() {
        val first = InflaterCache.get()
        first.end() // 模拟 Android 的 InflaterInputStream.close()
        InflaterCache.release(first) // 原版：NullPointerException("Inflater has been closed")

        val second = InflaterCache.get()
        assertNotSame(first, second)
        val data = "hello vault".toByteArray()
        val deflater = Deflater().apply { setInput(data); finish() }
        val buf = ByteArray(64)
        val n = deflater.deflate(buf)
        deflater.end()
        second.setInput(buf, 0, n)
        val out = ByteArray(64)
        assertEquals(data.size, second.inflate(out))
        assertEquals("hello vault", String(out, 0, data.size))
        InflaterCache.release(second)
        InflaterCache.release(second) // 重复释放也没事
    }

    @Test
    fun `the patched class is the one on the runtime classpath`() {
        // 原版有私有方法 getImpl / releaseImpl；替换版没有
        val methods = InflaterCache::class.java.declaredMethods.map { it.name }.toSet()
        assertEquals(setOf("get", "release"), methods)
    }
}
