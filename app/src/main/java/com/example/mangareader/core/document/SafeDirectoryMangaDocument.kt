package com.example.mangareader.core.document

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.example.mangareader.core.util.naturalSortedBy
import com.example.mangareader.data.DocumentUris
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * SAF 授权目录下的「图片文件夹」漫画。对应基于 java.io.File 的 [DirectoryMangaDocument]，
 * 区别只在页数据从 content uri 取，全部走 [android.content.ContentResolver]。
 *
 * 构造私有 + [open]：扫描目录是 IO，必须在 [Dispatchers.IO] 上做。
 *
 * @param directoryUri 章节目录自身的 content uri（由 `buildDocumentUriUsingTree` 生成）
 * @param pageUris 自然排序后的图片 uri
 */
class SafeDirectoryMangaDocument private constructor(
    private val context: Context,
    private val directoryUri: Uri,
    private val pageUris: List<Uri>,
) : MangaDocument {

    override val pageCount: Int get() = pageUris.size

    override suspend fun getPageStream(index: Int): InputStream = withContext(Dispatchers.IO) {
        val uri = pageUris[index]
        context.contentResolver.openInputStream(uri)
            ?: throw FileNotFoundException("打不开图片: $uri（可能是授权失效了）")
    }

    /**
     * 缓存 key 必须是完整 content uri。
     *
     * 与 [DirectoryMangaDocument.getPageIdentifier] 同理：只给文件名会让不同章节的
     * 同名页撞 Coil 的磁盘缓存键，翻页会串到别的卷上。
     */
    override fun getPageIdentifier(index: Int): String = pageUris[index].toString()

    override fun close() = Unit

    companion object {

        suspend fun open(context: Context, directoryUri: Uri): SafeDirectoryMangaDocument =
            withContext(Dispatchers.IO) {
                val resolver = context.contentResolver

                // directoryUri 形如 content://.../tree/primary%3AManga/document/primary%3AManga%2F一话，
                // 它所在的树根就是 path 里 tree/ 后面那段，Children URI 必须以树的 uri 为基准构造。
                val treeUri = DocumentsContract.buildDocumentUriUsingTree(
                    directoryUri,
                    DocumentsContract.getTreeDocumentId(directoryUri),
                )
                val parentDocumentId = DocumentsContract.getDocumentId(directoryUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri,
                    parentDocumentId,
                )

                val pageUris = DocumentUris.listChildren(resolver, childrenUri)
                    .filter { !it.isDirectory && it.displayName.substringAfterLast('.', "").lowercase() in IMAGE_EXT }
                    .naturalSortedBy { it.displayName }
                    .map { DocumentUris.documentUri(treeUri, it.documentId) }

                SafeDirectoryMangaDocument(context, directoryUri, pageUris)
            }
    }
}
