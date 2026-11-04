package com.mountriv.vaultsync.gitcore.repo

import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.FileTreeIterator
import org.eclipse.jgit.treewalk.WorkingTreeIterator
import org.eclipse.jgit.util.FS
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InterruptedIOException

/**
 * 给 `AddCommand` 用的工作区遍历：读文件内容时按 KiB 报进度，并在每次读之间检查取消（L-039）。
 * JGit 读一个大文件期间没有任何进度回调，也不检查取消；30 MB 的文件在手机上要读十几秒。
 * 取消时抛 [Cancelled]，`AddCommand` 把它包成 `JGitInternalException`；index 锁和临时对象文件由 JGit 在 finally 里清理。
 */
internal object ProgressFileTreeIterator {

    class Cancelled : InterruptedIOException("cancelled while reading files")

    fun create(repo: Repository, monitor: ProgressMonitor, cancel: () -> Boolean): WorkingTreeIterator =
        Root(repo, Counter(monitor, cancel))

    private class Counter(private val monitor: ProgressMonitor, private val cancel: () -> Boolean) {
        private var pending = 0L

        fun read(n: Int) {
            if (cancel()) throw Cancelled()
            pending += n
            if (pending >= 1024) {
                monitor.update((pending / 1024).toInt())
                pending %= 1024
            }
        }
    }

    private class Root(repo: Repository, private val counter: Counter) : FileTreeIterator(repo) {
        override fun enterSubtree(): AbstractTreeIterator = Sub(this, entryFile, fs, fileModeStrategy, counter)
        override fun openEntryStream(): InputStream = Counting(super.openEntryStream(), counter)
    }

    private class Sub(
        parent: WorkingTreeIterator,
        dir: File,
        fs: FS,
        strategy: FileModeStrategy,
        private val counter: Counter,
    ) : FileTreeIterator(parent, dir, fs, strategy) {
        override fun enterSubtree(): AbstractTreeIterator = Sub(this, entryFile, fs, fileModeStrategy, counter)
        override fun openEntryStream(): InputStream = Counting(super.openEntryStream(), counter)
    }

    private class Counting(input: InputStream, private val counter: Counter) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) counter.read(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) counter.read(it) }
    }
}
