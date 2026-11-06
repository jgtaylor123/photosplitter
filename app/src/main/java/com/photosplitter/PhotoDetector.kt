package com.photosplitter

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.util.ArrayDeque

/**
 * Experimental V1 segmentation: assumes light prints separated by a dark background.
 * Detects connected bright regions at low resolution and returns rectangular crops.
 * No perspective correction or manual crop editing yet.
 */
object PhotoDetector {
    fun detect(source: Bitmap): List<Bitmap> {
        val scale = min(1f, 600f / max(source.width, source.height))
        val w = max(1, (source.width * scale).toInt())
        val h = max(1, (source.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        small.recycle()
        // Estimate dark tabletop luminance from the image border.
        val border = ArrayList<Int>()
        fun lum(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        for (x in 0 until w) {
            border.add(lum(pixels[x]))
            border.add(lum(pixels[(h - 1) * w + x]))
        }
        for (y in 0 until h) {
            border.add(lum(pixels[y * w]))
            border.add(lum(pixels[y * w + w - 1]))
        }
        border.sort()
        val background = border[border.size / 2]
        val threshold = min(245, max(35, background + 35))
        val mask = BooleanArray(w * h) { lum(pixels[it]) > threshold }
        val visited = BooleanArray(w * h)
        val boxes = ArrayList<IntArray>()
        val queue = IntArray(w * h)
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minX = w; var minY = h; var maxX = 0; var maxY = 0; var count = 0
            while (head < tail) {
                val p = queue[head++]
                val x = p % w; val y = p / w
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
                count++
                val neighbors = intArrayOf(
                    if (x > 0) p - 1 else -1,
                    if (x + 1 < w) p + 1 else -1,
                    if (y > 0) p - w else -1,
                    if (y + 1 < h) p + w else -1
                )
                for (n in neighbors) {
                    if (n >= 0 && mask[n] && !visited[n]) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            if (count > w * h / 350 && bw > w / 25 && bh > h / 25 &&
                count.toFloat() / (bw * bh) > 0.45f) {
                boxes.add(intArrayOf(minX, minY, maxX + 1, maxY + 1))
            }
        }
        return boxes.sortedWith(compareBy({ it[1] }, { it[0] })).map { b ->
            val x = (b[0].toFloat() / w * source.width).toInt().coerceIn(0, source.width - 1)
            val y = (b[1].toFloat() / h * source.height).toInt().coerceIn(0, source.height - 1)
            val right = (b[2].toFloat() / w * source.width).toInt().coerceIn(x + 1, source.width)
            val bottom = (b[3].toFloat() / h * source.height).toInt().coerceIn(y + 1, source.height)
            Bitmap.createBitmap(source, x, y, right - x, bottom - y)
        }
    }
}
