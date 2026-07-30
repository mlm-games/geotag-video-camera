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
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.media3.common.MediaItem
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import org.app.geotagvideocamera.location.LocationUi
import org.app.geotagvideocamera.location.formatLatLon
import org.app.geotagvideocamera.location.formatSpeed
import org.app.geotagvideocamera.map.resolveStyleUrl
import org.app.geotagvideocamera.qr.QrCodeGenerator
import org.app.geotagvideocamera.settings.SettingsState

data class LocationSample(
    val timeUs: Long,
    val location: LocationUi
)

data class MapSample(
    val timeUs: Long,
    val bitmap: Bitmap
)

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
                                    styleUrl = resolveStyleUrl(settings, context),
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

    internal fun captureMapSnapshot(
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

    private fun findNearestLocation(samples: List<LocationSample>, timeUs: Long): LocationUi? {
        if (samples.isEmpty()) return null
        var lo = 0
        var hi = samples.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (samples[mid].timeUs < timeUs) lo = mid + 1 else hi = mid
        }
        val a = samples[lo]
        val b = samples.getOrNull(lo - 1)
        return when {
            b == null -> a.location
            kotlin.math.abs(a.timeUs - timeUs) <= kotlin.math.abs(b.timeUs - timeUs) -> a.location
            else -> b.location
        }
    }

    private fun findNearestMap(samples: List<MapSample>, timeUs: Long): Bitmap? {
        if (samples.isEmpty()) return null
        var lo = 0
        var hi = samples.lastIndex
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (samples[mid].timeUs < timeUs) lo = mid + 1 else hi = mid
        }
        val a = samples[lo]
        val b = samples.getOrNull(lo - 1)
        return when {
            b == null -> a.bitmap
            kotlin.math.abs(a.timeUs - timeUs) <= kotlin.math.abs(b.timeUs - timeUs) -> a.bitmap
            else -> b.bitmap
        }
    }

    fun processVideoWithOverlay(
        context: Context,
        inputUri: Uri,
        originalUri: Uri,
        locationSamples: List<LocationSample>,
        mapSamples: List<MapSample>,
        settings: SettingsState,
        recordingStartEpochMs: Long,
        onComplete: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        val outputName = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val tempInput = File(context.cacheDir, "geotag_video_${outputName}_input.mp4")
        val tempOutput = File(context.cacheDir, "geotag_video_${outputName}_overlay.mp4")

        mediaExecutor.execute {
            try {
                context.contentResolver.openInputStream(inputUri)?.use { inp ->
                    tempInput.outputStream().use { out -> inp.copyTo(out) }
                }

                val fallbackMap = if (mapSamples.isEmpty() && settings.showMap) {
                    locationSamples.lastOrNull()?.location?.let { loc ->
                        captureMapSnapshot(
                            context = context,
                            lat = loc.latitude,
                            lon = loc.longitude,
                            zoom = settings.mapZoom,
                            styleUrl = resolveStyleUrl(settings, context),
                            targetWidth = 400,
                            targetHeight = 480
                        )
                    }
                } else null

                Handler(Looper.getMainLooper()).postDelayed({
                    val mediaItem = MediaItem.fromUri(Uri.fromFile(tempInput))

                    val overlay = object : CanvasOverlay(true) {
                        override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
                            val loc = findNearestLocation(locationSamples, presentationTimeUs)
                            val mapBmp = findNearestMap(mapSamples, presentationTimeUs) ?: fallbackMap
                            drawVideoOverlays(
                                canvas = canvas,
                                loc = loc,
                                settings = settings,
                                mapBitmap = mapBmp,
                                presentationTimeUs = presentationTimeUs,
                                recordingStartEpochMs = recordingStartEpochMs
                            )
                        }
                    }

                    val effects = Effects(emptyList(), listOf(OverlayEffect(listOf(overlay))))
                    val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                        .setEffects(effects)
                        .build()

                    val transformer = Transformer.Builder(context)
                        .build()

                    val listener = object : Transformer.Listener {
                        override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                            val savedUri = saveVideoToMediaStore(context, tempOutput)
                            tempOutput.delete()
                            tempInput.delete()
                            mapSamples.forEach { it.bitmap.recycle() }
                            fallbackMap?.recycle()
                            ContextCompat.getMainExecutor(context).execute {
                                if (savedUri != null) {
                                    runCatching { context.contentResolver.delete(originalUri, null, null) }
                                    onComplete(savedUri)
                                } else onError("Failed to save processed video")
                            }
                        }

                        override fun onError(composition: androidx.media3.transformer.Composition, exportResult: ExportResult, exception: ExportException) {
                            Log.e("MediaUtils", "Video overlay failed", exception)
                            tempOutput.delete()
                            tempInput.delete()
                            mapSamples.forEach { it.bitmap.recycle() }
                            fallbackMap?.recycle()
                            ContextCompat.getMainExecutor(context).execute {
                                onError("Video processing: ${exception.message}")
                            }
                        }
                    }

                    transformer.addListener(listener)
                    transformer.start(editedMediaItem, tempOutput.absolutePath)
                }, 500)
            } catch (e: Exception) {
                tempInput.delete()
                tempOutput.delete()
                mapSamples.forEach { it.bitmap.recycle() }
                ContextCompat.getMainExecutor(context).execute {
                    onError("Failed to prepare video: ${e.message}")
                }
            }
        }
    }

    private fun drawVideoOverlays(
        canvas: Canvas,
        loc: LocationUi?,
        settings: SettingsState,
        mapBitmap: Bitmap? = null,
        presentationTimeUs: Long = 0L,
        recordingStartEpochMs: Long = System.currentTimeMillis()
    ) {
        val scale = canvas.width / 1080f
        val pad = 24f * scale
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f * scale, 1f, 1f, android.graphics.Color.BLACK)
        }
        val bgPaint = Paint().apply {
            color = android.graphics.Color.BLACK
            alpha = 140
        }
        val whitePaint = Paint().apply { color = android.graphics.Color.WHITE }

        if (settings.showTopBar) {
            val videoTimeMs = recordingStartEpochMs + presentationTimeUs / 1000
            val date = SimpleDateFormat("MM/dd/yyyy", Locale.getDefault()).format(videoTimeMs)
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(videoTimeMs)
            val acc = loc?.accuracyMeters?.let { "\u00b1${it.toInt()} m" } ?: "No GPS"
            textPaint.textSize = 30f * scale
            canvas.drawText(time, pad, pad + textPaint.textSize, textPaint)
            canvas.drawText(date, pad, pad + textPaint.textSize * 2.3f, textPaint)
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
                val qrPad = 6f * scale
                canvas.drawRoundRect(
                    RectF(left - qrPad, top - qrPad, left + qrSize + qrPad, top + qrSize + qrPad),
                    8f * scale, 8f * scale, whitePaint
                )
                canvas.drawBitmap(qrBmp, null, RectF(left, top, left + qrSize, top + qrSize), null)
                qrBmp.recycle()
            }
        }

        textPaint.textSize = 34f * scale
        textPaint.textAlign = Paint.Align.CENTER
        var bottomY = h - pad

        if (settings.showSpeed || settings.showGpsStatus) {
            val chips = buildList {
                if (settings.showSpeed) add(loc?.let { formatSpeed(it.speedMps ?: 0f, settings.unitsIndex) } ?: "\u2014")
                if (settings.showGpsStatus) add(loc?.accuracyMeters?.let { "\u00b1${it.toInt()} m" } ?: "No GPS")
            }
            val chipH = 50f * scale
            var chipX = w / 2f - (chips.size * 140f * scale) / 2f
            for (chip in chips) {
                val cw = textPaint.measureText(chip) + 30f * scale
                canvas.drawRoundRect(
                    RectF(chipX, bottomY - chipH, chipX + cw, bottomY),
                    8f * scale, 8f * scale, bgPaint
                )
                canvas.drawText(chip, chipX + cw / 2f, bottomY - chipH * 0.3f, textPaint)
                chipX += cw + 12f * scale
            }
            bottomY -= chipH + pad
        }

        if (settings.showMap && loc?.latitude != null && mapBitmap != null) {
            val cardW = (if (settings.compactUi) 0.3f else 0.38f) * w
            val cardH = cardW * (mapBitmap.height.toFloat() / mapBitmap.width.toFloat())
            val cardX = (w - cardW) / 2f
            val showAddrBelow = settings.showAddress && settings.addressPositionIndex == 2
            val cardBottomPad = when {
                showAddrBelow && settings.compactUi -> 140f * scale
                showAddrBelow -> 160f * scale
                else -> 100f * scale
            }
            val cardY = h - cardH - cardBottomPad
            val mapCardRect = RectF(cardX, cardY, cardX + cardW, cardY + cardH)
            val borderPaint = Paint().apply {
                color = android.graphics.Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 2f * scale
            }
            canvas.drawRoundRect(mapCardRect, 12f * scale, 12f * scale, borderPaint)
            canvas.save()
            val clipPath = Path().apply {
                addRoundRect(mapCardRect, 12f * scale, 12f * scale, Path.Direction.CW)
            }
            canvas.clipPath(clipPath)
            canvas.drawBitmap(mapBitmap, null, mapCardRect, null)
            canvas.restore()

            val addr = loc?.address ?: "\u2014"
            if (settings.showAddress && settings.addressPositionIndex == 0) {
                textPaint.textSize = 22f * scale
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawRoundRect(RectF(cardX, cardY, cardX + cardW, cardY + 30f * scale), 12f * scale, 12f * scale, bgPaint)
                canvas.drawText(addr, w / 2f, cardY + 22f * scale, textPaint)
            }
            if (settings.showAddress && settings.addressPositionIndex == 1) {
                textPaint.textSize = 22f * scale
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawRoundRect(RectF(cardX, cardY + cardH - 30f * scale, cardX + cardW, cardY + cardH), 12f * scale, 12f * scale, bgPaint)
                canvas.drawText(addr, w / 2f, cardY + cardH - 8f * scale, textPaint)
            }
            if (settings.showCoordinates) {
                textPaint.textSize = 22f * scale
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawRoundRect(RectF(cardX, cardY + cardH - 26f * scale, cardX + cardW, cardY + cardH), 12f * scale, 12f * scale, bgPaint)
                canvas.drawText(formatLatLon(loc.latitude, loc.longitude), w / 2f, cardY + cardH - 6f * scale, textPaint)
            }
            if (settings.showAddress && settings.addressPositionIndex == 2) {
                textPaint.textSize = 22f * scale
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawRoundRect(RectF(pad * 2, bottomY - 60f * scale, w - pad * 2, bottomY - 30f * scale), 8f * scale, 8f * scale, bgPaint)
                canvas.drawText(addr, w / 2f, bottomY - 36f * scale, textPaint)
            }
            bottomY = cardY - pad
        }

        if (settings.showCoordinates || (settings.showAddress && !settings.showMap)) {
            val coord = loc?.let { formatLatLon(it.latitude, it.longitude) } ?: "\u2014"
            val addr = loc?.address ?: "\u2014"
            val addrLines = addr.chunked(40)
            val lineH = 32f * scale
            val blockH = (if (settings.showCoordinates) lineH else 0f) + (if (settings.showAddress && !settings.showMap) addrLines.size * lineH else 0f) + 20f * scale
            if (blockH > 0f) {
                canvas.drawRoundRect(
                    RectF(pad * 2, bottomY - blockH, w - pad * 2, bottomY),
                    12f * scale, 12f * scale, bgPaint
                )
                textPaint.textSize = 30f * scale
                var yOff = bottomY - blockH + lineH * 1.2f
                if (settings.showCoordinates) {
                    canvas.drawText(coord, w / 2f, yOff, textPaint)
                    yOff += lineH
                }
                if (settings.showAddress && !settings.showMap) {
                    textPaint.textSize = 28f * scale
                    for (line in addrLines) {
                        canvas.drawText(line, w / 2f, yOff, textPaint)
                        yOff += lineH
                    }
                }
            }
        }
    }

    private fun saveVideoToMediaStore(context: Context, file: File): Uri? {
        val name = SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.US)
            .format(System.currentTimeMillis())
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "geotag_video_$name.mp4")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/GeotagCamera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { inp -> inp.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            uri
        } catch (e: Exception) {
            Log.e("MediaUtils", "Failed to save video", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
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
