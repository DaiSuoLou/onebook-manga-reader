package com.example.mangareader.core.document

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MOBI / AZW / AZW3(KF8) / PDB / PRC 漫画。
 *
 * MOBI 本质是 Palm 数据库(PDB)：78 字节文件头 + 每条记录 8 字节目录项 + 记录数据。
 * 漫画型 MOBI 直接把 JPEG/PNG 内嵌在记录里。
 *
 * **健壮性策略**：
 * 1. 先按标准 PDB 头解析记录表，并尝试多种常见偏移/字节序组合；
 * 2. 成功后再解压记录 0 读 MOBI header 的 firstImageIndex，从该位置扫描图片；
 * 3. 若 PDB 头解析失败（很多用户文件被非标准工具生成），退回到「全文件扫描图片魔数」，
 *    把每个图片开始位置当作一页。这会让封面/缩略图也可能被扫进来，但至少能打开阅读。
 */
class MobiMangaDocument(
    private val file: File,
) : MangaDocument {

    private val recordStarts: LongArray
    private val recordEnds: LongArray
    private val pageRecords: List<Int>
    private val fallbackImageOffsets: List<Long>?

    init {
        val fileLen = file.length()
        require(fileLen > 78) { "文件太小，不是有效的 MOBI/PDB: ${file.name}" }

        val raf = RandomAccessFile(file, "r")
        try {
            val header = ByteArray(78)
            raf.seek(0)
            raf.readFully(header)

            // 标准 Palm PDB：记录数 @76-77，目录表 @78。
            // 一些非标准生成器会把字段挪位，这里枚举常见候选。
            val candidate = readRecordTable(raf, fileLen, header, numOffset = 76, tableOffset = 78L, bigEndian = true)
                ?: readRecordTable(raf, fileLen, header, numOffset = 74, tableOffset = 76L, bigEndian = true)
                ?: readRecordTable(raf, fileLen, header, numOffset = 72, tableOffset = 76L, bigEndian = true)
                ?: readRecordTable(raf, fileLen, header, numOffset = 78, tableOffset = 80L, bigEndian = true)
                ?: readRecordTable(raf, fileLen, header, numOffset = 76, tableOffset = 78L, bigEndian = false)
                ?: readRecordTable(raf, fileLen, header, numOffset = 74, tableOffset = 76L, bigEndian = false)

            if (candidate != null) {
                recordStarts = candidate.first
                recordEnds = candidate.second
                val count = recordStarts.size
                Log.d(TAG, "${file.name}: PDB 记录数=$count, 文件大小=$fileLen")

                val firstImage = runCatching { readFirstImageIndex(raf, count) }
                    .getOrNull()
                    ?.also { Log.d(TAG, "${file.name}: firstImageIndex=$it") }

                val range = when {
                    firstImage != null && firstImage in 1 until count -> firstImage until count
                    else -> 1 until count
                }
                val found = range.filter { i ->
                    recordEnds[i] > recordStarts[i] && isImage(raf, i)
                }.toMutableList()

                if (found.isEmpty()) {
                    found.addAll((1 until count).filter { i ->
                        recordEnds[i] > recordStarts[i] && isImage(raf, i)
                    })
                }
                pageRecords = found
                fallbackImageOffsets = null
                Log.d(TAG, "${file.name}: 记录扫描找到 ${pageRecords.size} 张图片")
            } else {
                // PDB 头解析失败：退回到全文件魔数扫描。
                Log.w(TAG, "${file.name}: 标准 PDB 头解析失败，尝试全文件图片魔数扫描")
                recordStarts = LongArray(0)
                recordEnds = LongArray(0)
                pageRecords = emptyList()
                fallbackImageOffsets = scanImageOffsets(raf, fileLen)
                Log.d(TAG, "${file.name}: 魔数扫描找到 ${fallbackImageOffsets.size} 张图片")
            }

            if (pageRecords.isEmpty() && fallbackImageOffsets.isNullOrEmpty()) {
                error(buildDiagnosticMessage(file.name, header, fileLen))
            }
        } finally {
            raf.close()
        }
    }

    /**
     * 读 PDB 记录目录表，返回 [每条记录起始偏移, 每条记录结束偏移]。
     * 任一步不合理（记录数异常 / 偏移越界 / 不递增）就返回 null 让调用方换另一种布局。
     */
    private fun readRecordTable(
        raf: RandomAccessFile,
        fileLen: Long,
        header: ByteArray,
        numOffset: Int,
        tableOffset: Long,
        bigEndian: Boolean,
    ): Pair<LongArray, LongArray>? {
        val count = readU16(header, numOffset, bigEndian)
        if (count <= 0 || count > 10_000_000) return null

        val starts = LongArray(count)
        val buf = ByteArray(8)
        for (i in 0 until count) {
            raf.seek(tableOffset + i * 8L)
            raf.readFully(buf)
            val off = readU32(buf, 0, bigEndian)
            if (off < 0L || off > fileLen) return null
            if (i > 0 && off < starts[i - 1]) return null
            starts[i] = off
        }

        val ends = LongArray(count)
        for (i in 0 until count) {
            ends[i] = if (i + 1 < count) starts[i + 1] else fileLen
            if (ends[i] <= starts[i]) return null
        }
        return starts to ends
    }

    /**
     * 读取 MOBI header 中的 firstImageIndex。
     */
    private fun readFirstImageIndex(raf: RandomAccessFile, recordCount: Int): Int? {
        if (recordCount <= 0) return null
        val rec0Len = (recordEnds[0] - recordStarts[0]).toInt()
        if (rec0Len <= 16) return null

        val raw = ByteArray(rec0Len)
        raf.seek(recordStarts[0])
        raf.readFully(raw)

        val compression = readU16(raw, 0, bigEndian = true)
        val textLength = readU32(raw, 4, bigEndian = true).toInt().coerceAtLeast(0)

        val uncompressed = when (compression) {
            1, 0 -> raw
            2 -> runCatching {
                PalmDocDecompressor.decompress(raw, textLength.coerceAtLeast(rec0Len * 4))
            }.getOrNull() ?: raw
            else -> raw
        }

        val mobiOffset = uncompressed.indexOfBytes(MOBI_MAGIC)
        if (mobiOffset < 0 || uncompressed.size < mobiOffset + 92) return null

        val idx = readU32(uncompressed, mobiOffset + 0x58, bigEndian = true).toInt()
        return if (idx in 1 until recordCount) idx else null
    }

    /**
     * 全文件扫描图片魔数，返回每张图的起始偏移。
     * 用于 PDB 头解析失败时的兜底，让用户至少能看到内容。
     */
    private fun scanImageOffsets(raf: RandomAccessFile, fileLen: Long): List<Long> {
        val offsets = mutableListOf<Long>()
        val chunkSize = 64 * 1024
        val overlap = 16
        var pos = 0L
        val buf = ByteArray(chunkSize + overlap)

        while (pos < fileLen) {
            val toRead = min(chunkSize + overlap, (fileLen - pos).toInt())
            raf.seek(pos)
            raf.readFully(buf, 0, toRead)

            for (i in 0 until toRead - 4) {
                val absolute = pos + i
                if (isImageMagic(buf, i)) {
                    // 避免同一个图片头被相邻 chunk 重复记录（只在 chunk 边界处检查）
                    if (offsets.isEmpty() || absolute > offsets.last() + 1) {
                        offsets.add(absolute)
                    }
                }
            }
            pos += chunkSize
        }
        return offsets
    }

    private fun isImageMagic(buf: ByteArray, off: Int): Boolean {
        if (off + 4 > buf.size) return false
        val h = buf
        return when {
            h[off] == 0xFF.toByte() && h[off + 1] == 0xD8.toByte() -> true          // JPEG
            h[off] == 0x89.toByte() && h[off + 1] == 0x50.toByte() -> true         // PNG
            off + 6 <= h.size && String(h, off, 6, Charsets.US_ASCII) == "GIF87a" -> true
            off + 6 <= h.size && String(h, off, 6, Charsets.US_ASCII) == "GIF89a" -> true
            off + 12 <= h.size && String(h, off, 4, Charsets.US_ASCII) == "RIFF" &&
                String(h, off + 8, 4, Charsets.US_ASCII) == "WEBP" -> true
            off + 8 <= h.size && String(h, off + 4, 4, Charsets.US_ASCII) == "ftyp" -> true  // HEIC/AVIF
            else -> false
        }
    }

    override val pageCount: Int get() =
        if (fallbackImageOffsets != null) fallbackImageOffsets.size else pageRecords.size

    override suspend fun getPageStream(index: Int): InputStream = withContext(Dispatchers.IO) {
        val fallback = fallbackImageOffsets
        if (fallback != null) {
            // 兜底模式：从图片魔数位置读到下一张图开头或文件末尾。
            val start = fallback[index]
            val end = if (index + 1 < fallback.size) fallback[index + 1] else file.length()
            val len = (end - start).toInt()
            check(len > 0) { "图片数据为空" }
            val buf = ByteArray(len)
            RandomAccessFile(file, "r").use { r ->
                r.seek(start)
                r.readFully(buf)
            }
            return@withContext buf.inputStream()
        }

        val rec = pageRecords[index]
        val from = recordStarts[rec]
        val len = (recordEnds[rec] - from).toInt()
        check(len > 0) { "图片记录为空: $rec" }
        val buf = ByteArray(len)
        RandomAccessFile(file, "r").use { r ->
            r.seek(from)
            r.readFully(buf)
        }
        buf.inputStream()
    }

    override fun getPageIdentifier(index: Int): String {
        val fallback = fallbackImageOffsets
        if (fallback != null) {
            require(index in fallback.indices) { "页码越界: $index / 共 $pageCount 页" }
            return "${file.name}#offset-${fallback[index]}"
        }
        require(index in pageRecords.indices) { "页码越界: $index / 共 $pageCount 页" }
        return "${file.name}#record-${pageRecords[index]}"
    }

    override fun close() = Unit

    /** seek 到记录开头只读前 12 字节，判断是不是一张图 */
    private fun isImage(raf: RandomAccessFile, record: Int): Boolean =
        runCatching {
            val from = recordStarts[record]
            val head = ByteArray(12)
            raf.seek(from)
            raf.readFully(head, 0, minOf(head.size, (recordEnds[record] - from).toInt()))
            isImageMagic(head, 0)
        }.getOrDefault(false)

    private fun buildDiagnosticMessage(name: String, header: ByteArray, fileLen: Long): String {
        val first16 = header.take(16).joinToString(" ") { "%02X".format(it) }
        val type = runCatching { String(header, 60, 4, Charsets.US_ASCII) }.getOrDefault("?")
        val creator = runCatching { String(header, 64, 4, Charsets.US_ASCII) }.getOrDefault("?")
        val counts = listOf(72, 74, 76, 78).map { readU16(header, it, true) }
        val countsLe = listOf(72, 74, 76, 78).map { readU16(header, it, false) }
        return buildString {
            append("无法解析 $name（大小 ${fileLen}B）：")
            append("头16字节=$first16；")
            append("type='$type' creator='$creator'；")
            append("候选记录数（大端@72/74/76/78）=$counts；")
            append("候选记录数（小端@72/74/76/78）=$countsLe。")
            append("可能不是标准 MOBI/PDB，或文件已损坏。")
        }
    }

    private fun readU16(b: ByteArray, off: Int, bigEndian: Boolean): Int {
        require(off + 1 < b.size) { "readU16 越界: $off / ${b.size}" }
        return if (bigEndian) {
            ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
        } else {
            ((b[off + 1].toInt() and 0xFF) shl 8) or (b[off].toInt() and 0xFF)
        }
    }

    private fun readU32(b: ByteArray, off: Int, bigEndian: Boolean): Long {
        require(off + 3 < b.size) { "readU32 越界: $off / ${b.size}" }
        return if (bigEndian) {
            ((b[off].toLong() and 0xFFL) shl 24) or
                ((b[off + 1].toLong() and 0xFFL) shl 16) or
                ((b[off + 2].toLong() and 0xFFL) shl 8) or
                (b[off + 3].toLong() and 0xFFL)
        } else {
            ((b[off + 3].toLong() and 0xFFL) shl 24) or
                ((b[off + 2].toLong() and 0xFFL) shl 16) or
                ((b[off + 1].toLong() and 0xFFL) shl 8) or
                (b[off].toLong() and 0xFFL)
        }
    }

    private companion object {
        const val TAG = "MobiMangaDocument"
        val MOBI_MAGIC = "MOBI".toByteArray(Charsets.US_ASCII)
    }
}

/**
 * PalmDOC 流解压（LZ77 变体）。
 */
private object PalmDocDecompressor {

    fun decompress(input: ByteArray, outputLength: Int): ByteArray {
        val out = DynamicByteArray(outputLength.coerceAtLeast(input.size * 2))
        var i = 0
        while (i < input.size && out.size < outputLength) {
            val b = input[i].toInt() and 0xFF
            when {
                b == 0x00 -> out.add(0x00)
                b in 0x09..0x7F -> out.add(b)
                b in 0x01..0x08 -> {
                    repeat(b) {
                        i++
                        if (i < input.size) out.add(input[i].toInt() and 0xFF)
                    }
                }
                b in 0x80..0xBF -> {
                    i++
                    val next = if (i < input.size) input[i].toInt() and 0xFF else 0
                    val distance = ((b and 0x03) shl 7) or (next and 0x7F)
                    val length = ((b and 0x7C) shr 2) + 3
                    repeat(length) { offset ->
                        val idx = out.size - distance + offset
                        if (idx >= 0 && idx < out.size) out.add(out[idx].toInt() and 0xFF)
                    }
                }
                b in 0xC0..0xFF -> {
                    out.add(0x20)
                    out.add(b xor 0x80)
                }
            }
            i++
        }
        return out.toByteArray()
    }

    private class DynamicByteArray(initialCapacity: Int) {
        private var buffer = ByteArray(initialCapacity.coerceAtLeast(1024))
        var size: Int = 0
            private set

        fun add(value: Int) {
            if (size == buffer.size) {
                buffer = buffer.copyOf(buffer.size * 2)
            }
            buffer[size++] = value.toByte()
        }

        operator fun get(index: Int): Byte = buffer[index]

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }
}

/** 在字节数组中查找子字节序列首次出现的位置，找不到返回 -1。 */
private fun ByteArray.indexOfBytes(pattern: ByteArray, startIndex: Int = 0): Int {
    if (pattern.isEmpty()) return 0
    for (i in startIndex..size - pattern.size) {
        var match = true
        for (j in pattern.indices) {
            if (this[i + j] != pattern[j]) {
                match = false
                break
            }
        }
        if (match) return i
    }
    return -1
}
