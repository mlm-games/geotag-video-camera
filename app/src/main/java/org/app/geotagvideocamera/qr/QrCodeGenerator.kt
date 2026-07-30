package org.app.geotagvideocamera.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.Locale

object QrCodeGenerator {

    fun buildLocationPayload(lat: Double, lon: Double, address: String?): String {
        val latS = String.format(Locale.US, "%.6f", lat)
        val lonS = String.format(Locale.US, "%.6f", lon)
        return if (!address.isNullOrBlank()) {
            val safe = address
                .replace('(', '[')
                .replace(')', ']')
                .take(120)
            "geo:$latS,$lonS?q=$latS,$lonS($safe)"
        } else {
            "geo:$latS,$lonS"
        }
    }

    fun encodeToBitmap(content: String, sizePx: Int = 512): Bitmap? {
        if (content.isBlank() || sizePx <= 0) return null
        return runCatching {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val matrix: BitMatrix = MultiFormatWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                hints
            )
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                it.setPixels(pixels, 0, w, 0, 0, w, h)
            }
        }.getOrNull()
    }
}
