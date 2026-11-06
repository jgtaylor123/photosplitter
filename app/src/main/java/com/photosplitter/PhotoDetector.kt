package com.photosplitter

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

object PhotoDetector {
    fun detectBoxes(source: Bitmap): List<RectF> {
        val scale = min(1f, 600f / max(source.width, source.height))
        val w = max(1, (source.width * scale).toInt())
        val h = max(1, (source.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(source, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== source) small.recycle()
        fun lum(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
        val border = ArrayList<Int>()
        for (x in 0 until w) { border.add(lum(pixels[x])); border.add(lum(pixels[(h - 1) * w + x])) }
        for (y in 0 until h) { border.add(lum(pixels[y * w])); border.add(lum(pixels[y * w + w - 1])) }
        border.sort()
        val threshold = min(245, max(35, border[border.size / 2] + 35))
        val mask = BooleanArray(w * h) { lum(pixels[it]) > threshold }
        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val boxes = ArrayList<RectF>()
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var head = 0; var tail = 0
            queue[tail++] = start; visited[start] = true
            var minX = w; var minY = h; var maxX = 0; var maxY = 0; var count = 0
            while (head < tail) {
                val p = queue[head++]; val x = p % w; val y = p / w
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y); count++
                val neighbors = intArrayOf(if (x > 0) p - 1 else -1, if (x + 1 < w) p + 1 else -1,
                    if (y > 0) p - w else -1, if (y + 1 < h) p + w else -1)
                for (n in neighbors) if (n >= 0 && mask[n] && !visited[n]) {
                    visited[n] = true; queue[tail++] = n
                }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            if (count > w * h / 350 && bw > w / 25 && bh > h / 25 &&
                count.toFloat() / (bw * bh) > 0.45f) {
                boxes.add(RectF(minX.toFloat() / w * source.width, minY.toFloat() / h * source.height,
                    (maxX + 1).toFloat() / w * source.width, (maxY + 1).toFloat() / h * source.height))
            }
        }
        // A thin bridge of glare or a bright card edge can join two prints into
        // one connected component. Split at a genuine dark horizontal gutter.
        val separated = ArrayList<RectF>()
        fun splitAtGutters(box: RectF, depth: Int = 0) {
            if (depth >= 3) { separated.add(box); return }
            val left = (box.left / source.width * w).toInt().coerceIn(0, w - 1)
            val right = (box.right / source.width * w).toInt().coerceIn(left + 1, w)
            val top = (box.top / source.height * h).toInt().coerceIn(0, h - 1)
            val bottom = (box.bottom / source.height * h).toInt().coerceIn(top + 1, h)
            val bw = right - left
            val bh = bottom - top
            if (bh < h / 5 || bh < bw * 1.35f) { separated.add(box); return }
            val counts = IntArray(bh)
            for (y in top until bottom) {
                var count = 0
                for (x in left until right) if (mask[y * w + x]) count++
                counts[y - top] = count
            }
            // Require a low-occupancy band away from both ends, and a strong
            // foreground area on each side of the proposed split.
            val minSide = max(h / 12, bh / 5)
            var best = -1
            var bestCount = bw
            for (i in minSide until bh - minSide) {
                val local = (max(0, i - 2)..min(bh - 1, i + 2)).sumOf { counts[it] } / (min(bh - 1, i + 2) - max(0, i - 2) + 1)
                if (local < bestCount) { bestCount = local; best = i }
            }
            if (best < 0 || bestCount > bw * 0.22f) { separated.add(box); return }
            fun foregroundBounds(y0: Int, y1: Int): RectF? {
                var x0 = right; var x1 = left; var yy0 = y1; var yy1 = y0
                for (y in y0 until y1) for (x in left until right) {
                    if (mask[y * w + x]) {
                        x0 = min(x0, x); x1 = max(x1, x)
                        yy0 = min(yy0, y); yy1 = max(yy1, y)
                    }
                }
                if (x1 <= x0 || yy1 <= yy0 || x1 - x0 < w / 10 || yy1 - yy0 < h / 12) return null
                val pad = max(2, w / 150)
                return RectF(max(0, x0 - pad).toFloat() / w * source.width,
                    max(0, yy0 - pad).toFloat() / h * source.height,
                    min(w, x1 + 1 + pad).toFloat() / w * source.width,
                    min(h, yy1 + 1 + pad).toFloat() / h * source.height)
            }
            val upper = foregroundBounds(top, top + best)
            val lower = foregroundBounds(top + best + 1, bottom)
            if (upper == null || lower == null) { separated.add(box); return }
            splitAtGutters(upper, depth + 1)
            splitAtGutters(lower, depth + 1)
        }
        boxes.forEach { splitAtGutters(it) }
        return separated.sortedWith(compareBy({ it.top }, { it.left }))
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
