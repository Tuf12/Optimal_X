package com.example.optimalx.data.link

// Spec: app/docs/architecture/OPTIMALX_LINK.md §phone-link-screen.
//
// Tiny wrapper around ZXing's `QRCodeWriter` that returns a boolean bitmap.
// The Compose layer renders the bitmap with `drawRect` calls so we don't pull
// in `android.graphics.Bitmap` from a tools-only encoder path (this also keeps
// the function JVM-testable).

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object LinkQrEncoder {

    /**
     * Encodes [text] as a square QR code matrix. `result[y][x]` is `true` when
     * the module at column `x`, row `y` should be drawn dark.
     *
     * @param size requested module count (pixel resolution before rendering).
     *             ZXing rounds up to fit the chosen QR version, so the actual
     *             returned matrix may be slightly larger.
     */
    fun encode(text: String, size: Int = 256): Array<BooleanArray> {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val width = matrix.width
        val height = matrix.height
        val out = Array(height) { BooleanArray(width) }
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[y][x] = matrix.get(x, y)
            }
        }
        return out
    }
}
