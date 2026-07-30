package org.app.geotagvideocamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.core.location.LocationCompat
import androidx.core.location.altitude.AltitudeConverterCompat
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.app.geotagvideocamera.location.LocationUi
import org.app.geotagvideocamera.location.formatLatLon
import org.app.geotagvideocamera.location.formatSpeed
import org.app.geotagvideocamera.map.resolveStyleUrl
import org.app.geotagvideocamera.qr.QrCodeGenerator
import org.app.geotagvideocamera.settings.SettingsState

/**
 * Utility class for handling media capture and metadata embedding
 */
object MediaUtils {

    private val mediaExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

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

                    mediaExecutor.execute {
                        try {
                            location?.let { loc ->
                                embedLocationMetadata(context.applicationContext, uri, loc)
                            }

                            ContextCompat.getMainExecutor(context).execute {
                                onPhotoSaved(uri)
                            }
                        } catch (t: Throwable) {
                            Log.e("MediaUtils", "Post-save metadata failed", t)
                            ContextCompat.getMainExecutor(context).execute {
                                onPhotoSaved(uri)
                            }
                        }
                    }
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

                val loc = Location(location)

                if (loc.hasAltitude()) {
                    runCatching {
                        AltitudeConverterCompat.addMslAltitudeToLocation(context, loc)
                    }.onFailure { e ->
                        Log.w("MediaUtils", "MSL altitude conversion failed", e)
                    }
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


    fun capturePhotoWithOverlay(
        context: Context,
        imageCapture: ImageCapture,
        location: Location?,
        locationUi: LocationUi?,
        settings: SettingsState,
        onPhotoSaved: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        imageCapture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    mediaExecutor.execute {
                        try {
                            val rotation = image.imageInfo.rotationDegrees
                            var bmp = image.toBitmap()
                            image.close()

                            if (rotation != 0) {
                                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                                val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                                if (rotated != bmp) bmp.recycle()
                                bmp = rotated
                            }

                            val mutable = bmp.copy(Bitmap.Config.ARGB_8888, true)
                            if (mutable != bmp) bmp.recycle()

                            val mapBmp = if (settings.showMap && locationUi?.latitude != null) {
                                captureMapSnapshot(
                                    context = context,
                                    lat = locationUi.latitude,
                                    lon = locationUi.longitude,
                                    zoom = settings.mapZoom,
                                    styleUrl = resolveStyleUrl(settings),
                                    targetWidth = (mutable.width * 0.38f).toInt().coerceIn(200, 1200),
                                    targetHeight = (mutable.width * 0.44f).toInt().coerceIn(240, 1400)
                                )
                            } else null

                            compositeOverlays(mutable, locationUi, settings, mapBmp)
                            mapBmp?.recycle()

                            val uri = saveBitmapToPictures(context, mutable, location)
                            mutable.recycle()

                            ContextCompat.getMainExecutor(context).execute {
                                if (uri != null) onPhotoSaved(uri)
                                else onError("Failed to save composited photo")
                            }
                        } catch (t: Throwable) {
                            Log.e("MediaUtils", "Overlay composite capture failed", t)
                            ContextCompat.getMainExecutor(context).execute {
                                onError("Photo capture failed: ${t.message}")
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    onError("Photo capture failed: ${exception.message}")
                }
            }
        )
    }

    private fun compositeOverlays(
        bitmap: Bitmap,
        loc: LocationUi?,
        settings: SettingsState,
        mapBitmap: Bitmap? = null
    ) {
        val canvas = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val densityScale = w / 1080f
        val pad = 24f * densityScale

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f * densityScale, 1f, 1f, android.graphics.Color.BLACK)
        }
        val bgPaint = Paint().apply {
            color = android.graphics.Color.BLACK
            alpha = 140
        }
        val whitePaint = Paint().apply { color = android.graphics.Color.WHITE }

        var bottomY = h - pad

        if (settings.showTopBar) {
            val date = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()).format(System.currentTimeMillis())
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(System.currentTimeMillis())
            val acc = loc?.accuracyMeters?.let { "\u00b1${it.toInt()} m" } ?: "No GPS"
            textPaint.textSize = 30f * densityScale
            canvas.drawText(date, pad, pad + textPaint.textSize, textPaint)
            canvas.drawText(time, pad, pad + textPaint.textSize * 2.3f, textPaint)
            val accW = textPaint.measureText(acc)
            canvas.drawText(acc, w - pad - accW, pad + textPaint.textSize * 1.5f, textPaint)
        }

        if (settings.showQrCode && loc?.latitude != null && loc.longitude != null) {
            val payload = QrCodeGenerator.buildLocationPayload(loc.latitude, loc.longitude, loc.address)
            val qrPx = if (settings.compactUi) 256 else 320
            val qrBmp = QrCodeGenerator.encodeToBitmap(payload, qrPx)
            if (qrBmp != null) {
                val qrSize = (if (settings.compactUi) 0.12f else 0.15f) * w
                val left = w - pad - qrSize
                val top = if (settings.showTopBar) textPaint.textSize * 2.3f + pad * 0.5f else pad
                val qrPad = 6f * densityScale
                canvas.drawRoundRect(
                    RectF(left - qrPad, top - qrPad, left + qrSize + qrPad, top + qrSize + qrPad),
                    8f * densityScale, 8f * densityScale, whitePaint
                )
                canvas.drawBitmap(qrBmp, null, RectF(left, top, left + qrSize, top + qrSize), null)
                qrBmp.recycle()
            }
        }

        if (settings.showMap && loc?.latitude != null && mapBitmap != null) {
            val cardW = (if (settings.compactUi) 0.3f else 0.38f) * w
            val cardH = cardW * (mapBitmap.height.toFloat() / mapBitmap.width.toFloat())
            val cardX = (w - cardW) / 2f
            val showAddrBelow = settings.showAddress && settings.addressPositionIndex == 2
            val cardBottomPad = when {
                showAddrBelow && settings.compactUi -> 140f * densityScale
                showAddrBelow -> 160f * densityScale
                else -> 100f * densityScale
            }
            val cardY = h - cardH - cardBottomPad

            val borderPaint = Paint().apply {
                color = android.graphics.Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2f * densityScale
            }
            val mapCardRect = RectF(cardX, cardY, cardX + cardW, cardY + cardH)
            val clipPath = android.graphics.Path().apply {
                addRoundRect(mapCardRect, 12f * densityScale, 12f * densityScale, android.graphics.Path.Direction.CW)
            }
            canvas.drawRoundRect(mapCardRect, 12f * densityScale, 12f * densityScale, borderPaint)
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.drawBitmap(mapBitmap, null, mapCardRect, null)
            canvas.restore()

            val addr = loc?.address ?: "\u2014"
            if (settings.showAddress && settings.addressPositionIndex == 0) {
                canvas.drawRoundRect(RectF(cardX, cardY, cardX + cardW, cardY + 30f * densityScale), 12f * densityScale, 12f * densityScale, bgPaint)
                textPaint.textSize = 22f * densityScale
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(addr, w / 2f, cardY + 22f * densityScale, textPaint)
            }
            if (settings.showAddress && settings.addressPositionIndex == 1) {
                val addrY = cardY + cardH - 30f * densityScale
                canvas.drawRoundRect(RectF(cardX, addrY, cardX + cardW, cardY + cardH), 12f * densityScale, 12f * densityScale, bgPaint)
                textPaint.textSize = 22f * densityScale
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(addr, w / 2f, cardY + cardH - 8f * densityScale, textPaint)
            }
            if (settings.showCoordinates) {
                val coord = formatLatLon(loc.latitude, loc.longitude)
                val coordY = cardY + cardH - 26f * densityScale
                canvas.drawRoundRect(RectF(cardX, coordY, cardX + cardW, cardY + cardH), 12f * densityScale, 12f * densityScale, bgPaint)
                textPaint.textSize = 22f * densityScale
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(coord, w / 2f, cardY + cardH - 6f * densityScale, textPaint)
            }

            if (settings.showAddress && settings.addressPositionIndex == 2) {
                val addrY = h - (if (settings.compactUi) 100f * densityScale else 110f * densityScale)
                val addrH = 30f * densityScale
                canvas.drawRoundRect(RectF(pad * 2, addrY - addrH, w - pad * 2, addrY), 8f * densityScale, 8f * densityScale, bgPaint)
                textPaint.textSize = 22f * densityScale
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawText(addr, w / 2f, addrY - 6f * densityScale, textPaint)
            }
            bottomY = cardY - pad
        }

        textPaint.textSize = 34f * densityScale
        textPaint.textAlign = Paint.Align.CENTER

        if (settings.showSpeed || settings.showGpsStatus) {
            val chips = buildList {
                if (settings.showSpeed) add(loc?.let { formatSpeed(it.speedMps ?: 0f, settings.unitsIndex) } ?: "\u2014")
                if (settings.showGpsStatus) add(loc?.accuracyMeters?.let { "\u00b1${it.toInt()} m" } ?: "No GPS")
            }
            val chipH = 50f * densityScale
            var chipX = w / 2f - (chips.size * 140f * densityScale) / 2f
            for (chip in chips) {
                val cw = textPaint.measureText(chip) + 30f * densityScale
                canvas.drawRoundRect(
                    RectF(chipX, bottomY - chipH, chipX + cw, bottomY),
                    8f * densityScale, 8f * densityScale, bgPaint
                )
                canvas.drawText(chip, chipX + cw / 2f, bottomY - chipH * 0.3f, textPaint)
                chipX += cw + 12f * densityScale
            }
            bottomY -= chipH + pad
        }

        if (settings.showCoordinates || (settings.showAddress && !settings.showMap)) {
            val coord = loc?.let { formatLatLon(it.latitude, it.longitude) } ?: "\u2014"
            val addr = loc?.address ?: "\u2014"
            val addrLines = addr.chunked(40)
            val lineH = 32f * densityScale
            val blockH = (if (settings.showCoordinates) lineH else 0f) + (if (settings.showAddress && !settings.showMap) addrLines.size * lineH else 0f) + 20f * densityScale
            if (blockH > 0f) {
                canvas.drawRoundRect(
                    RectF(pad * 2, bottomY - blockH, w - pad * 2, bottomY),
                    12f * densityScale, 12f * densityScale, bgPaint
                )
                textPaint.textSize = 30f * densityScale
                var yOff = bottomY - blockH + lineH * 1.2f
                if (settings.showCoordinates) {
                    canvas.drawText(coord, w / 2f, yOff, textPaint)
                    yOff += lineH
                }
                if (settings.showAddress && !settings.showMap) {
                    textPaint.textSize = 28f * densityScale
                    for (line in addrLines) {
                        canvas.drawText(line, w / 2f, yOff, textPaint)
                        yOff += lineH
                    }
                }
            }
        }
    }

    private fun captureMapSnapshot(
        context: Context,
        lat: Double,
        lon: Double,
        zoom: Float,
        styleUrl: String,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap? {
        val latch = CountDownLatch(1)
        var result: Bitmap? = null
        runCatching {
            val cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                .target(org.maplibre.android.geometry.LatLng(lat, lon))
                .zoom(zoom.toDouble())
                .build()
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    val opts = org.maplibre.android.snapshotter.MapSnapshotter.Options(targetWidth, targetHeight)
                        .withStyleBuilder(org.maplibre.android.maps.Style.Builder().fromUri(styleUrl))
                        .withCameraPosition(cameraPosition)
                    val ss = org.maplibre.android.snapshotter.MapSnapshotter(context, opts)
                    ss.start(object : org.maplibre.android.snapshotter.MapSnapshotter.SnapshotReadyCallback {
                        override fun onSnapshotReady(snapshot: org.maplibre.android.snapshotter.MapSnapshot) {
                            result = snapshot.bitmap
                            latch.countDown()
                        }
                    })
                }.onFailure { latch.countDown() }
            }
            latch.await(10, TimeUnit.SECONDS)
        }.onFailure { e ->
            Log.w("MediaUtils", "Map snapshot failed", e)
        }
        return result
    }

    /**
     * Saves a bitmap to MediaStore under Pictures/GeotagCamera as a JPEG.
     */
    fun saveBitmapToPictures(context: Context, bitmap: Bitmap, location: Location? = null): Uri? {
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

            location?.let { embedLocationMetadata(context.applicationContext, uri, it) }

            return uri
        } catch (e: Exception) {
            Log.e("MediaUtils", "Failed to save bitmap", e)
            runCatching { resolver.delete(uri, null, null) }
            Toast.makeText(context, "Failed to save screenshot: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        return null
    }
}
