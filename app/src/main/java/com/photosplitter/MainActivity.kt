package com.photosplitter

import android.app.Activity
import android.content.Intent
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.CheckBox
import android.widget.EditText
import android.text.InputType
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
    private lateinit var retakeButton: Button
    private lateinit var preview: ImageView
    private lateinit var editor: CropEditor
    private lateinit var saveButton: Button
    private lateinit var scroll: ScrollView
    private var zoomDialog: android.app.Dialog? = null
    private lateinit var results: LinearLayout
    private lateinit var collectionField: EditText
    private lateinit var batchField: EditText
    private lateinit var batchFolderToggle: CheckBox
    private val displayedCrops = mutableListOf<Bitmap>()
    private val photoTitles = mutableListOf<String>()
    private val photoPeople = mutableListOf<String>()
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
        listOf(camera, pick).forEachIndexed { i, button -> styleButton(button, i == 0) }
        layout.addView(title)
        layout.addView(status)
        val prefs = getSharedPreferences("photo_settings", MODE_PRIVATE)
        layout.addView(TextView(this).apply {
            text = "Collection folder"
            textSize = 17f
            setPadding(0, 16, 0, 4)
        })
        collectionField = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(prefs.getString("collection", "PhotoSplitter") ?: "PhotoSplitter")
            hint = "PhotoSplitter"
        }
        layout.addView(collectionField)
        layout.addView(TextView(this).apply {
            text = "Batch label (optional)"
            textSize = 17f
            setPadding(0, 12, 0, 4)
        })
        batchField = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = "e.g. Christmas 2025"
        }
        layout.addView(batchField)
        batchFolderToggle = CheckBox(this).apply {
            text = "Save each labelled batch in its own subfolder"
            isChecked = prefs.getBoolean("batch_subfolder", true)
        }
        layout.addView(batchFolderToggle)
        layout.addView(camera)
        layout.addView(pick)
        retakeButton = Button(this).apply {
            text = "Retake Photo"
            visibility = View.GONE
            setOnClickListener { takePhoto() }
        }
        styleButton(retakeButton)
        layout.addView(retakeButton)
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
        results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 18, 8, 12)
        }
        // Results must appear immediately after the source preview, before edit/save controls.
        layout.addView(results)
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
        scroll = ScrollView(this).apply { addView(layout) }
        setContentView(scroll)
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
            photoTitles.clear()
            photoPeople.clear()
            retakeButton.visibility = if (requestCode == requestCamera) View.VISIBLE else View.GONE
            editor.editing = false
            editor.photo = selected
            editor.setBoxes(emptyList())
            editor.visibility = View.GONE
            preview.setImageBitmap(selected)
            preview.visibility = View.VISIBLE
            process()
        } catch (e: Exception) {
            status.text = "Could not load photo: ${e.message}"
        }
    }

    private fun clearResults() {
        zoomDialog?.dismiss()
        zoomDialog = null
        results.removeAllViews()
        displayedCrops.forEach { if (!it.isRecycled) it.recycle() }
        displayedCrops.clear()
    }

    private fun showCrops() {
        val source = selected ?: return
        clearResults()
        while (photoTitles.size < editor.boxes.size) photoTitles.add("")
        while (photoPeople.size < editor.boxes.size) photoPeople.add("")
        while (photoTitles.size > editor.boxes.size) photoTitles.removeAt(photoTitles.lastIndex)
        while (photoPeople.size > editor.boxes.size) photoPeople.removeAt(photoPeople.lastIndex)
        results.addView(TextView(this).apply {
            text = "Detected photos (${editor.boxes.size})"
            textSize = 22f
            setPadding(0, 24, 0, 12)
        })
        editor.boxes.forEachIndexed { index, box ->
            results.addView(TextView(this).apply { text = "Photo ${index + 1}"; textSize = 17f })
            results.addView(EditText(this).apply {
                hint = "Photo title (e.g. Howard in Paris)"
                setSingleLine(true)
                setText(photoTitles[index])
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        photoTitles[index] = s?.toString() ?: ""
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            })
            results.addView(EditText(this).apply {
                hint = "People (comma-separated, e.g. Howard, Margaret)"
                setSingleLine(true)
                setText(photoPeople[index])
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        photoPeople[index] = s?.toString() ?: ""
                    }
                    override fun afterTextChanged(s: android.text.Editable?) {}
                })
            })
            val cropped = PhotoDetector.crop(source, box)
            displayedCrops.add(cropped)
            results.addView(ImageView(this).apply {
                setImageBitmap(cropped)
                contentDescription = "Open photo ${index + 1} to zoom"
                setOnClickListener {
                    zoomDialog?.dismiss()
                    zoomDialog = showZoomPhoto(this@MainActivity, cropped,
                        photoTitles.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "Photo ${index + 1}")
                }
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = LinearLayout.LayoutParams(-1, (240 * resources.displayMetrics.density).toInt()).apply { bottomMargin = 20 }
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
                val boxes = PhotoDetector.detectQuads(source)
                runOnUiThread {
                    if (selected !== source) return@runOnUiThread
                    editor.photo = source
                    editor.setBoxes(boxes)
                    // Always show a visible result, including when detection finds nothing.
                    if (boxes.isEmpty()) {
                        results.addView(TextView(this).apply {
                            text = "No photos detected. Tap Add Crop Box, then Edit Crops to mark them manually."
                            textSize = 16f
                        })
                    }
                    editor.visibility = View.VISIBLE
                    preview.visibility = View.GONE
                    status.text = "Found ${boxes.size} photos. Review crops directly below the original image."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (selected === source) status.text = "Detection failed: ${e.message}"
                }
            }
        }.start()
    }

    private fun safeFolderName(value: String, fallback: String): String {
        val forbidden = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
        val clean = value.trim()
            .map { if (it in forbidden || it.code < 32) '_' else it }
            .joinToString("")
            .trim('.', ' ')
            .take(90)
        return if (clean.isBlank()) fallback else clean
    }

    // Add a caption below the photograph, never over the original pixels.
    private fun withCaption(photo: Bitmap, title: String): Bitmap {
        if (title.isBlank()) return photo
        val fontSize = (photo.width * 0.045f).coerceIn(18f, 64f)
        val inset = (photo.width * 0.04f).coerceAtLeast(12f)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(25, 35, 55)
            textSize = fontSize
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        }
        val layout = StaticLayout.Builder.obtain(
            title, 0, title.length, textPaint,
            (photo.width - 2f * inset).toInt().coerceAtLeast(1)
        ).setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
        val padY = (fontSize * 0.7f).toInt()
        val extraHeight = layout.height + 2 * padY
        val output = Bitmap.createBitmap(photo.width, photo.height + extraHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(photo, 0f, 0f, null)
        canvas.save()
        canvas.translate(inset, photo.height + padY.toFloat())
        layout.draw(canvas)
        canvas.restore()
        return output
    }

    private fun saveCrops() {
        val source = selected ?: return
        val boxes = editor.boxes.map { it.copyQuad() }
        val titles = photoTitles.toList()
        val people = photoPeople.toList()
        val batchLabel = batchField.text.toString().trim()
        if (boxes.isEmpty()) {
            Toast.makeText(this, "No crop boxes to save", Toast.LENGTH_SHORT).show()
            return
        }
        val collection = safeFolderName(collectionField.text.toString(), "PhotoSplitter")
        val batch = batchField.text.toString().trim()
        val batchFolder = batchFolderToggle.isChecked
        val relativePath = "Pictures/" + collection +
            if (batchFolder && batch.isNotBlank()) "/" + safeFolderName(batch, "Unlabelled") else ""
        getSharedPreferences("photo_settings", MODE_PRIVATE).edit()
            .putString("collection", collection)
            .putBoolean("batch_subfolder", batchFolder)
            .apply()
        status.text = "Saving ${boxes.size} photos to $relativePath..."
        saveButton.isEnabled = false
        saveButton.text = "Saving..."
        scroll.smoothScrollTo(0, 0)
        Thread {
            var saved = 0
            try {
                for ((index, box) in boxes.withIndex()) {
                    val crop = PhotoDetector.crop(source, box)
                    val captioned = withCaption(crop, titles.getOrNull(index).orEmpty().trim())
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "PhotoSplitter_${System.currentTimeMillis()}_${index}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, relativePath + "/")
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        contentResolver.openOutputStream(uri)?.use {
                            if (captioned.compress(Bitmap.CompressFormat.JPEG, 95, it)) saved++
                        }
                        // Keep searchable descriptions in the MediaStore database.
                        val title = titles.getOrNull(index).orEmpty().trim()
                        val names = people.getOrNull(index).orEmpty().trim()
                        val description = listOfNotNull(
                            batchLabel.takeIf { it.isNotBlank() }?.let { "Batch: $it" },
                            title.takeIf { it.isNotBlank() }?.let { "Title: $it" },
                            names.takeIf { it.isNotBlank() }?.let { "People: $it" }
                        ).joinToString("; ")
                        if (description.isNotBlank()) {
                            try {
                                contentResolver.update(uri, ContentValues().apply {
                                    put(MediaStore.Images.Media.DESCRIPTION, description)
                                }, null, null)
                            } catch (_: Exception) {
                                // Some Android gallery providers do not support descriptions.
                            }
                        }
                    }
                    if (captioned !== crop) captioned.recycle()
                    crop.recycle()
                }
                runOnUiThread {
                    status.text = "Saved $saved of ${boxes.size} photos to $relativePath."
                    Toast.makeText(this, "Saved $saved photos", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Save failed after $saved photos: ${e.message}"
                    Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread {
                    saveButton.isEnabled = true
                    saveButton.text = "Save Crops"
                }
            }
        }.start()
    }
}
