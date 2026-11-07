package com.photosplitter

import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.auth.api.identity.AuthorizationResult
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
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
import android.widget.PopupMenu
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface as AndroidXExif
import java.io.File
import kotlin.math.max

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var retakeButton: Button
    private lateinit var preview: ImageView
    private lateinit var editor: CropEditor
    private lateinit var saveButton: Button
    private lateinit var cropControls: LinearLayout
    private lateinit var scroll: ScrollView
    private var zoomDialog: android.app.Dialog? = null
    private lateinit var results: LinearLayout
    private lateinit var collectionField: EditText
    private lateinit var batchField: EditText
    private lateinit var historyList: LinearLayout
    private lateinit var batchFolderToggle: CheckBox
    private val displayedCrops = mutableListOf<Bitmap>()
    private val photoTitles = mutableListOf<String>()
    private val photoPeople = mutableListOf<String>()
    private var selected: Bitmap? = null
    private var pendingCameraFile: File? = null
    private val photosScope = Scope("https://www.googleapis.com/auth/photoslibrary.appendonly")
    private val requestPhotosAuthorization = 102
    private var pendingPhotos: List<Pair<Uri, Pair<String, String>>> = emptyList()
    private var photosConnected = false
    private val requestPick = 100
    private val requestCamera = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCameraFile = savedInstanceState?.getString("cameraPath")?.let(::File)
        photosConnected = getSharedPreferences("photo_settings", MODE_PRIVATE).getBoolean("google_photos_connected", false)
        window.statusBarColor = Color.rgb(17, 24, 39)
        window.navigationBarColor = Color.rgb(17, 24, 39)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 44, 32, 44)
            setBackgroundColor(Color.WHITE)
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
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val menuButton = TextView(this).apply {
            text = "☰"
            textSize = 27f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.rgb(17, 24, 39))
            contentDescription = "Open menu"
            isClickable = true
            isFocusable = true
            setOnClickListener { showAppMenu() }
        }
        header.addView(menuButton, LinearLayout.LayoutParams(
            (48 * resources.displayMetrics.density).toInt(),
            (48 * resources.displayMetrics.density).toInt()
        ))
        layout.addView(header)
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
        val batchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        batchRow.addView(batchField, LinearLayout.LayoutParams(0, -2, 1f))
        batchRow.addView(TextView(this).apply {
            text = "×"
            contentDescription = "Clear batch label"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.rgb(55, 65, 81))
            background = null
            elevation = 0f
            isClickable = true
            isFocusable = true
            setOnClickListener { batchField.setText("") }
        }, LinearLayout.LayoutParams(
            (44 * resources.displayMetrics.density).toInt(),
            LinearLayout.LayoutParams.MATCH_PARENT
        ))
        layout.addView(batchRow)
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
        cropControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val edit = Button(this).apply {
            text = "Edit Crops"
            setOnClickListener {
                if (selected == null) return@setOnClickListener
                editor.editing = !editor.editing
                if (editor.editing) {
                    // Place the original photograph at the top and lock scrolling during edits.
                    scroll.post { scroll.scrollTo(0, camera.top) }
                }
                editor.visibility = View.VISIBLE
                preview.visibility = View.GONE
                text = if (editor.editing) "Finish Editing" else "Edit Crops"
                editor.invalidate()
            }
        }
        styleButton(edit)
        cropControls.addView(edit)
        cropControls.addView(Button(this).apply {
            text = "Add Crop Box"
            setOnClickListener {
                if (selected == null) return@setOnClickListener
                editor.addBox()
                if (!editor.editing) {
                    editor.editing = true
                    editor.visibility = View.VISIBLE
                    preview.visibility = View.GONE
                    edit.text = "Finish Editing"
                    scroll.post { scroll.scrollTo(0, camera.top) }
                }
                editor.invalidate()
            }
            styleButton(this)
        })
        cropControls.addView(Button(this).apply { text = "Remove Selected Box"; setOnClickListener { editor.removeSelected() }; styleButton(this) })
        layout.addView(cropControls)
        results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(8, 18, 8, 12)
        }
        // Keep crop controls next to the original image; previews follow below.
        layout.addView(results)
        saveButton = Button(this).apply {
            text = "Save Crops"
            setOnClickListener { saveCrops() }
        }
        styleButton(saveButton, true)
        saveButton.visibility = View.GONE
        layout.addView(saveButton)
        layout.addView(TextView(this).apply {
            text = "Previous saved batches"
            textSize = 20f
            setPadding(0, 24, 0, 8)
        })
        historyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(historyList)
        refreshHistory()
        scroll = object : ScrollView(this) {
            override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean =
                if (editor.editing) false else super.onInterceptTouchEvent(ev)

            override fun onTouchEvent(ev: android.view.MotionEvent): Boolean =
                if (editor.editing) false else super.onTouchEvent(ev)
        }.apply { addView(layout) }
        setContentView(scroll)
    }

    private fun themedDialog(title: String, message: String, actionLabel: String, onAction: () -> Unit) {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.WHITE)
        }
        content.addView(TextView(this).apply {
            text = title
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(17, 24, 39))
        })
        content.addView(TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(Color.rgb(55, 65, 81))
            setPadding(0, padding / 2, 0, padding / 2)
        })
        val dialog = Dialog(this)
        content.addView(Button(this).apply {
            text = actionLabel
            styleButton(this, true)
            setOnClickListener { dialog.dismiss(); onAction() }
        })
        content.addView(Button(this).apply {
            text = "Cancel"
            styleButton(this)
            setOnClickListener { dialog.dismiss() }
        })
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawable(rounded(Color.WHITE, 16))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showAppMenu() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val dialog = Dialog(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.WHITE)
        }
        content.addView(TextView(this).apply {
            text = "PhotoSplitter menu"
            textSize = 22f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(17, 24, 39))
        })
        content.addView(TextView(this).apply {
            text = if (photosConnected) "Google Photos — Connected  ✓" else "Google Photos integration  ⓘ"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(30, 58, 138))
            setPadding(0, padding, 0, padding / 3)
        })
        content.addView(TextView(this).apply {
            text = "Completely optional. No Google account is needed to scan, label, or save photos locally on your phone.\n\nConnecting lets you upload your scanned pictures with titles, people's names and descriptions to your Google Photos collection.\n\nGoogle Photos search may not index these descriptions; we're still testing that."
            textSize = 16f
            setTextColor(Color.rgb(55, 65, 81))
            setPadding(0, 0, 0, padding / 2)
        })
        content.addView(Button(this).apply {
            text = if (photosConnected) "Disconnect Google Photos" else "Connect Google Photos"
            styleButton(this, !photosConnected)
            setOnClickListener { dialog.dismiss(); if (photosConnected) disconnectPhotos() else authorizePhotos() }
        })
        content.addView(Button(this).apply {
            text = if (photosConnected) "Done" else "Continue without Google"
            styleButton(this)
            setOnClickListener { dialog.dismiss() }
        })
        dialog.setContentView(content)
        dialog.window?.setBackgroundDrawable(rounded(Color.WHITE, 16))
        dialog.show()
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9f).toInt(),
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun disconnectPhotos() {
        AlertDialog.Builder(this)
            .setTitle("Disconnect Google Photos?")
            .setMessage("PhotoSplitter will lose permission to upload to Google Photos. Photos already saved locally or uploaded to Google Photos will remain untouched.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Disconnect") { _, _ ->
                Identity.getAuthorizationClient(this).revokeAccess()
                    .addOnSuccessListener {
                        photosConnected = false
                        pendingPhotos = emptyList()
                        getSharedPreferences("photo_settings", MODE_PRIVATE).edit()
                            .putBoolean("google_photos_connected", false).apply()
                        Toast.makeText(this, "Google Photos disconnected", Toast.LENGTH_LONG).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "Could not disconnect: ${it.message}", Toast.LENGTH_LONG).show()
                    }
            }
            .show()
    }

    private fun authorizePhotos() {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(photosScope))
            .build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    try {
                        startIntentSenderForResult(
                            result.pendingIntent!!.intentSender, requestPhotosAuthorization,
                            null, 0, 0, 0
                        )
                    } catch (e: Exception) {
                        Toast.makeText(this, "Google authorization: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    onPhotosAuthorized(result)
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "Google Photos authorization failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun onPhotosAuthorized(result: AuthorizationResult) {
        val token = result.accessToken
        if (token.isNullOrBlank()) {
            Toast.makeText(this, "No Google Photos access token returned", Toast.LENGTH_LONG).show()
            return
        }
        photosConnected = true
        getSharedPreferences("photo_settings", MODE_PRIVATE).edit()
            .putBoolean("google_photos_connected", true).apply()
        val items = pendingPhotos
        pendingPhotos = emptyList()
        if (items.isEmpty()) {
            Toast.makeText(this, "Google Photos connected. Save a batch to upload it.", Toast.LENGTH_LONG).show()
            return
        }
        status.text = "Uploading ${items.size} saved photos to Google Photos..."
        Thread {
            var uploaded = 0
            var failure: String? = null
            for ((uri, details) in items) {
                try {
                    GooglePhotosUploader.upload(contentResolver, uri, details.first, details.second, token)
                    uploaded++
                } catch (e: Exception) {
                    failure = e.message ?: "Unknown upload error"
                    break
                }
            }
            runOnUiThread {
                status.text = if (failure == null) {
                    "Uploaded $uploaded photos to Google Photos."
                } else {
                    "Uploaded $uploaded of ${items.size}. Upload stopped: $failure"
                }
                Toast.makeText(this, status.text, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun offerPhotosUpload(items: List<Pair<Uri, Pair<String, String>>>) {
        if (items.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Upload to Google Photos?")
            .setMessage("Your scans are saved on this device. Upload ${items.size} scans with their descriptions to Google Photos? This is optional.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Upload") { _, _ ->
                pendingPhotos = items
                authorizePhotos()
            }
            .show()
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
            if (primary) Color.rgb(37, 99, 235) else Color.WHITE,
            12,
            if (primary) null else Color.rgb(219, 234, 254)
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
        if (requestCode == requestPhotosAuthorization) {
            if (resultCode == RESULT_OK && data != null) {
                try {
                    onPhotosAuthorized(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data))
                } catch (e: Exception) {
                    Toast.makeText(this, "Google Photos authorization failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } else {
                pendingPhotos = emptyList()
            }
            return
        }
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
            cropControls.visibility = View.VISIBLE
            saveButton.visibility = View.VISIBLE
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

    private fun nextBatchLabel(label: String): String {
        if (label.isBlank()) return ""
        val match = Regex("^(.*?)(\\d+)$").matchEntire(label)
        if (match == null) return "$label 2"
        val digits = match.groupValues[2]
        val next = digits.toLongOrNull()?.plus(1) ?: return "$label 2"
        return match.groupValues[1] + next.toString().padStart(digits.length, '0')
    }

    private fun recordBatch(label: String, path: String, firstUri: String, count: Int) {
        val prefs = getSharedPreferences("photo_settings", MODE_PRIVATE)
        val previous = try { JSONArray(prefs.getString("saved_batches", "[]")) } catch (_: Exception) { JSONArray() }
        val history = JSONArray()
        history.put(JSONObject().apply {
            put("label", label.ifBlank { "Untitled batch" })
            put("path", path)
            put("uri", firstUri)
            put("count", count)
            put("savedAt", System.currentTimeMillis())
        })
        for (i in 0 until previous.length()) history.put(previous.get(i))
        prefs.edit().putString("saved_batches", history.toString()).apply()
    }

    private fun savedBatchHistory(): JSONArray {
        val value = getSharedPreferences("photo_settings", MODE_PRIVATE)
            .getString("saved_batches", "[]")
        return try { JSONArray(value) } catch (_: Exception) { JSONArray() }
    }

    private fun currentVersionCode(): Long =
        packageManager.getPackageInfo(packageName, 0).longVersionCode

    private fun removeHistoryLink(index: Int, onRemoved: () -> Unit) {
        val prefs = getSharedPreferences("photo_settings", MODE_PRIVATE)
        val history = savedBatchHistory()
        if (index !in 0 until history.length()) return
        val remove = {
            val remaining = JSONArray()
            for (i in 0 until history.length()) {
                if (i != index) remaining.put(history.get(i))
            }
            prefs.edit().putString("saved_batches", remaining.toString()).apply()
            refreshHistory()
            onRemoved()
        }
        val version = currentVersionCode()
        if (prefs.getLong("skip_history_warning_version", -1L) == version) {
            remove()
            return
        }
        val dp = resources.displayMetrics.density
        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * dp).toInt(), (12 * dp).toInt(), (24 * dp).toInt(), (12 * dp).toInt())
        }
        dialogContent.addView(TextView(this).apply {
            text = "This removes only the link from PhotoSplitter. Your scanned photos are NOT deleted and remain available in your photo library."
            textSize = 16f
            setTextColor(Color.rgb(40, 40, 40))
            setPadding(0, (8 * dp).toInt(), 0, (22 * dp).toInt())
        })
        val noRepeat = CheckBox(this).apply {
            text = "Don't show this dialog again"
            textSize = 15f
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        dialogContent.addView(noRepeat)
        AlertDialog.Builder(this)
            .setTitle("Remove saved batch link?")
            .setView(dialogContent)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove link") { _, _ ->
                if (noRepeat.isChecked) {
                    prefs.edit().putLong("skip_history_warning_version", version).apply()
                }
                remove()
            }
            .show()
    }

    private fun openSavedBatch(item: JSONObject) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(item.optString("uri")), "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open saved photo", Toast.LENGTH_LONG).show()
        }
    }

    private fun batchHistoryRow(item: JSONObject, index: Int, onRemoved: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        row.addView(Button(this).apply {
            isAllCaps = false
            setTextColor(Color.rgb(17, 24, 39))
            background = rounded(Color.WHITE, 10, Color.rgb(219, 234, 254))
            elevation = 0f
            text = "${item.optString("label")} · ${item.optInt("count")} photos\n${item.optString("path")}"
            setOnClickListener { openSavedBatch(item) }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = "×"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.rgb(55, 65, 81))
            contentDescription = "Remove ${item.optString("label")} from saved batches"
            background = null
            elevation = 0f
            isClickable = true
            isFocusable = true
            setOnClickListener { removeHistoryLink(index, onRemoved) }
        }, LinearLayout.LayoutParams(
            (48 * resources.displayMetrics.density).toInt(),
            LinearLayout.LayoutParams.MATCH_PARENT
        ))
        return row
    }

    private fun showOlderBatches() {
        val dialog = Dialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(248, 251, 255))
        }
        container.addView(TextView(this).apply {
            text = "←  Back"
            textSize = 17f
            setTextColor(Color.rgb(30, 64, 175))
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(12, 8, 12, 8)
            contentDescription = "Back to PhotoSplitter"
            isClickable = true
            isFocusable = true
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (48 * resources.displayMetrics.density).toInt()
        ))
        container.addView(TextView(this).apply {
            text = "All saved batches"
            textSize = 24f
            setPadding(8, 16, 8, 16)
        })
        val olderList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val olderScroll = ScrollView(this).apply { addView(olderList) }
        container.addView(olderScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        fun populate() {
            olderList.removeAllViews()
            val history = savedBatchHistory()
            if (history.length() == 0) {
                olderList.addView(TextView(this).apply { text = "No saved batches" })
            }
            for (i in 0 until history.length()) {
                val item = history.optJSONObject(i) ?: continue
                olderList.addView(batchHistoryRow(item, i) { populate() })
            }
        }
        populate()
        dialog.setContentView(container)
        dialog.window?.setLayout(-1, -1)
        dialog.setOnDismissListener { refreshHistory() }
        dialog.show()
        dialog.window?.setLayout(-1, -1)
    }

    private fun refreshHistory() {
        historyList.removeAllViews()
        val history = savedBatchHistory()
        if (history.length() == 0) {
            historyList.addView(TextView(this).apply { text = "No saved batches yet" })
            return
        }
        for (i in 0 until minOf(5, history.length())) {
            val item = history.optJSONObject(i) ?: continue
            historyList.addView(batchHistoryRow(item, i) { refreshHistory() })
        }
        if (history.length() > 5) {
            historyList.addView(Button(this).apply {
                text = "Show more · All ${history.length()} batches"
                isAllCaps = false
                styleButton(this)
                setOnClickListener { showOlderBatches() }
            })
        }
    }

    private fun resetAfterSave(nextLabel: String) {
        selected = null
        editor.editing = false
        editor.photo = null
        editor.setBoxes(emptyList())
        editor.visibility = View.GONE
        preview.setImageDrawable(null)
        preview.visibility = View.GONE
        retakeButton.visibility = View.GONE
        cropControls.visibility = View.GONE
        saveButton.visibility = View.GONE
        clearResults()
        photoTitles.clear()
        photoPeople.clear()
        batchField.setText(nextLabel)
        scroll.smoothScrollTo(0, 0)
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
            var firstSavedUri = ""
            val savedForUpload = mutableListOf<Pair<Uri, Pair<String, String>>>()
            try {
                for ((index, box) in boxes.withIndex()) {
                    val crop = PhotoDetector.crop(source, box)
                    val captioned = withCaption(crop, titles.getOrNull(index).orEmpty().trim())
                    val filenameTitle = titles.getOrNull(index).orEmpty().trim()
                        .replace(Regex("[^\\p{L}\\p{N}._ -]"), "")
                        .trim().replace(Regex("\\s+"), "_")
                        .take(60).ifBlank { "PhotoSplitter" }
                    val filename = "${filenameTitle}_${System.currentTimeMillis()}_${index + 1}.jpg"
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, relativePath + "/")
                    }
                    val title = titles.getOrNull(index).orEmpty().trim()
                    val names = people.getOrNull(index).orEmpty().trim()
                    val description = listOfNotNull(
                        batchLabel.takeIf { it.isNotBlank() }?.let { "Batch: $it" },
                        title.takeIf { it.isNotBlank() }?.let { "Title: $it" },
                        names.takeIf { it.isNotBlank() }?.let { "People: $it" }
                    ).joinToString("; ")
                    // Encode EXIF into the JPEG itself, not only the gallery database.
                    val temp = File.createTempFile("photosplitter_exif_", ".jpg", cacheDir)
                    try {
                        temp.outputStream().use { output ->
                            if (!captioned.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                                error("Could not encode JPEG")
                            }
                        }
                        if (title.isNotBlank() || description.isNotBlank()) {
                            AndroidXExif(temp.absolutePath).apply {
                                if (title.isNotBlank()) {
                                    setAttribute(AndroidXExif.TAG_IMAGE_DESCRIPTION, title)
                                }
                                if (description.isNotBlank()) {
                                    setAttribute(AndroidXExif.TAG_USER_COMMENT, description)
                                }
                                saveAttributes()
                            }
                        }
                        // Experimental: see whether Google Photos imports XMP dc:description.
                        embedXmpDescription(
                            temp, title, names, batchLabel,
                            // Experimental keyword appears ONLY in XMP, not visible caption,
                            // filename, EXIF, or MediaStore. First crop of each save only.
                            if (index == 0) "PSHIDDEN9184" else ""
                        )
                        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                            ?: error("Could not create saved photo")
                        val written = contentResolver.openOutputStream(uri)?.use { output ->
                            temp.inputStream().use { input -> input.copyTo(output) }
                            true
                        } ?: false
                        if (!written) {
                            contentResolver.delete(uri, null, null)
                            error("Could not write saved photo")
                        }
                        savedForUpload.add(uri to (filename to description))
                        saved++
                        if (firstSavedUri.isBlank()) firstSavedUri = uri.toString()
                        if (description.isNotBlank()) {
                            try {
                                contentResolver.update(uri, ContentValues().apply {
                                    put(MediaStore.Images.Media.DESCRIPTION, description)
                                }, null, null)
                            } catch (_: Exception) {
                                // Gallery providers may not expose this optional field.
                            }
                        }
                    } finally {
                        temp.delete()
                    }
                    if (captioned !== crop) captioned.recycle()
                    crop.recycle()
                }
                runOnUiThread {
                    status.text = "Saved $saved of ${boxes.size} photos to $relativePath."
                    if (saved > 0) {
                        recordBatch(batchLabel, relativePath, firstSavedUri, saved)
                        refreshHistory()
                    }
                    if (saved == boxes.size) resetAfterSave(nextBatchLabel(batchLabel))
                    if (savedForUpload.isNotEmpty()) offerPhotosUpload(savedForUpload)
                    Toast.makeText(this, "Saved $saved of ${boxes.size} photos", Toast.LENGTH_LONG).show()
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
