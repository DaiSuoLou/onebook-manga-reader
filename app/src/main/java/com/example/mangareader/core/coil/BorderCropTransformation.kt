package com.example.mangareader.core.coil

import android.graphics.Bitmap
import android.graphics.Color
import coil.transform.Transformation
import coil.size.Size

/**
 * 裁掉漫画页四周均匀的白边/黑边。
 *
 * 以左上角像素作为背景色，从每条边向内收缩，直到遇到「与背景色差异超过容差」的像素为止。
 * 只扫边、不扫全图，复杂度 O(宽 + 高)，对大图也很快。
 *
 * 没有裁到任何边时直接返回原图（不创建无意义的新 Bitmap，避免额外的内存拷贝）。
 */
class BorderCropTransformation(
    /** 每个通道允许的色差（0-255），越大裁得越激进 */
    private val tolerance: Int = 24,
) : Transformation {

    override val cacheKey: String
        get() = "${BorderCropTransformation::class.java.name}:$tolerance"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = input.width
        val height = input.height
        if (width == 0 || height == 0) return input

        val bg = input.getPixel(0, 0)

        var left = 0
        while (left < width && columnIsBackground(input, left, height, bg)) left++

        var right = width - 1
        while (right > left && columnIsBackground(input, right, height, bg)) right--

        var top = 0
        while (top < height && rowIsBackground(input, top, width, bg)) top++

        var bottom = height - 1
        while (bottom > top && rowIsBackground(input, bottom, width, bg)) bottom--

        if (left == 0 && right == width - 1 && top == 0 && bottom == height - 1) {
            return input
        }
        return Bitmap.createBitmap(input, left, top, right - left + 1, bottom - top + 1)
    }

    private fun columnIsBackground(bmp: Bitmap, x: Int, height: Int, bg: Int): Boolean {
        val step = maxOf(1, height / 200)
        var y = 0
        while (y < height) {
            if (!similar(bmp.getPixel(x, y), bg, tolerance)) return false
            y += step
        }
        return true
    }

    private fun rowIsBackground(bmp: Bitmap, y: Int, width: Int, bg: Int): Boolean {
        val step = maxOf(1, width / 200)
        var x = 0
        while (x < width) {
            if (!similar(bmp.getPixel(x, y), bg, tolerance)) return false
            x += step
        }
        return true
    }

    private fun similar(a: Int, b: Int, tol: Int): Boolean {
        return abs(Color.red(a) - Color.red(b)) <= tol &&
            abs(Color.green(a) - Color.green(b)) <= tol &&
            abs(Color.blue(a) - Color.blue(b)) <= tol
    }

    private fun abs(v: Int) = if (v < 0) -v else v
}
