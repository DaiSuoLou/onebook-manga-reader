package com.example.mangareader.core.document

import java.io.File

/**
 * 漫画文件格式白名单 —— 单一真源。
 *
 * 「一个文件算不算章节」这件事原先散落在三处（[LocalMangaSource]、[SafeMangaSource]、
 * [MangaDocumentFactory]）各写一套集合，于是加一种格式要改三处、极易漏改，
 * 漏改的表现就是书架上看不到这个文件（用户侧的"无法识别"）。这里收敛成一份。
 */
object MangaFormats {

    /** commons-compress 能顺序流解开的压缩包（zip / cbz / tar / 7z ...） */
    val STREAM_ARCHIVE = setOf("zip", "cbz", "cbr", "tar", "tgz", "7z")

    /** RAR：commons-compress 自 1.8 起已移除 RAR 支持，走 junrar */
    val RAR = setOf("rar")

    /** PDF：系统 PdfRenderer */
    val PDF = setOf("pdf")

    /** EPUB：容器本身就是 zip，需 ZipFile 随机访问 */
    val EPUB = setOf("epub")

    /** MOBI / AZW / AZW3 / PDB：记录式容器，需 seek 读取 */
    val MOBI = setOf("mobi", "azw", "azw3", "prc")

    /** 所有能作为「单文件章节」打开的扩展名 */
    val CHAPTER_FILE = STREAM_ARCHIVE + RAR + PDF + EPUB + MOBI

    /**
     * 常见 MIME type -> 扩展名。
     *
     * SAF 云盘 provider 经常把文件扩展名从 [Document.COLUMN_DISPLAY_NAME] 里抹掉
     *（比如只显示"镇魂街"而不是"镇魂街.mobi"），只通过 MIME type 标识类型。
     * 这时单靠扩展名判定会漏掉文件，所以要补一道 MIME 兜底。
     */
    val MIME_TO_EXT: Map<String, String> = mapOf(
        // MOBI / AZW / PRC 系列
        // 很多国产 ROM/云盘把 .mobi 标成 Palm PRC，所以 PRC 相关 MIME 都要映射到 mobi
        "application/x-mobipocket-ebook" to "mobi",
        "application/x-mobipocket-subscription" to "mobi",
        "application/vnd.amazon.mobi8-ebook" to "azw3",
        "application/vnd.amazon.ebook" to "azw",
        "application/x-pilot-prc" to "prc",
        "application/vnd.palm" to "prc",
        "application/prc.plucker" to "prc",
        // EPUB
        "application/epub+zip" to "epub",
        // PDF
        "application/pdf" to "pdf",
        // 压缩包
        "application/zip" to "zip",
        "application/x-zip-compressed" to "zip",
        "application/x-rar-compressed" to "rar",
        "application/vnd.rar" to "rar",
        "application/x-7z-compressed" to "7z",
        "application/x-cbr" to "cbr",
        "application/x-cbz" to "cbz",
        "application/x-tar" to "tar",
        "application/gzip" to "tgz",
        "application/x-gzip" to "tgz",
    )
}

/** 文件名（或相对路径）是否是可作为章节的单文件。扩展名比较不区分大小写。 */
fun String.isChapterFile(): Boolean =
    substringAfterLast('.', "").lowercase() in MangaFormats.CHAPTER_FILE

/** 同上，[File] 版本 */
fun File.isChapterFile(): Boolean = isFile && name.isChapterFile()

/**
 * 由 MIME type 判断文件是否是可识别的章节格式。
 *
 * 返回推导出的扩展名（如 `"mobi"`），返回 null 表示不是已知漫画容器。
 */
fun String?.isChapterMime(): String? {
    if (this == null) return null
    val normalized = lowercase().trim()
    return MangaFormats.MIME_TO_EXT[normalized]
}
