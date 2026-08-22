package com.ibeeng.mp80m

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream

/**
 * Renders documents (images + PDF) to Bitmaps for printing, using only
 * Android's built-in APIs (PdfRenderer). No external dependencies.
 */
object DocRender {

    /** Target render width in px — matches the printer's 576-dot printable width. */
    const val RENDER_WIDTH = EscPos.MAX_WIDTH

    /**
     * Rasterize every page of a PDF to a Bitmap sized to the print width.
     * Pages are rendered on a white background so transparency -> paper.
     */
    fun pdfToBitmaps(context: Context, uri: Uri): List<Bitmap> {
        // Copy to a seekable local file (PdfRenderer needs a real FD).
        val tmp = File.createTempFile("print", ".pdf", context.cacheDir)
        context.contentResolver.openInputStream(uri).use { input ->
            FileOutputStream(tmp).use { output ->
                input?.copyTo(output)
            }
        }
        val out = mutableListOf<Bitmap>()
        val pfd = ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(pfd).use { renderer ->
            for (i in 0 until renderer.pageCount) {
                renderer.openPage(i).use { page ->
                    val scale = RENDER_WIDTH.toFloat() / page.width
                    val w = RENDER_WIDTH
                    val h = Math.round(page.height * scale)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    out.add(bmp)
                }
            }
        }
        pfd.close()
        tmp.delete()
        return out
    }
}
