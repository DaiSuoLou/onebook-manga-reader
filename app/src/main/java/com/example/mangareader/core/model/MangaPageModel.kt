package com.example.mangareader.core.model

import com.example.mangareader.core.document.MangaDocument
import java.io.InputStream

/**
 * 一页漫画：文档 + 页码。
 *
 * 之所以把 document 一起带上，是为了让调用方（阅读器 / 图片加载器）
 * 拿到一页时不必再自己持有文档引用。
 *
 * 注意：equals/hashCode 对 document 走引用比较（MangaDocument 实现未重写 equals），
 * 即「同一个文档实例 + 同一页码」才相等——这正是缓存 key 想要的行为。
 */
data class MangaPageModel(
    val document: MangaDocument,
    val pageIndex: Int,
) {

    /** 该页在文档内的稳定标识，可直接用作缓存 key */
    val identifier: String
        get() = document.getPageIdentifier(pageIndex)

    /** 文档总页数 */
    val pageCount: Int
        get() = document.pageCount

    val isFirst: Boolean get() = pageIndex == 0
    val isLast: Boolean get() = pageIndex >= document.pageCount - 1

    /** 打开该页图像流，调用方负责关闭 */
    suspend fun openStream(): InputStream = document.getPageStream(pageIndex)

    /** 取相邻页，越界返回 null */
    fun next(): MangaPageModel? =
        if (isLast) null else MangaPageModel(document, pageIndex + 1)

    fun previous(): MangaPageModel? =
        if (isFirst) null else MangaPageModel(document, pageIndex - 1)
}
