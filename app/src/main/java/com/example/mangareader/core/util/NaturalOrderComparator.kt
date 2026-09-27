package com.example.mangareader.core.util

import java.util.regex.Pattern

/**
 * 自然排序比较器：把字符串切成「数字段 / 非数字段」交替的块，
 * 数字段按数值比较（支持任意长度，用 BigInteger），非数字段按大小写不敏感比较。
 *
 * 效果："第2话" < "第10话"，"page 2.jpg" < "page 10.jpg"。
 */
object NaturalOrderComparator : Comparator<String> {

    private val SPLIT_PATTERN = Pattern.compile("(?<=\\D)(?=\\d)|(?<=\\d)(?=\\D)")

    override fun compare(s1: String, s2: String): Int {
        val chunks1 = SPLIT_PATTERN.split(s1)
        val chunks2 = SPLIT_PATTERN.split(s2)
        val minLength = minOf(chunks1.size, chunks2.size)

        for (i in 0 until minLength) {
            val c1 = chunks1[i]
            val c2 = chunks2[i]

            // 如果两段都是纯数字，按数值大小比较
            val isDigit1 = c1.isNotEmpty() && c1[0].isDigit()
            val isDigit2 = c2.isNotEmpty() && c2[0].isDigit()

            val cmp = if (isDigit1 && isDigit2) {
                try {
                    c1.toBigInteger().compareTo(c2.toBigInteger())
                } catch (_: NumberFormatException) {
                    c1.compareTo(c2, ignoreCase = true)
                }
            } else {
                c1.compareTo(c2, ignoreCase = true)
            }

            if (cmp != 0) return cmp
        }
        return chunks1.size.compareTo(chunks2.size)
    }
}

/** 按文件名自然排序的便捷扩展 */
fun <T> Iterable<T>.naturalSortedBy(selector: (T) -> String): List<T> =
    sortedWith(compareBy(NaturalOrderComparator, selector))
