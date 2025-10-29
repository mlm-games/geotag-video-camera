package org.app.geotagvideocamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Utility class for handling media capture and metadata embedding
 */
object MediaUtils {

    /**
     * Captures a photo with embedded location metadata
     */
    fun capturePhoto(
        context: Context,
        imageCapture: ImageCapture,
        location: Location?,
        onPhotoSaved: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        // Create output file options
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GeotagCamera")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        // Take the photo
        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    output.savedUri?.let { uri ->
                        // Embed location metadata
                        location?.let { loc ->
                            embedLocationMetadata(context, uri, loc)
                        }
                        onPhotoSaved(uri)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    onError("Photo capture failed: ${exception.message}")
                }
            }
        )
    }

    /**
     * Embeds location metadata into a captured photo
     * Use a seekable FileDescriptor; ExifInterface does not persist changes with plain InputStreams.
     */
    private fun embedLocationMetadata(context: Context, uri: Uri, location: Location) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setLatLong(location.latitude, location.longitude)
                if (location.hasAltitude()) {
                    exif.setAltitude(location.altitude)
                }
                exif.saveAttributes()
                Log.d("MediaUtils", "Location metadata embedded successfully")
            }
        } catch (e: IOException) {
            Log.e("MediaUtils", "Error embedding location metadata", e)
        }
    }

    /**
     * Save a bitmap to MediaStore under Pictures/GeotagCamera as a JPEG.
     */
    fun saveBitmapToPictures(context: Context, bitmap: Bitmap): Uri? {
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val resolver = context.contentResolver

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "geotag_${name}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GeotagCamera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri == null) {
            Toast.makeText(context, "Unable to save screenshot", Toast.LENGTH_SHORT).show()
            return null
        }

        try {
            resolver.openOutputStream(uri, "w")?.use { out: OutputStream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                    throw IOException("Bitmap compress() returned false")
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            return uri
        } catch (e: Exception) {
            Log.e("MediaUtils", "Failed to save bitmap", e)
            // Cleanup the row if something failed
            runCatching { resolver.delete(uri, null, null) }
            Toast.makeText(context, "Failed to save screenshot: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        return null
    }

    /**
     * Creates a MediaRecorder instance for screen recording
     */
    fun createMediaRecorder(context: Context, width: Int, height: Int): MediaRecorder {
        val videoPath = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)}/GeotagCamera"
        val dir = File(videoPath)
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val fileName = "geotag_${System.currentTimeMillis()}.mp4"
        val filePath = "$videoPath/$fileName"

        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoEncodingBitRate(8 * 1024 * 1024) // 8Mbps
            setVideoFrameRate(30)
            setVideoSize(width, height)
            setOutputFile(filePath)

            try {
                prepare()
            } catch (e: IOException) {
                Log.e("MediaUtils", "Error preparing MediaRecorder", e)
                Toast.makeText(context, "Failed to prepare recording", Toast.LENGTH_SHORT).show()
            }
        }

        return mediaRecorder
    }
}