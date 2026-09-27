package com.example.mangareader.core.coil

import android.content.Context
import coil.ImageLoader
import coil.request.ImageRequest
import com.example.mangareader.core.model.MangaPageModel

/**
 * Coil 装配：注册 MangaPageFetcher，并为自定义 data 类型指定缓存 key。
 *
 * 为什么必须显式指定 key：MangaPageModel 不是 String/Uri/File 这类 Coil 内建类型，
 * 默认的内存缓存 key 会退化成 data.toString()（含对象地址），同一页反复加载命中不到缓存，
 * 且会因为 document 实例变化而泄漏旧条目。这里统一用 page.identifier。
 */
fun ImageLoader.Builder.addMangaPageSupport(): ImageLoader.Builder =
    components { add(MangaPageFetcher.Factory()) }

fun mangaPageRequest(context: Context, page: MangaPageModel): ImageRequest =
    ImageRequest.Builder(context)
        .data(page)
        .memoryCacheKey(page.identifier)
        .diskCacheKey(page.identifier)
        .build()
