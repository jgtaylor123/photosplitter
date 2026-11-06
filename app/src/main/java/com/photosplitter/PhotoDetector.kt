package com.photosplitter

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

object PhotoDetector {
    fun detectBoxes(source: Bitmap): List<RectF> {
        val scale = min(1f, 700f / max(source.width, source.height))
        val w = max(1, (source.width * scale).toInt())
        val h = max(1, (source.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== source) small.recycle()

        // Detect pale paper rather than merely bright pixels. Reddish wood and
        // its reflections are bright but generally have much less green/blue.
        val mask = BooleanArray(w * h) { i ->
            val c = pixels[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            r >= 125 && g >= 110 && b >= 85 &&
                g >= r * 0.72f && b >= r * 0.54f &&
                (r + g + b) >= 390
        }

        // Bridge narrow printed details and small shadows without joining
        // different cards across the wooden gaps.
        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val boxes = ArrayList<RectF>()
        val minArea = w * h / 180
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minX = w
            var minY = h
            var maxX = 0
            var maxY = 0
            var count = 0
            while (head < tail) {
                val p = queue[head++]
                val x = p % w
                val y = p / w
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
                count++
                if (x > 0 && mask[p - 1] && !visited[p - 1]) {
                    visited[p - 1] = true; queue[tail++] = p - 1
                }
                if (x + 1 < w && mask[p + 1] && !visited[p + 1]) {
                    visited[p + 1] = true; queue[tail++] = p + 1
                }
                if (y > 0 && mask[p - w] && !visited[p - w]) {
                    visited[p - w] = true; queue[tail++] = p - w
                }
                if (y + 1 < h && mask[p + w] && !visited[p + w]) {
                    visited[p + w] = true; queue[tail++] = p + w
                }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val aspect = bw.toFloat() / bh
            val coverage = count.toFloat() / (bw * bh)
            if (count < minArea || bw < w / 12 || bh < h / 12 ||
                aspect < 0.40f || aspect > 2.5f || coverage < 0.36f) continue

            // Preserve the scalloped outer edge by padding the pale-paper region.
            val pad = max(3, min(bw, bh) / 35)
            boxes.add(RectF(
                max(0, minX - pad).toFloat() / w * source.width,
                max(0, minY - pad).toFloat() / h * source.height,
                min(w, maxX + 1 + pad).toFloat() / w * source.width,
                min(h, maxY + 1 + pad).toFloat() / h * source.height
            ))
        }
        return boxes.sortedWith(compareBy({ it.top }, { it.left }))
    }

    fun crop(source: Bitmap, box: RectF): Bitmap {
        val left = box.left.toInt().coerceIn(0, source.width - 1)
        val top = box.top.toInt().coerceIn(0, source.height - 1)
        val right = box.right.toInt().coerceIn(left + 1, source.width)
        val bottom = box.bottom.toInt().coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    fun detect(source: Bitmap): List<Bitmap> = detectBoxes(source).map { crop(source, it) }
}
