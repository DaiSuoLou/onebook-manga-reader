package com.example.mangareader.core.coil

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.example.mangareader.core.model.MangaPageModel
import okio.buffer
import okio.source

/**
 * 让 Coil 直接加载 MangaPageModel：调 document.getPageStream() 拿流交给解码器。
 *
 * 注意：Coil 2.x 的 ImageSource 第二个参数 context 是非空的（Coil 3 才改成 FileSystem），
 * 所以这里从 Options 里拿 context 传入，不能写 null。
 */
class MangaPageFetcher(
    private val data: MangaPageModel,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val inputStream = data.document.getPageStream(data.pageIndex)
        return SourceResult(
            source = ImageSource(
                source = inputStream.source().buffer(),
                context = options.context,
            ),
            mimeType = null,           // 交给 Coil 嗅探真实格式
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<MangaPageModel> {
        override fun create(
            data: MangaPageModel,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = MangaPageFetcher(data, options)
    }
}
