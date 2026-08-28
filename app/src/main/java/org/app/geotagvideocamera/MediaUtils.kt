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
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import org.app.geotagvideocamera.location.LocationUi
import org.app.geotagvideocamera.location.formatLatLon
import org.app.geotagvideocamera.location.formatSpeed
import org.app.geotagvideocamera.map.resolveStyleUrl
import org.app.geotagvideocamera.qr.QrCodeGenerator
import org.app.geotagvideocamera.settings.SettingsState
import org.app.geotagvideocamera.settings.effectiveMapPosition

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
        dragFractionX: Float = 0f,
        dragFractionY: Float = 0f,
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
                                val isLand = mutable.width > mutable.height
                                val cardDpW = when {
                                    (settings.effectiveMapPosition(isLand) == 2 || settings.effectiveMapPosition(isLand) == 3) && settings.compactUi -> 150f
                                    settings.effectiveMapPosition(isLand) == 2 || settings.effectiveMapPosition(isLand) == 3 -> 170f
                                    settings.compactUi -> 200f
                                    else -> 240f
                                }
                                val cardDpH = when {
                                    (settings.effectiveMapPosition(isLand) == 2 || settings.effectiveMapPosition(isLand) == 3) && settings.compactUi -> 150f
                                    settings.effectiveMapPosition(isLand) == 2 || settings.effectiveMapPosition(isLand) == 3 -> 170f
                                    settings.compactUi -> 220f
                                    else -> 280f
                                }
                                val snapW = dpToPx(cardDpW, mutable.width.toFloat()).toInt().coerceIn(200, 2000)
                                val snapH = dpToPx(cardDpH, mutable.width.toFloat()).toInt().coerceIn(200, 2400)
                                captureMapSnapshot(
                                    context = context,
                                    lat = locationUi.latitude,
                                    lon = locationUi.longitude,
                                    zoom = settings.mapZoom,
                                    styleUrl = resolveStyleUrl(settings, context),
                                    targetWidth = snapW,
                                    targetHeight = snapH
                                )
                            } else null

                            compositeOverlays(
                                mutable,
                                locationUi,
                                settings,
                                mapBmp,
                                dragFractionX,
                                dragFractionY
                            )
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

    private data class MapCardLayout(
        val cardW: Float,
        val cardH: Float,
        val cardX: Float,
        val cardY: Float,
        val sidePane: Boolean
    )

    private const val ADDRESS_MAX_LINES = 3
    private const val REF_WIDTH_DP = 360f
    private fun dpToPx(dp: Float, w: Float): Float = dp * w / REF_WIDTH_DP
    private fun spToPx(sp: Float, w: Float): Float = sp * w / REF_WIDTH_DP
    private fun mapAddressSp(settings: SettingsState) = if (settings.compactUi) 9f else 10f
    private fun chipSp(settings: SettingsState) = if (settings.compactUi) 12f else 14f

    private data class BoundedTextBlock(
        val layout: StaticLayout,
        val textWidthPx: Int,
        val height: Float
    )

    private fun estimatedCapturedAddressBlockHeight(scale: Float): Float {
        return 90f * scale
    }

    private fun buildBoundedTextBlock(
        text: String,
        basePaint: Paint,
        outerWidth: Float,
        hPad: Float,
        vPad: Float,
        maxLines: Int = ADDRESS_MAX_LINES
    ): BoundedTextBlock {
        val textWidthPx = (outerWidth - 2f * hPad).toInt().coerceAtLeast(1)
        val textPaint = TextPaint(basePaint).apply {
            textAlign = Paint.Align.LEFT
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, textWidthPx)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setEllipsizedWidth(textWidthPx)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.05f)
            .build()
        return BoundedTextBlock(
            layout = layout,
            textWidthPx = textWidthPx,
            height = layout.height.toFloat() + 2f * vPad
        )
    }

    private fun drawBoundedTextBlock(
        canvas: Canvas,
        block: BoundedTextBlock,
        left: Float,
        top: Float,
        outerWidth: Float,
        hPad: Float,
        vPad: Float,
        bgPaint: Paint,
        cornerRadius: Float
    ): Float {
        val rect = RectF(left, top, left + outerWidth, top + block.height)
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
        canvas.save()
        canvas.clipRect(rect)
        canvas.translate(left + hPad, top + vPad)
        block.layout.draw(canvas)
        canvas.restore()
        return block.height
    }

    private fun drawBoundedAddressBlock(
        canvas: Canvas,
        address: String,
        textPaint: Paint,
        left: Float,
        top: Float,
        outerWidth: Float,
        scale: Float,
        w: Float,
        settings: SettingsState,
        bgPaint: Paint,
        cornerRadius: Float,
        maxLines: Int = ADDRESS_MAX_LINES
    ): Float {
        textPaint.textSize = spToPx(mapAddressSp(settings), w)
        val hPad = 8f * scale
        val vPad = 6f * scale
        val block = buildBoundedTextBlock(
            text = address,
            basePaint = textPaint,
            outerWidth = outerWidth,
            hPad = hPad,
            vPad = vPad,
            maxLines = maxLines
        )
        return drawBoundedTextBlock(
            canvas = canvas,
            block = block,
            left = left,
            top = top,
            outerWidth = outerWidth,
            hPad = hPad,
            vPad = vPad,
            bgPaint = bgPaint,
            cornerRadius = cornerRadius
        )
    }

    private fun computeMapCardLayout(
        w: Float,
        h: Float,
        scale: Float,
        settings: SettingsState,
        mapBitmap: Bitmap,
        isLandscape: Boolean,
        dragFracX: Float = 0f,
        dragFracY: Float = 0f
    ): MapCardLayout {
        val pos = settings.effectiveMapPosition(isLandscape)
        val sidePane = pos == 2 || pos == 3
        val aspect = mapBitmap.height.toFloat() / mapBitmap.width.toFloat()

        val cardDpW = when {
            sidePane && settings.compactUi -> 150f
            sidePane -> 170f
            settings.compactUi -> 200f
            else -> 240f
        }
        var cardW = dpToPx(cardDpW, w)
        val minCardW = minOf(w, h) * when {
            sidePane && settings.compactUi -> 0.28f
            sidePane -> 0.32f
            settings.compactUi -> 0.30f
            else -> 0.38f
        }
        cardW = maxOf(cardW, minCardW)
        cardW = cardW.coerceAtMost(w - 2f * 24f * scale)
        var cardH = cardW * aspect
        val maxH = h * (if (sidePane || isLandscape) 0.55f else 0.75f)
        if (cardH > maxH) {
            cardH = maxH
            cardW = cardH / aspect
        }

        val showAddrBelow = settings.showAddress && settings.addressPositionIndex == 2

        val belowAddressReserve = if (showAddrBelow) {
            6f * scale + estimatedCapturedAddressBlockHeight(scale) + 24f * scale
        } else {
            24f * scale
        }

        val baseBottomPad = when {
            sidePane && showAddrBelow -> 84f * scale
            sidePane -> 56f * scale
            showAddrBelow && settings.compactUi -> 140f * scale
            showAddrBelow -> 160f * scale
            else -> 100f * scale
        }

        val cardBottomPad = maxOf(baseBottomPad, belowAddressReserve)

        val pad = 24f * scale
        var cardY = (h - cardH - cardBottomPad).coerceAtLeast(pad)
        var cardX = when (pos) {
            2 -> pad
            3 -> w - cardW - pad
            else -> (w - cardW) / 2f
        }

        if (dragFracX != 0f || dragFracY != 0f) {
            val maxX = (w - cardW - pad).coerceAtLeast(pad)
            val maxY = (h - cardH - belowAddressReserve).coerceAtLeast(pad)
            cardX = (cardX + dragFracX * w).coerceIn(pad, maxX)
            cardY = (cardY + dragFracY * h).coerceIn(pad, maxY)
        }
        return MapCardLayout(cardW, cardH, cardX, cardY, sidePane)
    }

    private fun compositeOverlays(
        bitmap: Bitmap,
        loc: LocationUi?,
        settings: SettingsState,
        mapBitmap: Bitmap? = null,
        dragFractionX: Float = 0f,
        dragFractionY: Float = 0f
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
            val dateSp = if (settings.compactUi) 10f else 12f
            val timeSp = if (settings.compactUi) 12f else 14f
            val accSp = if (settings.compactUi) 10f else 12f
            textPaint.textSize = spToPx(dateSp, w)
            canvas.drawText(date, pad, pad + textPaint.textSize, textPaint)
            textPaint.textSize = spToPx(timeSp, w)
            canvas.drawText(time, pad, pad + textPaint.textSize * 1.5f + spToPx(dateSp, w), textPaint)
            textPaint.textSize = spToPx(accSp, w)
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
            val isLandscape = w > h
            val layout = computeMapCardLayout(w, h, densityScale, settings, mapBitmap, isLandscape, dragFractionX, dragFractionY)
            val cardW = layout.cardW
            val cardH = layout.cardH
            val cardX = layout.cardX
            val cardY = layout.cardY

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
            val textCx = cardX + cardW / 2f

            if (settings.showAddress && settings.addressPositionIndex == 0) {
                drawBoundedAddressBlock(
                    canvas = canvas,
                    address = addr,
                    textPaint = textPaint,
                    left = cardX,
                    top = cardY,
                    outerWidth = cardW,
                    scale = densityScale,
                    w = w,
                    settings = settings,
                    bgPaint = bgPaint,
                    cornerRadius = 12f * densityScale
                )
            }

            val coordStripH = 26f * densityScale
            val coordTop = cardY + cardH - coordStripH
            val bottomAddressLimit = if (settings.showCoordinates) coordTop else cardY + cardH

            if (settings.showAddress && settings.addressPositionIndex == 1) {
                val hPad = 8f * densityScale
                val vPad = 6f * densityScale
                textPaint.textSize = spToPx(mapAddressSp(settings), w)
                val block = buildBoundedTextBlock(
                    text = addr,
                    basePaint = textPaint,
                    outerWidth = cardW,
                    hPad = hPad,
                    vPad = vPad,
                    maxLines = ADDRESS_MAX_LINES
                )
                val addrTop = (bottomAddressLimit - block.height).coerceAtLeast(cardY)
                drawBoundedTextBlock(
                    canvas = canvas,
                    block = block,
                    left = cardX,
                    top = addrTop,
                    outerWidth = cardW,
                    hPad = hPad,
                    vPad = vPad,
                    bgPaint = bgPaint,
                    cornerRadius = 12f * densityScale
                )
            }

            if (settings.showCoordinates) {
                val coord = formatLatLon(loc.latitude, loc.longitude)
                textPaint.textSize = spToPx(mapAddressSp(settings), w)
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawRoundRect(
                    RectF(cardX, coordTop, cardX + cardW, cardY + cardH),
                    12f * densityScale,
                    12f * densityScale,
                    bgPaint
                )
                val coordTextWidth = textPaint.measureText(coord)
                canvas.drawText(coord, textCx, cardY + cardH - 6f * densityScale, textPaint)
            }

            if (settings.showAddress && settings.addressPositionIndex == 2) {
                val addrTop = cardY + cardH + 6f * densityScale
                val pillW = minOf(cardW + 24f * densityScale, w - 2f * pad).coerceAtLeast(1f)
                val maxLeft = (w - pad - pillW).coerceAtLeast(pad)
                val pillLeft = (textCx - pillW / 2f).coerceIn(pad, maxLeft)
                drawBoundedAddressBlock(
                    canvas = canvas,
                    address = addr,
                    textPaint = textPaint,
                    left = pillLeft,
                    top = addrTop,
                    outerWidth = pillW,
                    scale = densityScale,
                    w = w,
                    settings = settings,
                    bgPaint = bgPaint,
                    cornerRadius = 8f * densityScale
                )
            }

            if (!layout.sidePane) {
                bottomY = cardY - pad
            }
        }

        textPaint.textSize = spToPx(chipSp(settings), w)
        textPaint.textAlign = Paint.Align.CENTER

        if (settings.showSpeed || settings.showGpsStatus) {
            val chips = buildList {
                if (settings.showSpeed) add(loc?.let { formatSpeed(it.speedMps ?: 0f, settings.unitsIndex) } ?: "\u2014")
                if (settings.showGpsStatus) add(loc?.accuracyMeters?.let { "\u00b1${it.toInt()} m" } ?: "No GPS")
            }
            val chipH = dpToPx(if (settings.compactUi) 26f else 30f, w)
            var chipX = w / 2f - (chips.size * 140f * densityScale) / 2f
            for (chip in chips) {
                val cw = textPaint.measureText(chip) + dpToPx(16f, w)
                canvas.drawRoundRect(
                    RectF(chipX, bottomY - chipH, chipX + cw, bottomY),
                    8f * densityScale, 8f * densityScale, bgPaint
                )
                canvas.drawText(chip, chipX + cw / 2f, bottomY - chipH * 0.3f, textPaint)
                chipX += cw + 12f * densityScale
            }
            bottomY -= chipH + pad
        }

        if (!settings.showMap && settings.showLocationTextWithoutMap && (settings.showCoordinates || settings.showAddress)) {
            val coord = loc?.let { formatLatLon(it.latitude, it.longitude) } ?: "\u2014"
            val addr = loc?.address ?: "\u2014"
            val addrLines = addr.chunked(40)
            val standaloneSp = if (settings.compactUi) 11f else 13f
            val lineH = spToPx(standaloneSp, w) * 1.35f
            // Respect individual toggles for block height, matching StandaloneLocationOverlay
            val showCoords = settings.showCoordinates
            val showAddr = settings.showAddress
            val blockH = (if (showCoords) lineH else 0f) + (if (showAddr) addrLines.size * lineH else 0f) + 20f * densityScale
            if (blockH > 0f) {
                canvas.drawRoundRect(
                    RectF(pad * 2, bottomY - blockH, w - pad * 2, bottomY),
                    12f * densityScale, 12f * densityScale, bgPaint
                )
                textPaint.textSize = spToPx(standaloneSp, w)
                var yOff = bottomY - blockH + lineH * 1.05f
                if (showCoords) {
                    canvas.drawText(coord, w / 2f, yOff, textPaint)
                    yOff += lineH
                }
                if (showAddr) {
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
        dragFractionX: Float = 0f,
        dragFractionY: Float = 0f,
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
                        // Fallback uses preview card size at 1080p reference so zoom matches preview
                        val snapW = 720
                        val snapH = 840
                        captureMapSnapshot(
                            context = context,
                            lat = loc.latitude,
                            lon = loc.longitude,
                            zoom = settings.mapZoom,
                            styleUrl = resolveStyleUrl(settings, context),
                            targetWidth = snapW,
                            targetHeight = snapH
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
                                dragFractionX = dragFractionX,
                                dragFractionY = dragFractionY,
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
        dragFractionX: Float = 0f,
        dragFractionY: Float = 0f,
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
            val dateSp = if (settings.compactUi) 10f else 12f
            val timeSp = if (settings.compactUi) 12f else 14f
            val accSp = if (settings.compactUi) 10f else 12f
            textPaint.textSize = spToPx(dateSp, w)
            canvas.drawText(time, pad, pad + textPaint.textSize, textPaint)
            textPaint.textSize = spToPx(dateSp, w)
            canvas.drawText(date, pad, pad + textPaint.textSize * 2.3f, textPaint)
            textPaint.textSize = spToPx(accSp, w)
            val accW = textPaint.measureText(acc)
            canvas.drawText(acc, w - pad - accW, pad + textPaint.textSize * 1.5f, textPaint)
            textPaint.textSize = spToPx(timeSp, w)
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

        textPaint.textSize = spToPx(chipSp(settings), w)
        textPaint.textAlign = Paint.Align.CENTER
        var bottomY = h - pad

        if (settings.showSpeed || settings.showGpsStatus) {
            val chips = buildList {
                if (settings.showSpeed) add(loc?.let { formatSpeed(it.speedMps ?: 0f, settings.unitsIndex) } ?: "\u2014")
                if (settings.showGpsStatus) add(loc?.accuracyMeters?.let { "\u00b1${it.toInt()} m" } ?: "No GPS")
            }
            val chipH = dpToPx(if (settings.compactUi) 26f else 30f, w)
            var chipX = w / 2f - (chips.size * 140f * scale) / 2f
            for (chip in chips) {
                val cw = textPaint.measureText(chip) + dpToPx(16f, w)
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
            val isLandscape = w > h
            val layout = computeMapCardLayout(w, h, scale, settings, mapBitmap, isLandscape, dragFractionX, dragFractionY)
            val cardW = layout.cardW
            val cardH = layout.cardH
            val cardX = layout.cardX
            val cardY = layout.cardY
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
            val textCx = cardX + cardW / 2f

            if (settings.showAddress && settings.addressPositionIndex == 0) {
                drawBoundedAddressBlock(
                    canvas = canvas,
                    address = addr,
                    textPaint = textPaint,
                    left = cardX,
                    top = cardY,
                    outerWidth = cardW,
                    scale = scale,
                    w = w,
                    settings = settings,
                    bgPaint = bgPaint,
                    cornerRadius = 12f * scale
                )
            }

            val coordStripH = 26f * scale
            val coordTop = cardY + cardH - coordStripH
            val bottomAddressLimit = if (settings.showCoordinates) coordTop else cardY + cardH

            if (settings.showAddress && settings.addressPositionIndex == 1) {
                val hPad = 8f * scale
                val vPad = 6f * scale
                textPaint.textSize = spToPx(mapAddressSp(settings), w)
                val block = buildBoundedTextBlock(
                    text = addr,
                    basePaint = textPaint,
                    outerWidth = cardW,
                    hPad = hPad,
                    vPad = vPad,
                    maxLines = ADDRESS_MAX_LINES
                )
                val addrTop = (bottomAddressLimit - block.height).coerceAtLeast(cardY)
                drawBoundedTextBlock(
                    canvas = canvas,
                    block = block,
                    left = cardX,
                    top = addrTop,
                    outerWidth = cardW,
                    hPad = hPad,
                    vPad = vPad,
                    bgPaint = bgPaint,
                    cornerRadius = 12f * scale
                )
            }

            if (settings.showCoordinates) {
                textPaint.textSize = spToPx(mapAddressSp(settings), w)
                textPaint.textAlign = Paint.Align.CENTER
                canvas.drawRoundRect(
                    RectF(cardX, coordTop, cardX + cardW, cardY + cardH),
                    12f * scale,
                    12f * scale,
                    bgPaint
                )
                canvas.drawText(formatLatLon(loc.latitude, loc.longitude), textCx, cardY + cardH - 6f * scale, textPaint)
            }

            if (settings.showAddress && settings.addressPositionIndex == 2) {
                val addrTop = cardY + cardH + 6f * scale
                val pillW = minOf(cardW + 24f * scale, w - 2f * pad).coerceAtLeast(1f)
                val maxLeft = (w - pad - pillW).coerceAtLeast(pad)
                val pillLeft = (textCx - pillW / 2f).coerceIn(pad, maxLeft)
                drawBoundedAddressBlock(
                    canvas = canvas,
                    address = addr,
                    textPaint = textPaint,
                    left = pillLeft,
                    top = addrTop,
                    outerWidth = pillW,
                    scale = scale,
                    w = w,
                    settings = settings,
                    bgPaint = bgPaint,
                    cornerRadius = 8f * scale
                )
            }

            if (!layout.sidePane) {
                bottomY = cardY - pad
            }
        }

        if (!settings.showMap && settings.showLocationTextWithoutMap && (settings.showCoordinates || settings.showAddress)) {
            val coord = loc?.let { formatLatLon(it.latitude, it.longitude) } ?: "\u2014"
            val addr = loc?.address ?: "\u2014"
            val addrLines = addr.chunked(40)
            val standaloneSp = if (settings.compactUi) 11f else 13f
            val lineH = spToPx(standaloneSp, w) * 1.35f
            val showCoords = settings.showCoordinates
            val showAddr = settings.showAddress
            val blockH = (if (showCoords) lineH else 0f) + (if (showAddr) addrLines.size * lineH else 0f) + 20f * scale
            if (blockH > 0f) {
                canvas.drawRoundRect(
                    RectF(pad * 2, bottomY - blockH, w - pad * 2, bottomY),
                    12f * scale, 12f * scale, bgPaint
                )
                textPaint.textSize = spToPx(standaloneSp, w)
                var yOff = bottomY - blockH + lineH * 1.05f
                if (showCoords) {
                    canvas.drawText(coord, w / 2f, yOff, textPaint)
                    yOff += lineH
                }
                if (showAddr) {
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
