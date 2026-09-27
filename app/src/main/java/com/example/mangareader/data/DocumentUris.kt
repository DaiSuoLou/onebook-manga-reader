package com.example.mangareader.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/**
 * SAF（Storage Access Framework）树节点的 uri 工具。
 *
 * 之所以不直接用已废弃的 [androidx.documentfile.provider.DocumentFile]：它每取一个子项
 * 都要跨进程一次 IPC，扫一个 200 页的章节就是 200 次 Binder 往返；这里统一走
 * [ContentResolver.query]，一次查询把 documentId / 名字 / mimeType 全拿回来。
 */
object DocumentUris {

    /** 列子项时最小够用的投影 */
    val PROJECTION = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
    )

    /** 一次查到的子项 */
    data class Child(
        val documentId: String,
        val displayName: String,
        val mimeType: String?,
    ) {
        /** 目录判定：部分 provider 会给目录返回 MIME_TYPE_DIR，实测 externalstorage 是给全的 */
        val isDirectory: Boolean get() = mimeType == Document.MIME_TYPE_DIR
    }

    /** 树的根 documentId，如 tree/primary%3AManga 中的 primary:Manga */
    fun rootDocumentId(treeUri: Uri): String = DocumentsContract.getTreeDocumentId(treeUri)

    fun childrenUri(treeUri: Uri, parentDocumentId: String): Uri =
        DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)

    fun documentUri(treeUri: Uri, documentId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    /**
     * 源内相对路径 -> documentId。
     *
     * SAF 的 documentId 长这样：`primary:Manga/OnePiece/第01话` —— 冒号后面是相对路径，
     * 段之间用 `/` 拼，所以子项 id 就是父 id 后接 `/段名`。
     */
    fun documentIdOf(treeUri: Uri, relativePath: String): String {
        val root = rootDocumentId(treeUri)
        val segments = relativePath.split('/').filter { it.isNotEmpty() }
        return if (segments.isEmpty()) root else segments.joinToString("/", prefix = "$root/")
    }

    /** 列目录下所有子项。返回空列表表示目录不存在或无权限。 */
    fun listChildren(resolver: ContentResolver, childrenUri: Uri): List<Child> {
        val cursor = resolver.query(childrenUri, PROJECTION, null, null, null) ?: return emptyList()
        cursor.use {
            // 部分云盘 provider 的 cursor 可能缺少某一列（实测 Google Drive 就缺 mime），
            // 用 getColumnIndexOrThrow 会直接崩，导致整层目录读成空。这里降级到 -1 跳过。
            val idIdx = it.getColumnIndex(Document.COLUMN_DOCUMENT_ID)
            val nameIdx = it.getColumnIndex(Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = it.getColumnIndex(Document.COLUMN_MIME_TYPE)
            if (idIdx < 0 || nameIdx < 0) return emptyList()

            val list = ArrayList<Child>(it.count)
            while (it.moveToNext()) {
                list.add(
                    Child(
                        documentId = it.getString(idIdx),
                        displayName = it.getString(nameIdx) ?: "",
                        mimeType = if (mimeIdx >= 0) it.getString(mimeIdx) else null,
                    )
                )
            }
            return list
        }
    }

    /** 打开单个文档的内容流。拿不到返回 null，由调用方决定怎么提示。 */
    fun openDocument(resolver: ContentResolver, documentUri: Uri): java.io.InputStream? =
        runCatching { resolver.openInputStream(documentUri) }.getOrNull()

    /** 读单个文档的 mimeType，判定不了返回 null */
    fun mimeOf(resolver: ContentResolver, documentUri: Uri): String? = runCatching {
        resolver.query(documentUri, arrayOf(Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    /** 读单个文档的显示名，判定不了返回 null */
    fun nameOf(resolver: ContentResolver, documentUri: Uri): String? = runCatching {
        resolver.query(documentUri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}
