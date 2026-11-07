package com.photosplitter

import android.content.ContentResolver
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Google Photos upload transport. Call on a background thread with an OAuth access token
 * granted for photoslibrary.appendonly. This class never handles passwords or stores tokens.
 */
internal object GooglePhotosUploader {
    private const val BASE = "https://photoslibrary.googleapis.com/v1"

    fun upload(
        resolver: ContentResolver,
        photoUri: Uri,
        fileName: String,
        userWrittenDescription: String,
        accessToken: String
    ): String {
        require(accessToken.isNotBlank()) { "Google Photos authorization required" }
        require(fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true)) {
            "Only JPEG uploads are supported in this initial integration"
        }
        require(userWrittenDescription.length < 1000) { "Description must be under 1000 characters" }

        val upload = (URL("$BASE/uploads").openConnection() as HttpURLConnection)
        val token = try {
            upload.requestMethod = "POST"
            upload.setRequestProperty("Authorization", "Bearer $accessToken")
            upload.setRequestProperty("Content-Type", "application/octet-stream")
            upload.setRequestProperty("X-Goog-Upload-Content-Type", "image/jpeg")
            upload.setRequestProperty("X-Goog-Upload-Protocol", "raw")
            upload.connectTimeout = 15000
            upload.readTimeout = 60000
            upload.doOutput = true
            upload.outputStream.use { output ->
                resolver.openInputStream(photoUri)?.use { it.copyTo(output) }
                    ?: error("Unable to open saved photo")
            }
            readResponse(upload).trim().also { require(it.isNotEmpty()) { "Empty upload token" } }
        } finally {
            upload.disconnect()
        }

        val create = (URL("$BASE/mediaItems:batchCreate").openConnection() as HttpURLConnection)
        return try {
            create.requestMethod = "POST"
            create.setRequestProperty("Authorization", "Bearer $accessToken")
            create.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            create.connectTimeout = 15000
            create.readTimeout = 60000
            create.doOutput = true
            val item = JSONObject().put("simpleMediaItem",
                JSONObject().put("uploadToken", token).put("fileName", fileName))
            if (userWrittenDescription.isNotBlank()) item.put("description", userWrittenDescription)
            val body = JSONObject().put("newMediaItems", JSONArray().put(item)).toString()
            create.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val response = JSONObject(readResponse(create))
            val result = response.getJSONArray("newMediaItemResults").getJSONObject(0)
            if (result.has("status") && result.getJSONObject("status").optInt("code", 0) != 0) {
                error(result.getJSONObject("status").optString("message", "Google Photos upload failed"))
            }
            result.getJSONObject("mediaItem").getString("id")
        } finally {
            create.disconnect()
        }
    }

    private fun readResponse(connection: HttpURLConnection): String {
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (status !in 200..299) error("Google Photos HTTP $status: ${body.take(500)}")
        return body
    }
}
