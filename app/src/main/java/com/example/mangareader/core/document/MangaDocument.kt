package com.example.mangareader.core.document

import java.io.Closeable
import java.io.InputStream

/**
 * 漫画文档统一抽象接口
 *
 * 实现需保证：
 * - getPageStream 返回的流由调用方关闭
 * - 所有耗时操作（IO / 渲染）应切到 Dispatchers.IO 执行，不在主线程阻塞
 * - close() 释放底层句柄（ZipFile / PdfRenderer / 临时文件）
 */
interface MangaDocument : Closeable {

    /** 总页数 */
    val pageCount: Int

    /**
     * 获取指定页的图像输入流
     * @param index 页码索引 (0 until pageCount)
     * @return 图像字节流，由调用方负责 close
     */
    suspend fun getPageStream(index: Int): InputStream

    /**
     * 获取指定页的标识或文件名（可用于调试与缓存 Key）
     */
    fun getPageIdentifier(index: Int): String
}
