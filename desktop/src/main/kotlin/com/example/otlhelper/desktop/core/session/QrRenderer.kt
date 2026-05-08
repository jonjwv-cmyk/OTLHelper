package com.example.otlhelper.desktop.core.session

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.nayuki.qrcodegen.QrCode
import java.awt.Color
import java.awt.image.BufferedImage

/**
 * §TZ-0.10.5 — рендер QR-кода для PC login screen.
 *
 * nayuki/qrcodegen pure-Java encoder; matrix scaled до sizePx квадрата
 * с margin = 1 модуль (минимально для надёжного scan).
 */
object QrRenderer {

    fun render(payload: String, sizePx: Int = 320, fg: Color = Color.WHITE, bg: Color = Color(0x1A, 0x1A, 0x1C)): ImageBitmap {
        val qr = QrCode.encodeText(payload, QrCode.Ecc.MEDIUM)
        val modules = qr.size
        val border = 1
        val total = modules + 2 * border
        val pixelsPerModule = (sizePx / total).coerceAtLeast(2)
        val imgSize = pixelsPerModule * total
        val img = BufferedImage(imgSize, imgSize, BufferedImage.TYPE_INT_RGB)
        val bgRgb = bg.rgb
        val fgRgb = fg.rgb

        // Fill background.
        val g = img.createGraphics()
        g.color = bg
        g.fillRect(0, 0, imgSize, imgSize)
        g.dispose()

        // Draw black modules.
        for (y in 0 until modules) {
            for (x in 0 until modules) {
                if (qr.getModule(x, y)) {
                    val pxX = (x + border) * pixelsPerModule
                    val pxY = (y + border) * pixelsPerModule
                    for (dy in 0 until pixelsPerModule) {
                        for (dx in 0 until pixelsPerModule) {
                            img.setRGB(pxX + dx, pxY + dy, fgRgb)
                        }
                    }
                }
            }
        }
        return img.toComposeImageBitmap()
    }
}
