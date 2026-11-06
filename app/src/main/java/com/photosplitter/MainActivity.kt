package com.photosplitter

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.content.ContentValues
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import java.util.ArrayDeque

class MainActivity : Activity() {
    private lateinit var status: TextView
    private var selected: Bitmap? = null
    private val requestPick = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 50, 40, 30)
        }
        val title = TextView(this).apply { text = "PhotoSplitter"; textSize = 28f }
        status = TextView(this).apply {
            text = "Place separate prints on a dark, plain background. Photograph them with your phone's camera, then select the image here."
            textSize = 16f
        }
        val pick = Button(this).apply {
            text = "Choose batch photograph"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
                startActivityForResult(intent, requestPick)
            }
        }
        val split = Button(this).apply {
            text = "Detect, crop and save"
            setOnClickListener { process() }
        }
        layout.addView(title)
        layout.addView(status)
        layout.addView(pick)
        layout.addView(split)
        setContentView(layout)
    }

    @Deprecated("Legacy picker callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestPick || resultCode != RESULT_OK) return
        try {
            val uri = data?.data ?: return
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            selected = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            status.text = "Image selected. Ready to detect photographs."
        } catch (e: Exception) {
            status.text = "Could not open image: ${e.message}"
        }
    }

    private fun process() {
        val source = selected ?: run {
            Toast.makeText(this, "Choose an image first", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = "Processing..."
        Thread {
            try {
                val crops = PhotoDetector.detect(source)
                var saved = 0
                for (crop in crops) {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "PhotoSplitter_${System.currentTimeMillis()}_${saved}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoSplitter")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: continue
                    contentResolver.openOutputStream(uri)?.use { crop.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    saved++
                    crop.recycle()
                }
                runOnUiThread { status.text = "Saved $saved crops to Pictures/PhotoSplitter. Check every crop before discarding originals." }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Processing failed: ${e.message}" }
            }
        }.start()
    }
}
