package com.dudu.mixin.plugin.sign

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 仿 Android `zipalign` 行为：把 STORED（未压缩）entry 的数据偏移对齐到指定字节边界。
 *
 *  - 普通 STORED entry → 4 字节对齐
 *  - `.so` （native library）→ 4096 字节对齐（新版 Android 需要 mmap 加载）
 *  - DEFLATED entry → 不需要对齐（运行时会被解压）
 *
 * 实现策略：在每个 entry 的 extra 字段尾部追加 0x00 填充字节，使 file-data 偏移满足对齐。
 * 为了精确知道 file-data 偏移，我们使用一个 [CountingOutputStream] 跟踪写入字节数。
 *
 * 注：`java.util.zip.ZipOutputStream` 对 STORED entry 要求显式设置 size / crc /
 *    compressedSize；DEFLATED 则交由内部 deflater 自动计算。
 */
object ZipAligner {

    private const val LOCAL_FILE_HEADER_SIZE = 30L
    private const val DEFAULT_ALIGNMENT = 4
    private const val SO_ALIGNMENT = 4096

    fun align(input: File, output: File, alignment: Int = DEFAULT_ALIGNMENT, soAlignment: Int = SO_ALIGNMENT) {
        ZipFile(input).use { zip ->
            FileOutputStream(output).use { fos ->
                val counter = CountingOutputStream(fos)
                ZipOutputStream(counter).use { zos ->
                    val entries = zip.entries().toList()
                    var stored = 0
                    var aligned = 0
                    for (e in entries) {
                        val newEntry = ZipEntry(e.name).apply {
                            method = e.method
                            time = e.time
                            comment = e.comment
                        }

                        if (e.method == ZipEntry.STORED) {
                            newEntry.size = e.size
                            newEntry.crc = e.crc
                            newEntry.compressedSize = e.size
                            stored++

                            val align = if (e.name.endsWith(".so")) soAlignment else alignment
                            val nameBytes = e.name.toByteArray(Charsets.UTF_8).size
                            val existingExtra = e.extra ?: ByteArray(0)
                            // 计算如果按现有 extra 写出后，file-data 会落在的偏移
                            val dataOffset = counter.count + LOCAL_FILE_HEADER_SIZE + nameBytes + existingExtra.size
                            val padding = ((align - (dataOffset % align).toInt()) % align)
                            newEntry.extra = if (padding > 0) {
                                aligned++
                                existingExtra + ByteArray(padding)
                            } else if (existingExtra.isNotEmpty()) {
                                existingExtra
                            } else null
                        } else if (e.extra != null) {
                            newEntry.extra = e.extra
                        }

                        zos.putNextEntry(newEntry)
                        zip.getInputStream(e).use { input -> input.copyTo(zos) }
                        zos.closeEntry()
                    }
                    println("[zipalign] entries=${entries.size}, stored=$stored, aligned=$aligned")
                }
            }
        }
    }

    /** 跟踪已写入字节数，用于计算 ZIP 局部文件头偏移。 */
    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        var count: Long = 0L
            private set
        override fun write(b: Int) {
            out.write(b); count++
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len); count += len
        }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }
}
