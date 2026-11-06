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
        // Connected bright regions can merge adjacent prints. Search for dark
        // gutters on either axis, not only horizontal ones.
        val separated = ArrayList<RectF>()
        fun splitAtGutters(box: RectF, depth: Int = 0) {
            if (depth >= 4) { separated.add(box); return }
            val left = (box.left / source.width * w).toInt().coerceIn(0, w - 1)
            val right = (box.right / source.width * w).toInt().coerceIn(left + 1, w)
            val top = (box.top / source.height * h).toInt().coerceIn(0, h - 1)
            val bottom = (box.bottom / source.height * h).toInt().coerceIn(top + 1, h)
            val bw = right - left
            val bh = bottom - top
            // Try the longer axis first; the example has two side-by-side cards.
            val axes = if (bw >= bh) listOf(true, false) else listOf(false, true)
            for (vertical in axes) {
                val length = if (vertical) bw else bh
                val cross = if (vertical) bh else bw
                if (length < max(40, (cross * 1.3f).toInt())) continue
                val minSide = max(12, length / 4)
                if (length <= minSide * 2) continue
                val counts = IntArray(length)
                for (i in 0 until length) {
                    var n = 0
                    for (j in 0 until cross) {
                        val x = if (vertical) left + i else left + j
                        val y = if (vertical) top + j else top + i
                        if (mask[y * w + x]) n++
                    }
                    counts[i] = n
                }
                var best = -1
                var lowest = cross
                for (i in minSide until length - minSide) {
                    val lo = max(0, i - 2)
                    val hi = min(length - 1, i + 2)
                    val avg = (lo..hi).sumOf { counts[it] } / (hi - lo + 1)
                    if (avg < lowest) { lowest = avg; best = i }
                }
                if (best < 0 || lowest > cross * 0.25f) continue
                fun bounds(start: Int, end: Int): RectF? {
                    var x0 = right; var x1 = left - 1
                    var y0 = bottom; var y1 = top - 1
                    for (i in start until end) for (j in 0 until cross) {
                        val x = if (vertical) left + i else left + j
                        val y = if (vertical) top + j else top + i
                        if (mask[y * w + x]) {
                            x0 = min(x0, x); x1 = max(x1, x)
                            y0 = min(y0, y); y1 = max(y1, y)
                        }
                    }
                    if (x1 - x0 < w / 12 || y1 - y0 < h / 12) return null
                    val pad = max(2, w / 150)
                    return RectF(max(0, x0 - pad).toFloat() / w * source.width,
                        max(0, y0 - pad).toFloat() / h * source.height,
                        min(w, x1 + 1 + pad).toFloat() / w * source.width,
                        min(h, y1 + 1 + pad).toFloat() / h * source.height)
                }
                val first = bounds(0, best)
                val second = bounds(best + 1, length)
                if (first != null && second != null) {
                    splitAtGutters(first, depth + 1)
                    splitAtGutters(second, depth + 1)
                    return
                }
            }
            separated.add(box)
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
