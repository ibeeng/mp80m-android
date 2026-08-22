package com.ibeeng.mp80m

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream

/**
 * ESC/POS raster encoder for the MP-80M / RPP02N thermal printer.
 *
 * Ported from the proven Go daemon (github.com/ibeeng/mp80m-printer):
 *   - Fit image to 576-dot printable width (72mm @ 8 dot/mm)
 *   - Floyd–Steinberg dithering to 1-bit
 *   - Optional whitespace trim (top/bottom) to save paper on tall labels
 *   - GS v 0 raster, one command per row (shape proven correct on this HW)
 *   - Init + center align + reset line spacing, feed + cut at the end
 *
 * IMPORTANT: the raw byte stream must reach the printer byte-for-byte. On the
 * daemon side we had to force the RFCOMM TTY into raw mode; on Android the
 * Bluetooth SPP OutputStream is already a raw binary channel, so no 0x0A
 * translation happens. We still pace the writes (see PrinterBluetooth).
 */
object EscPos {

    /** Printable width in dots for an 80mm roll (72mm print area @ 8 dot/mm). */
    const val MAX_WIDTH = 576

    private const val ESC = 0x1B
    private const val GS = 0x1D

    /**
     * Full end-to-end: bitmap -> ESC/POS byte stream, repeated [copies] times.
     * Each copy is a full init + raster + cut so multi-copy prints cleanly.
     */
    fun encodeImage(
        src: Bitmap,
        maxWidth: Int = MAX_WIDTH,
        trim: Boolean = true,
        trimMargin: Int = 16,
        copies: Int = 1,
    ): ByteArray {
        val mono = toMonochrome(src, maxWidth, trim, trimMargin)
        val one = ByteArrayOutputStream()
        one.write(byteArrayOf(ESC.toByte(), 0x40))            // ESC @  init
        one.write(byteArrayOf(ESC.toByte(), 0x61, 0x01))      // ESC a 1  center
        one.write(byteArrayOf(ESC.toByte(), 0x32))            // ESC 2  reset line spacing
        one.write(rasterRows(mono))
        one.write(feedAndCut())
        val single = one.toByteArray()

        if (copies <= 1) return single
        val out = ByteArrayOutputStream()
        repeat(copies.coerceAtLeast(1)) { out.write(single) }
        return out.toByteArray()
    }

    /** Text receipt helper (built-in font). Left-aligned, feed + cut, [copies] times. */
    fun encodeText(text: String, copies: Int = 1): ByteArray {
        val one = ByteArrayOutputStream()
        one.write(byteArrayOf(ESC.toByte(), 0x40))            // init
        one.write(byteArrayOf(ESC.toByte(), 0x61, 0x00))      // left
        one.write(text.toByteArray(Charsets.US_ASCII))
        if (!text.endsWith("\n")) one.write('\n'.code)
        one.write(feedAndCut())
        val single = one.toByteArray()

        if (copies <= 1) return single
        val out = ByteArrayOutputStream()
        repeat(copies.coerceAtLeast(1)) { out.write(single) }
        return out.toByteArray()
    }

    /**
     * Produce the exact 1-bit monochrome bitmap that would be printed (fit +
     * dither + trim). Used by the on-screen print preview so what you see is
     * what the printer emits. Black pixels = ink.
     */
    fun previewBitmap(
        src: Bitmap,
        maxWidth: Int = MAX_WIDTH,
        trim: Boolean = true,
        trimMargin: Int = 16,
    ): Bitmap {
        val mono = toMonochrome(src, maxWidth, trim, trimMargin)
        val h = mono.size
        val w = if (h > 0) mono[0].size else 0
        val bmp = Bitmap.createBitmap(maxOf(w, 1), maxOf(h, 1), Bitmap.Config.ARGB_8888)
        val px = IntArray(maxOf(w, 1) * maxOf(h, 1)) { Color.WHITE }
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (mono[y][x]) px[y * w + x] = Color.BLACK
            }
        }
        if (w > 0 && h > 0) bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    /** fit -> dither -> optional trim, returning boolean[h][w] (true = ink). */
    private fun toMonochrome(
        src: Bitmap, maxWidth: Int, trim: Boolean, trimMargin: Int
    ): Array<BooleanArray> {
        val fitted = fitWidth(src, if (maxWidth <= 0) MAX_WIDTH else maxWidth)
        var mono = ditherFloydSteinberg(fitted)
        if (trim) mono = trimWhitespace(mono, trimMargin)
        return mono
    }

    private fun feedAndCut(): ByteArray = byteArrayOf(
        ESC.toByte(), 0x64, 0x03,          // ESC d 3  feed 3 lines
        GS.toByte(), 0x56, 0x00            // GS V 0   full cut
    )

    /** Resize keeping aspect so width <= maxWidth. Narrower images pass through. */
    fun fitWidth(src: Bitmap, maxWidth: Int): Bitmap {
        if (src.width <= maxWidth) return src
        val scale = maxWidth.toFloat() / src.width
        val nh = Math.round(src.height * scale)
        return Bitmap.createScaledBitmap(src, maxWidth, nh, true)
    }

    /**
     * Floyd–Steinberg dithering to 1-bit. Returns boolean[height][width] where
     * true = black ink pixel.
     */
    fun ditherFloydSteinberg(bmp: Bitmap): Array<BooleanArray> {
        val w = bmp.width
        val h = bmp.height
        // luminance buffer 0(black)..255(white)
        val lum = FloatArray(w * h)
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val a = Color.alpha(c)
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            // composite over white so transparent -> white paper
            val fa = a / 255f
            val rr = r * fa + 255 * (1 - fa)
            val gg = g * fa + 255 * (1 - fa)
            val bb = b * fa + 255 * (1 - fa)
            lum[i] = 0.299f * rr + 0.587f * gg + 0.114f * bb
        }

        val out = Array(h) { BooleanArray(w) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val old = lum[i]
                val newV = if (old >= 128f) 255f else 0f
                out[y][x] = newV < 128f      // ink where black
                val err = old - newV
                // distribute error (7/16, 3/16, 5/16, 1/16)
                if (x + 1 < w) lum[i + 1] += err * 7f / 16f
                if (x - 1 >= 0 && y + 1 < h) lum[i - 1 + w] += err * 3f / 16f
                if (y + 1 < h) lum[i + w] += err * 5f / 16f
                if (x + 1 < w && y + 1 < h) lum[i + 1 + w] += err * 1f / 16f
            }
        }
        return out
    }

    /** Crop fully-white top/bottom rows; keep marginRows blank lines below content. */
    fun trimWhitespace(mono: Array<BooleanArray>, marginRows: Int): Array<BooleanArray> {
        val h = mono.size
        if (h == 0) return mono
        fun rowHasInk(y: Int): Boolean {
            for (v in mono[y]) if (v) return true
            return false
        }
        var top = 0
        while (top < h && !rowHasInk(top)) top++
        if (top == h) return mono   // fully blank
        var bottom = h - 1
        while (bottom > top && !rowHasInk(bottom)) bottom--
        bottom = minOf(bottom + marginRows, h - 1)
        val newH = bottom - top + 1
        if (newH >= h) return mono
        return Array(newH) { mono[top + it] }
    }

    /**
     * Encode each raster row as its own "GS v 0" command (1 dot tall). This is
     * the exact shape the daemon uses and the printer prints correctly.
     */
    fun rasterRows(mono: Array<BooleanArray>): ByteArray {
        val h = mono.size
        if (h == 0) return ByteArray(0)
        val w = mono[0].size
        val rowBytes = (w + 7) / 8
        val wl = (rowBytes and 0xFF).toByte()
        val wh = ((rowBytes shr 8) and 0xFF).toByte()

        val out = ByteArrayOutputStream()
        val header = byteArrayOf(GS.toByte(), 0x76, 0x30, 0x00, wl, wh, 0x01, 0x00)
        for (y in 0 until h) {
            out.write(header)
            val row = ByteArray(rowBytes)
            val line = mono[y]
            for (x in 0 until w) {
                if (line[x]) {
                    row[x / 8] = (row[x / 8].toInt() or (0x80 shr (x % 8))).toByte()
                }
            }
            out.write(row)
        }
        return out.toByteArray()
    }
}
