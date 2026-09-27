package com.example.mangareader.ui.reader.model

/**
 * 屏幕上单次展示的“展页”（Spread）模型
 * 单页模式下 rightPageIndex 为 null
 */
data class MangaSpread(
    val leftPageIndex: Int?,
    val rightPageIndex: Int?,
) {
    /** 该展页包含的所有页码，按「阅读顺序」排列（RTL 时右页在前） */
    fun orderedIndices(isRtl: Boolean): List<Int> =
        if (isRtl) listOfNotNull(rightPageIndex, leftPageIndex)
        else listOfNotNull(leftPageIndex, rightPageIndex)

    companion object {
        fun single(pageIndex: Int, isRtl: Boolean = false) =
            if (isRtl) MangaSpread(leftPageIndex = null, rightPageIndex = pageIndex)
            else MangaSpread(leftPageIndex = pageIndex, rightPageIndex = null)
    }
}

object SpreadHelper {

    /**
     * 将线性页码切分为双页展页列表
     * @param totalPages 总页数
     * @param isDualPage 是否开启双页并排
     * @param isRtl 是否日漫从右往左
     * @param firstPageCover 是否保持第 0 页（封面）单页独占
     * @param manualSpread 手动合并跨页：不为 null 时，只有该 spread 是双页，其余页保持单页
     */
    fun createSpreads(
        totalPages: Int,
        isDualPage: Boolean,
        isRtl: Boolean,
        firstPageCover: Boolean = true,
        manualSpread: MangaSpread? = null,
    ): List<MangaSpread> {
        manualSpread?.let { return createManualSpreads(totalPages, it, isRtl) }

        if (!isDualPage || totalPages <= 1) {
            return (0 until totalPages).map { MangaSpread(leftPageIndex = it, rightPageIndex = null) }
        }

        val spreads = mutableListOf<MangaSpread>()
        var currentIndex = 0

        // 封面单独成页
        if (firstPageCover) {
            spreads.add(MangaSpread.single(0, isRtl))
            currentIndex = 1
        }

        // 双页成组
        while (currentIndex < totalPages) {
            val p1 = currentIndex
            val p2 = if (currentIndex + 1 < totalPages) currentIndex + 1 else null

            if (p2 == null) {
                // 最后一页落单
                spreads.add(MangaSpread.single(p1, isRtl))
            } else {
                // 日漫 RTL: 较早的页码放右边，较晚的放左边
                // 普通 LTR: 较早的页码放左边，较晚的放右边
                if (isRtl) {
                    spreads.add(MangaSpread(leftPageIndex = p2, rightPageIndex = p1))
                } else {
                    spreads.add(MangaSpread(leftPageIndex = p1, rightPageIndex = p2))
                }
            }
            currentIndex += 2
        }
        return spreads
    }

    /**
     * 手动合并跨页：只在 [manual] 指定位置显示双页，其他页全部单页。
     * 这样用户可以临时把任意相邻两页拼成完整大图，像真实漫画书的跨页一样。
     */
    private fun createManualSpreads(
        totalPages: Int,
        manual: MangaSpread,
        isRtl: Boolean,
    ): List<MangaSpread> {
        if (totalPages <= 0) return emptyList()
        val spreads = mutableListOf<MangaSpread>()
        val left = manual.leftPageIndex
        val right = manual.rightPageIndex
        val startPage = minOf(left ?: Int.MAX_VALUE, right ?: Int.MAX_VALUE)
        val endPage = maxOf(left ?: -1, right ?: -1)

        // manual spread 之前的页保持单页
        for (i in 0 until minOf(startPage, totalPages)) {
            spreads.add(MangaSpread.single(i, isRtl))
        }
        // 手动合并的跨页（边界保护）
        when {
            left != null && right != null && left in 0 until totalPages && right in 0 until totalPages -> {
                spreads.add(MangaSpread(leftPageIndex = left, rightPageIndex = right))
            }
            left != null && left in 0 until totalPages -> {
                spreads.add(MangaSpread.single(left, isRtl))
            }
            right != null && right in 0 until totalPages -> {
                spreads.add(MangaSpread.single(right, isRtl))
            }
        }
        // manual spread 之后的页保持单页
        for (i in maxOf(endPage + 1, 0) until totalPages) {
            spreads.add(MangaSpread.single(i, isRtl))
        }
        return spreads
    }
}

/**
 * 页码 -> 展页下标，用于把 Pager 定位到某一页。
 *
 * 注意：这是**顶层**扩展函数（不是 [SpreadHelper] 的成员），所以可以直接
 * `import com.example.mangareader.ui.reader.model.spreadIndexFor` 后调用。
 * 若写成 SpreadHelper 的成员扩展，调用方必须 `with(SpreadHelper) { ... }`，容易踩坑。
 */
fun List<MangaSpread>.spreadIndexFor(pageIndex: Int): Int =
    indexOfFirst { it.leftPageIndex == pageIndex || it.rightPageIndex == pageIndex }
        .takeIf { it >= 0 } ?: 0
