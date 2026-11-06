package com.photosplitter

import android.app.Activity
import android.content.Intent
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
    private lateinit var editor: CropEditor
    private lateinit var saveButton: Button
    private lateinit var results: LinearLayout
    private val displayedCrops = mutableListOf<Bitmap>()
    private var selected: Bitmap? = null
    private var pendingCameraFile: File? = null
    private val requestPick = 100
    private val requestCamera = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCameraFile = savedInstanceState?.getString("cameraPath")?.let(::File)
        window.statusBarColor = Color.rgb(17, 24, 39)
        window.navigationBarColor = Color.rgb(17, 24, 39)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 44, 32, 44)
            setBackgroundColor(Color.rgb(248, 251, 255))
        }
        val title = TextView(this).apply {
            text = "PhotoSplitter"
            textSize = 30f
            setTextColor(Color.rgb(17, 24, 39))
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(8, 16, 8, 20)
        }
        status = TextView(this).apply {
            text = "Arrange prints with gaps on a dark, plain background. Take a photo or select an existing image."
            textSize = 16f
            setTextColor(Color.rgb(55, 65, 81))
            setPadding(12, 16, 12, 24)
            background = rounded(Color.WHITE, 16, Color.rgb(219, 234, 254))
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
            text = "Detect Photos"
            setOnClickListener { process() }
        }
        listOf(camera, pick, split).forEachIndexed { i, button -> styleButton(button, i == 0) }
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
        editor = CropEditor(this).apply {
            visibility = View.GONE
            onChanged = { showCrops() }
        }
        layout.addView(editor)
        val edit = Button(this).apply {
            text = "Edit Crops"
            setOnClickListener {
                if (selected == null) return@setOnClickListener
                editor.editing = !editor.editing
                editor.visibility = View.VISIBLE
                preview.visibility = View.GONE
                text = if (editor.editing) "Finish Editing" else "Edit Crops"
                editor.invalidate()
            }
        }
        styleButton(edit)
        layout.addView(edit)
        layout.addView(Button(this).apply { text = "Add Crop Box"; setOnClickListener { editor.addBox() }; styleButton(this) })
        layout.addView(Button(this).apply { text = "Remove Selected Box"; setOnClickListener { editor.removeSelected() }; styleButton(this) })
        saveButton = Button(this).apply {
            text = "Save Crops"
            setOnClickListener { saveCrops() }
        }
        styleButton(saveButton, true)
        layout.addView(saveButton)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(8, 18, 8, 12) }
        layout.addView(results)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius * resources.displayMetrics.density
            if (stroke != null) setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), stroke)
        }

    private fun styleButton(button: Button, primary: Boolean = false) {
        button.isAllCaps = false
        button.textSize = 16f
        button.setTypeface(null, android.graphics.Typeface.BOLD)
        button.setTextColor(if (primary) Color.WHITE else Color.rgb(30, 58, 138))
        button.background = rounded(
            if (primary) Color.rgb(37, 99, 235) else Color.rgb(239, 246, 255),
            12,
            if (primary) null else Color.rgb(191, 219, 254)
        )
        button.setPadding(18, 12, 18, 12)
        button.layoutParams = LinearLayout.LayoutParams(-1, (52 * resources.displayMetrics.density).toInt()).apply {
            topMargin = (10 * resources.displayMetrics.density).toInt()
        }
        button.elevation = 3f * resources.displayMetrics.density
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
            editor.photo = selected
            editor.setBoxes(emptyList())
            editor.visibility = View.GONE
            preview.setImageBitmap(selected)
            preview.visibility = View.VISIBLE
            status.text = "Photo ready. Tap Detect Photos to preview the individual crops."
        } catch (e: Exception) {
            status.text = "Could not load photo: ${e.message}"
        }
    }

    private fun clearResults() {
        results.removeAllViews()
        displayedCrops.forEach { if (!it.isRecycled) it.recycle() }
        displayedCrops.clear()
    }

    private fun showCrops() {
        val source = selected ?: return
        results.removeAllViews()
        results.addView(TextView(this).apply {
            text = "Detected photos (${editor.boxes.size})"
            textSize = 22f
            setPadding(0, 24, 0, 12)
        })
        editor.boxes.forEachIndexed { index, box ->
            results.addView(TextView(this).apply { text = "Photo ${index + 1}"; textSize = 17f })
            results.addView(ImageView(this).apply {
                setImageBitmap(PhotoDetector.crop(source, box))
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 20 }
            })
        }
    }

    private fun process() {
        val source = selected ?: run {
            Toast.makeText(this, "Take or choose a photo first", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = "Detecting photos..."
        Thread {
            try {
                val boxes = PhotoDetector.detectBoxes(source)
                runOnUiThread {
                    editor.photo = source
                    editor.setBoxes(boxes)
                    editor.visibility = View.VISIBLE
                    preview.visibility = View.GONE
                    status.text = "Found ${boxes.size} photos. Tap Edit Crops to adjust boxes, then Save Crops."
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Detection failed: ${e.message}" }
            }
        }.start()
    }

    private fun saveCrops() {
        val source = selected ?: return
        val boxes = editor.boxes.map { android.graphics.RectF(it) }
        if (boxes.isEmpty()) {
            Toast.makeText(this, "No crop boxes to save", Toast.LENGTH_SHORT).show()
            return
        }
        status.text = "Saving..."
        Thread {
            var saved = 0
            try {
                for (box in boxes) {
                    val crop = PhotoDetector.crop(source, box)
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "PhotoSplitter_${System.currentTimeMillis()}_${saved}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoSplitter")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use {
                            crop.compress(Bitmap.CompressFormat.JPEG, 95, it)
                            saved++
                        }
                    }
                    crop.recycle()
                }
                runOnUiThread { status.text = "Saved $saved crops to Pictures/PhotoSplitter." }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Save failed: ${e.message}" }
            }
        }.start()
    }
}
