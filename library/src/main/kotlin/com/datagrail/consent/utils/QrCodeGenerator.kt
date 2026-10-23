package com.datagrail.consent.utils

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Generates QR code bitmaps for CTV pairing flow
 */
object QrCodeGenerator {
    /**
     * Generate a QR code bitmap from a URL string
     *
     * @param url The URL to encode in the QR code
     * @param size The width and height of the resulting square bitmap in pixels
     * @return Bitmap containing the QR code
     * @throws IllegalArgumentException if the URL is empty or size is invalid
     */
    fun generateQrCode(
        url: String,
        size: Int = 512,
    ): Bitmap {
        require(url.isNotEmpty()) { "URL cannot be empty" }
        require(size > 0) { "Size must be positive" }

        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(url, BarcodeFormat.QR_CODE, size, size)

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }

        return bitmap
    }
}
