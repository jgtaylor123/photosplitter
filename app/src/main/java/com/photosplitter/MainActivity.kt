package com.photosplitter

import android.app.Activity
import android.content.Intent
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.max

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var results: LinearLayout
    private val displayedCrops = mutableListOf<Bitmap>()
    private var selected: Bitmap? = null
    private var pendingCameraFile: File? = null
    private val requestPick = 100
    private val requestCamera = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCameraFile = savedInstanceState?.getString("cameraPath")?.let(::File)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 50, 40, 30)
        }
        val title = TextView(this).apply { text = "PhotoSplitter"; textSize = 28f }
        status = TextView(this).apply {
            text = "Arrange prints with gaps on a dark, plain background. Take a photo or select an existing image."
            textSize = 16f
        }
        val camera = Button(this).apply {
            text = "Take Photo"
            setOnClickListener { takePhoto() }
        }
        val pick = Button(this).apply {
            text = "Choose Existing Photo"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }, requestPick)
            }
        }
        val split = Button(this).apply {
            text = "Detect, crop and save"
            setOnClickListener { process() }
        }
        layout.addView(title)
        layout.addView(status)
        layout.addView(camera)
        layout.addView(pick)
        layout.addView(split)
        preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            contentDescription = "Captured or selected photograph preview"
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 24
            }
        }
        layout.addView(preview)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(results)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("cameraPath", pendingCameraFile?.absolutePath)
        super.onSaveInstanceState(outState)
    }

    private fun takePhoto() {
        try {
            val file = File.createTempFile("batch_", ".jpg", cacheDir)
            pendingCameraFile = file
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = android.content.ClipData.newUri(contentResolver, "PhotoSplitter capture", uri)
            }
            startActivityForResult(intent, requestCamera)
        } catch (e: Exception) {
            status.text = "Unable to launch camera: ${e.message}"
        }
    }

    @Deprecated("Legacy activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestPick && requestCode != requestCamera) return
        if (resultCode != RESULT_OK) {
            if (requestCode == requestCamera) pendingCameraFile?.delete()
            return
        }
        try {
            val uri: Uri = if (requestCode == requestCamera) {
                val file = pendingCameraFile ?: error("Camera file missing")
                if (file.length() == 0L) error("Camera returned an empty photo")
                Uri.fromFile(file)
            } else data?.data ?: error("No photo selected")
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Invalid image")
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > 3200) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: error("Could not decode image")
            val orientation = contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            }
            selected = if (!matrix.isIdentity) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true) else decoded
            if (selected !== decoded) decoded.recycle()
            clearResults()
            preview.setImageBitmap(selected)
            preview.visibility = View.VISIBLE
            status.text = "Photo ready. Tap Detect, crop and save."
        } catch (e: Exception) {
            status.text = "Could not load photo: ${e.message}"
        }
    }

    private fun clearResults() {
        results.removeAllViews()
        displayedCrops.forEach { if (!it.isRecycled) it.recycle() }
        displayedCrops.clear()
    }

    private fun process() {
        val source = selected ?: run {
            Toast.makeText(this, "Take or choose a photo first", Toast.LENGTH_SHORT).show()
            return
        }
        clearResults()
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
                    // Keep the bitmap for the on-screen crop preview.
                }
                runOnUiThread {
                    displayedCrops.addAll(crops)
                    val heading = TextView(this).apply {
                        text = "Detected photos (${crops.size})"
                        textSize = 22f
                        setPadding(0, 24, 0, 12)
                    }
                    results.addView(heading)
                    crops.forEachIndexed { index, crop ->
                        results.addView(TextView(this).apply { text = "Photo ${index + 1}"; textSize = 17f })
                        results.addView(ImageView(this).apply {
                            setImageBitmap(crop)
                            adjustViewBounds = true
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            contentDescription = "Cropped photo ${index + 1}"
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 20 }
                        })
                    }
                    status.text = "Saved $saved crops to Pictures/PhotoSplitter. Review each crop below before discarding originals."
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Processing failed: ${e.message}" }
            }
        }.start()
    }
}
