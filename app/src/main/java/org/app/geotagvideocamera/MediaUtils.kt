package org.app.geotagvideocamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.location.Location
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.altitude.AltitudeConverterCompat
import androidx.exifinterface.media.ExifInterface
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

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = output.savedUri ?: return
                    val loc = location
                    if (loc != null) {
                        Thread {
                            embedLocationMetadata(context, uri, loc)
                        }.start()
                    }
                    onPhotoSaved(uri)
                }

                override fun onError(exception: ImageCaptureException) {
                    onError("Photo capture failed: ${exception.message}")
                }
            }
        )
    }

    /**
     * Embeds location metadata into a captured photo.
     * Converts GPS ellipsoid altitude to MSL (Mean Sea Level) before writing EXIF.
     */
    private fun embedLocationMetadata(context: Context, uri: Uri, location: Location) {
        try {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)

                // Clone location to avoid mutating the original
                val loc = Location(location)

                // Convert ellipsoid altitude to MSL (Mean Sea Level)
                runCatching {
                    AltitudeConverterCompat.addMslAltitudeToLocation(context, loc)
                }.onFailure { e ->
                    Log.w("MediaUtils", "MSL altitude conversion failed", e)
                }

                val altitude = when {
                    LocationCompat.hasMslAltitude(loc) -> LocationCompat.getMslAltitudeMeters(loc)
                    loc.hasAltitude() -> loc.altitude
                    else -> null
                }
                altitude?.let { exif.setAltitude(it) }

                exif.setLatLong(loc.latitude, loc.longitude)
                exif.saveAttributes()

                Log.d("MediaUtils", "Location metadata embedded successfully (MSL: ${LocationCompat.hasMslAltitude(loc)})")
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
            val out = resolver.openOutputStream(uri, "w")
                ?: throw IOException("openOutputStream() returned null")
            out.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) {
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
            runCatching { resolver.delete(uri, null, null) }
            Toast.makeText(context, "Failed to save screenshot: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        return null
    }
}
