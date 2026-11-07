package com.photosplitter

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min

/** A fitted image that supports pinch zoom, pan and double-tap reset. */
private class ZoomImageView(context: Context, bitmap: Bitmap) : ImageView(context) {
    private val transform = Matrix()
    private val imageWidth = bitmap.width.toFloat()
    private val imageHeight = bitmap.height.toFloat()
    private var baseScale = 1f
    private var currentScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (currentScale * detector.scaleFactor).coerceIn(1f, 6f)
                val factor = newScale / currentScale
                currentScale = newScale
                transform.postScale(factor, factor, detector.focusX, detector.focusY)
                constrain()
                imageMatrix = transform
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (currentScale > 1.05f) {
                    fitImage()
                } else {
                    currentScale = 2.5f
                    transform.postScale(2.5f, 2.5f, e.x, e.y)
                    constrain()
                    imageMatrix = transform
                }
                return true
            }
        })

    init {
        setImageBitmap(bitmap)
        scaleType = ScaleType.MATRIX
        setBackgroundColor(Color.BLACK)
        contentDescription = "Zoomable photograph"
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) fitImage()
    }

    private fun fitImage() {
        if (width <= 0 || height <= 0) return
        baseScale = min(width / imageWidth, height / imageHeight)
        currentScale = 1f
        transform.reset()
        transform.postScale(baseScale, baseScale)
        transform.postTranslate((width - imageWidth * baseScale) / 2f,
            (height - imageHeight * baseScale) / 2f)
        imageMatrix = transform
    }

    private fun constrain() {
        val rect = RectF(0f, 0f, imageWidth, imageHeight)
        transform.mapRect(rect)
        val dx = if (rect.width() <= width) width / 2f - rect.centerX()
                 else when {
                     rect.left > 0f -> -rect.left
                     rect.right < width -> width - rect.right
                     else -> 0f
                 }
        val dy = if (rect.height() <= height) height / 2f - rect.centerY()
                 else when {
                     rect.top > 0f -> -rect.top
                     rect.bottom < height -> height - rect.bottom
                     else -> 0f
                 }
        transform.postTranslate(dx, dy)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                dragging = true
            }
            MotionEvent.ACTION_POINTER_DOWN -> dragging = false
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1 && dragging) {
                    if (currentScale > 1f) {
                        transform.postTranslate(event.x - lastX, event.y - lastY)
                        constrain()
                        imageMatrix = transform
                    }
                    lastX = event.x; lastY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                dragging = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}

fun showZoomPhoto(context: Context, bitmap: Bitmap, title: String): Dialog {
    val dialog = Dialog(context, android.R.style.Theme_Material_NoActionBar_Fullscreen)
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.BLACK)
    }
    val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(16, 8, 16, 8)
    }
    val heading = TextView(context).apply {
        text = title
        textSize = 18f
        setTextColor(Color.WHITE)
        maxLines = 2
    }
    header.addView(heading, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    val close = Button(context).apply {
        text = "Close"
        setOnClickListener { dialog.dismiss() }
    }
    header.addView(close)
    container.addView(header)
    container.addView(ZoomImageView(context, bitmap),
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    dialog.setContentView(container)
    dialog.show()
    return dialog
}
