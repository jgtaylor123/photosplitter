package com.photosplitter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Boxes use source-image pixel coordinates. Drag inside to move, corners to resize. */
class CropEditor(context: Context) : View(context) {
    var photo: Bitmap? = null
        set(value) { field = value; invalidate() }
    val boxes = mutableListOf<RectF>()
    var editing = false
    var onChanged: (() -> Unit)? = null
    private var active = -1
    private var mode = -1
    private var previousX = 0f
    private var previousY = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density

    fun setBoxes(items: List<RectF>) {
        boxes.clear(); boxes.addAll(items.map { RectF(it) }); invalidate(); onChanged?.invoke()
    }
    fun addBox() {
        val bmp = photo ?: return
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        boxes.add(RectF(w * .3f, h * .3f, w * .7f, h * .7f))
        active = boxes.lastIndex; invalidate(); onChanged?.invoke()
    }
    fun removeSelected() {
        if (active in boxes.indices) { boxes.removeAt(active); active = -1; invalidate(); onChanged?.invoke() }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val bmp = photo
        val height = if (bmp != null && bmp.width > 0) max(1, (width.toFloat() * bmp.height / bmp.width).toInt()) else width
        setMeasuredDimension(width, height)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = photo ?: return
        canvas.drawBitmap(bmp, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint)
        val sx = width.toFloat() / bmp.width; val sy = height.toFloat() / bmp.height
        boxes.forEachIndexed { index, r ->
            val rect = RectF(r.left * sx, r.top * sy, r.right * sx, r.bottom * sy)
            paint.color = if (index == active && editing) Color.YELLOW else Color.CYAN
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f * density
            canvas.drawRect(rect, paint)
            paint.style = Paint.Style.FILL; paint.textSize = 18f * density
            canvas.drawText("${index + 1}", rect.left + 5 * density, rect.top + 20 * density, paint)
            if (editing) for (x in listOf(rect.left, rect.right)) for (y in listOf(rect.top, rect.bottom)) {
                canvas.drawCircle(x, y, 8f * density, paint)
            }
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bmp = photo ?: return false
        if (!editing) return false
        val x = event.x.coerceIn(0f, width.toFloat()) * bmp.width / width
        val y = event.y.coerceIn(0f, height.toFloat()) * bmp.height / height
        val radius = 28f * density * bmp.width / width
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = -1; mode = -1
                for (i in boxes.indices.reversed()) {
                    val b = boxes[i]
                    val corners = listOf(b.left to b.top, b.right to b.top, b.left to b.bottom, b.right to b.bottom)
                    val corner = corners.indexOfFirst { abs(x - it.first) < radius && abs(y - it.second) < radius }
                    if (corner >= 0) { active = i; mode = corner; break }
                    if (b.contains(x, y)) { active = i; mode = 4; break }
                }
                previousX = x; previousY = y; invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (active !in boxes.indices || mode < 0) return true
                val b = boxes[active]
                val minSize = 20f
                if (mode == 4) {
                    val dx = (x - previousX).coerceIn(-b.left, bmp.width - b.right)
                    val dy = (y - previousY).coerceIn(-b.top, bmp.height - b.bottom)
                    b.offset(dx, dy)
                } else {
                    if (mode == 0 || mode == 2) b.left = x.coerceIn(0f, b.right - minSize)
                    else b.right = x.coerceIn(b.left + minSize, bmp.width.toFloat())
                    if (mode == 0 || mode == 1) b.top = y.coerceIn(0f, b.bottom - minSize)
                    else b.bottom = y.coerceIn(b.top + minSize, bmp.height.toFloat())
                }
                previousX = x; previousY = y; invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> { onChanged?.invoke(); performClick(); return true }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
