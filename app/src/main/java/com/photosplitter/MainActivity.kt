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
import android.speech.RecognizerIntent
import android.content.ActivityNotFoundException
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
    private val requestSpeech = 103
    private var speechTarget: EditText? = null
    private val requestPick = 100
    private val requestCamera = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCameraFile = savedInstanceState?.getString("cameraPath")?.let(::File)
        photosConnected = getSharedPreferences("photo_settings", MODE_PRIVATE).getBoolean("google_photos_connected", false)
        refreshPhotosConnectionSilently()
        window.statusBarColor = Color.rgb(17, 24, 39)
        window.navigationBarColor = Color.rgb(17, 24, 39)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 44, 32, 44)
            setBackgroundColor(Color.WHITE)
        }
        val title = TextView(this).apply {
            text = "PhotoSplitter"
            textSize = 31f
            setTextColor(Color.rgb(7, 59, 76))
            typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD)
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
        header.addView(ImageView(this).apply {
            setImageResource(com.photosplitter.R.drawable.photosplitter_icon)
            contentDescription = "PhotoSplitter vintage photograph logo"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams((48 * resources.displayMetrics.density).toInt(), (48 * resources.displayMetrics.density).toInt()))
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
        if (!prefs.getBoolean("skip_welcome", false) && savedInstanceState == null) {
            scroll.post { showWelcomeGuide() }
        }
    }

    private fun showWelcomeGuide() {
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val dialog = Dialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
            setBackgroundColor(Color.rgb(253, 248, 239))
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        brand.addView(ImageView(this).apply {
            setImageResource(com.photosplitter.R.drawable.photosplitter_icon)
            contentDescription = "Vintage photographs"
        }, LinearLayout.LayoutParams((44 * density).toInt(), (44 * density).toInt()).apply { rightMargin = (14 * density).toInt() })
        brand.addView(TextView(this).apply {
            text = "PhotoSplitter"
            textSize = 26f
            setTextColor(Color.rgb(7, 59, 76))
            typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD)
        })
        root.addView(brand)
        root.addView(TextView(this).apply {
            text = "One shot. Every photo."
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.rgb(169, 102, 56))
            typeface = android.graphics.Typeface.create("serif", android.graphics.Typeface.BOLD)
            setPadding(0, pad / 2, 0, 0)
        })
        root.addView(TextView(this).apply {
            text = "Photograph several printed pictures at once. We'll split them into individual photos you can label and save to your phone or Google Photos."
            textSize = 15f
            setTextColor(Color.rgb(63, 67, 64))
            setPadding(0, pad / 3, 0, pad / 3)
        })
        // A real-life example of 16 slightly misaligned vintage prints on a dark surface.
        val exampleBytes = android.util.Base64.decode("/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAEMuMjoyKkM6NjpLR0NPZKZsZFxcZMySmnmm8dT++u3U6eX//////////+Xp////////////////////////////2wBDAUdLS2RXZMRsbMT//+n/////////////////////////////////////////////////////////////////////wAARCAC8APADASIAAhEBAxEB/8QAGQAAAwEBAQAAAAAAAAAAAAAAAgMEAQAF/8QANhAAAgECBAQEBAYBBAMAAAAAAQIAAxEEEiExE0FRgRRhcZEiMkJSIyQzQ2KhggVykrHB0fH/xAAWAQEBAQAAAAAAAAAAAAAAAAAAAQL/xAAYEQEBAQEBAAAAAAAAAAAAAAAAEQEhMf/aAAwDAQACEQMRAD8AOvjcjlaYBtuTFeOq9F9pMd4QRzspPaA/x1XovtO8dV6L7RQoVT9BhDC1T9IHqYB+Oq9F9p3jqvRfaD4VuboO87gUx81de0AvHVei+07x1XovtMFGhlvxCQOkGvTRKasl9TbWKD8dV6L7TvHVei+0llaYamUUksCReBnjqvRfad46r0X2hHCJyc+0w4Mcqn9QM8fV6L7TvH1ei+0w4NuTrMODqcip7wC8fV6L7TvH1ei+0WcLVH0g94t6bUzZxa8B/j6vRfad4+t0X2mHDIoGera4vtOFHD86pPoIG+PrdF9p3j63Rfadkww+pzNy4YD5XPeBnj63Rfad4+r0X2nA4flSPcw6fCqMVFIDTeSgPH1ei+07x9bovtJZbTwtNqSsSwJF9JQHj6vRfad4+r0X2hnBJyc+0E4HpUHcQM8fV6J7TvH1ui+044J+TKYJwdYcge8AvH1eie0JP9QcH41BHlEnDVh9B94tkZDZgQfOBxlNSq6UaRU2BGsmMebNhEJNrNaTQtq9Qj5295nEYnViR6xgogrfTygiiBTzE87QrVp3VWBN2gVEyaX1liimlgL6c4qsmcm1r3EBCEkga7bdY6qc+EvYCzTKL01uHFiIbOKmHq25WMCSemB8KjoBPMG8uqVGWpYdJUNtOtFCsekLjQDtMtB46jeCcQOQ/uAyxk2N+ZB5QjiDytAxZvVH+2BuI1Wmf4RI3jqutCkf42k5MiqKgUKAABby1h01FlBNwdCDEmpxFUMbEaXliU1UCwgS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", android.util.Base64.DEFAULT)
        root.addView(ImageView(this).apply {
            setImageBitmap(BitmapFactory.decodeByteArray(exampleBytes, 0, exampleBytes.size))
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            contentDescription = "Example: 16 vintage family and travel photographs laid out on a dark background, slightly crooked with gaps between prints"
        })
        root.addView(TextView(this).apply {
            text = "Lay prints on a dark surface with small gaps; slight angles are fine.\\n\\nThe number you can capture depends on your camera's resolution. I've found 16 works well, but experiment with batch sizes to balance speed and detail."
            textSize = 15f
            setTextColor(Color.rgb(63, 67, 64))
            setPadding(0, pad / 2, 0, pad / 2)
        })
        val neverAgain = CheckBox(this).apply {
            text = "Don't show this again"
            isChecked = getSharedPreferences("photo_settings", MODE_PRIVATE).getBoolean("skip_welcome", false)
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, pad / 2)
            setBackgroundColor(Color.rgb(253, 248, 239))
        }
        footer.addView(neverAgain)
        footer.addView(Button(this).apply {
            text = "Get Started →"
            styleButton(this, true)
            setOnClickListener {
                getSharedPreferences("photo_settings", MODE_PRIVATE).edit()
                    .putBoolean("skip_welcome", neverAgain.isChecked).apply()
                dialog.dismiss()
            }
        })
        val scroller = ScrollView(this).apply { addView(root) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(253, 248, 239))
            addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(footer, LinearLayout.LayoutParams(-1, -2))
        }
        dialog.setContentView(container)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .94f).toInt(), -2)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * .94f).toInt(),
            (resources.displayMetrics.heightPixels * .88f).toInt())
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

    private fun refreshPhotosConnectionSilently() {
        val prefs = getSharedPreferences("photo_settings", MODE_PRIVATE)
        if (prefs.getBoolean("google_photos_disconnected_by_user", false)) return
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(photosScope))
            .build()
        Identity.getAuthorizationClient(this).authorize(request)
            .addOnSuccessListener { result ->
                // Never launch a consent screen during an automatic status check.
                if (!result.hasResolution() && !result.accessToken.isNullOrBlank()) {
                    photosConnected = true
                    prefs.edit().putBoolean("google_photos_connected", true).apply()
                } else {
                    photosConnected = false
                    prefs.edit().putBoolean("google_photos_connected", false).apply()
                }
            }
            .addOnFailureListener {
                // A transient Google Play Services failure is not proof of disconnection.
            }
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
            text = "How to use PhotoSplitter  ⓘ"
            textSize = 17f
            setTextColor(Color.rgb(30, 58, 138))
            setPadding(0, padding / 2, 0, padding / 2)
            setOnClickListener { dialog.dismiss(); showWelcomeGuide() }
        })
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
            text = "Completely optional. No Google account is needed to scan, label, or save photos locally on your phone.\n\nConnecting lets you upload your scanned pictures with titles, people's names and descriptions to your Google Photos collection.\n\nIn Google Photos, you can use Ask Photos to find uploaded pictures by titles, names and descriptions. For better results, ask a full question, such as: Find photos whose description mentions Athens. Search results may vary."
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
            .setMessage("PhotoSplitter will stop treating Google Photos as connected and will not upload unless you authorize it again. This does not revoke Google's account-level permission. You can revoke that separately in your Google Account's third-party connections settings. Existing photos are not deleted.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Disconnect") { _, _ ->
                photosConnected = false
                pendingPhotos = emptyList()
                getSharedPreferences("photo_settings", MODE_PRIVATE).edit()
                    .putBoolean("google_photos_connected", false)
                    .putBoolean("google_photos_disconnected_by_user", true).apply()
                Toast.makeText(this, "Google Photos disconnected in PhotoSplitter", Toast.LENGTH_LONG).show()
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
            .putBoolean("google_photos_connected", true)
            .putBoolean("google_photos_disconnected_by_user", false).apply()
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
            .setMessage("Your scans are saved on this device. Upload ${items.size} ${if (items.size == 1) "scan" else "scans"} with ${if (items.size == 1) "its description" else "their descriptions"} to Google Photos? This is optional.")
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
        if (requestCode == requestSpeech) {
            val recognized = if (resultCode == RESULT_OK) {
                data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            } else null
            if (!recognized.isNullOrBlank()) {
                speechTarget?.let { field ->
                    val current = field.text.toString().trim()
                    field.setText(if (current.isBlank()) recognized else "$current $recognized")
                    field.setSelection(field.text.length)
                }
            }
            speechTarget = null
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

    private fun addDictationField(hintText: String, initial: String, onTextChanged: (String) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val field = EditText(this).apply {
            hint = hintText
            setSingleLine(true)
            setText(initial)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    onTextChanged(s?.toString() ?: "")
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        row.addView(field)
        row.addView(TextView(this).apply {
            text = "🎤"
            textSize = 24f
            contentDescription = "Dictate $hintText"
            setPadding(12, 12, 12, 12)
            setOnClickListener {
                speechTarget = field
                try {
                    startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak $hintText")
                    }, requestSpeech)
                } catch (e: ActivityNotFoundException) {
                    speechTarget = null
                    Toast.makeText(this@MainActivity, "Speech recognition is not available on this device", Toast.LENGTH_LONG).show()
                }
            }
        })
        results.addView(row)
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
            addDictationField("Photo title (e.g. Howard in Paris)", photoTitles[index]) {
                photoTitles[index] = it
            }
            addDictationField("People (comma-separated, e.g. Howard, Margaret)", photoPeople[index]) {
                photoPeople[index] = it
            }
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
