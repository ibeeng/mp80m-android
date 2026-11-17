package com.ibeeng.mp80m

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Antrian cetak level-proses (bukan level-Activity).
 *
 * Dipakai PrintBridgeActivity supaya struk dari qasirku+ bisa dicetak TANPA
 * nampilin UI app: activity-nya invisible + langsung finish, sedangkan kerjaan
 * connect/encode/write jalan di scope milik proses ini.
 *
 * Kenapa Mutex: bridge bisa ketembak dua kali (double tap) — tanpa lock, dua
 * coroutine bakal rebutan socket SPP yang sama dan output printer jadi kacau.
 */
object PrintQueue {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    /** Hasil satu job cetak, buat feedback ke pemanggil (toast / fallback UI). */
    sealed class Result {
        data class Ok(val bytes: Int, val copies: Int) : Result()
        /** Gagal — [needsUi] true kalau user harus buka app (pilih printer / izin). */
        data class Fail(val message: String, val needsUi: Boolean) : Result()
    }

    fun hasBtPermission(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else true

    /**
     * Cetak teks struk. [onResult] dipanggil di main thread.
     * Aman dipanggil dari Activity yang langsung finish: kerjaannya gak ikut mati.
     */
    fun submit(ctx: Context, text: String, onResult: (Result) -> Unit = {}) {
        if (text.isBlank()) {
            post { onResult(Result.Fail("Struk kosong", needsUi = false)) }
            return
        }
        enqueue(ctx, onResult) { _, s -> EscPos.encodeText(text, s.copies.coerceIn(1, 20)) }
    }

    /** Unduh PDF (label resi) dari [url] https lalu cetak semua halamannya. */
    fun submitPdf(ctx: Context, url: String, onResult: (Result) -> Unit = {}) =
        enqueue(ctx, onResult) { app, s -> pdfBytes(app, url, s) }

    private fun enqueue(
        ctx: Context, onResult: (Result) -> Unit, encode: (Context, PrintSettings) -> ByteArray
    ) {
        val app = ctx.applicationContext
        if (!hasBtPermission(app)) {
            post { onResult(Result.Fail("Izin Bluetooth belum diberikan", needsUi = true)) }
            return
        }
        scope.launch {
            val result = lock.withLock { printNow(app, encode) }
            post { onResult(result) }
        }
    }

    private fun pdfBytes(app: Context, url: String, s: PrintSettings): ByteArray {
        // Hanya https: tautan dari halaman web mana pun tidak boleh menyuruh app
        // mengambil alamat http/jaringan lokal.
        val conn = URL(url).openConnection() as? HttpsURLConnection
            ?: throw IllegalArgumentException("Tautan label harus https")
        val tmp = File.createTempFile("label", ".pdf", app.cacheDir)
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            if (conn.responseCode != 200) {
                throw IllegalStateException("Unduh label gagal (HTTP ${conn.responseCode})")
            }
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_PDF_BYTES) throw IllegalStateException("Berkas label terlalu besar")
                        out.write(buf, 0, n)
                    }
                }
            }
            val out = ByteArrayOutputStream()
            for (page in DocRender.pdfToBitmaps(tmp)) {
                out.write(
                    EscPos.encodeImage(
                        page, trim = s.trim, trimMargin = s.trimMargin,
                        copies = s.copies.coerceIn(1, 20), crisp = true,
                    )
                )
            }
            if (out.size() == 0) throw IllegalStateException("Label kosong")
            return out.toByteArray()
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }

    private fun printNow(app: Context, encode: (Context, PrintSettings) -> ByteArray): Result {
        val printer = PrinterBluetooth.shared
        val settings = SettingsStore(app).load()
        // Siapkan dulu (bisa mengunduh): gagal di sini tidak boleh memutus printer.
        val bytes = try {
            encode(app, settings)
        } catch (e: Exception) {
            return Result.Fail(e.message ?: "gagal menyiapkan cetakan", needsUi = false)
        }
        return try {
            if (!printer.isConnected) {
                val mac = settings.lastMac
                if (mac.isNullOrBlank()) {
                    return Result.Fail("Printer belum pernah dipilih", needsUi = true)
                }
                printer.connect(mac)
            }
            printer.write(bytes)
            Result.Ok(bytes.size, settings.copies.coerceIn(1, 20))
        } catch (e: Exception) {
            // Socket besar kemungkinan sudah setengah mati — tutup biar percobaan
            // berikutnya connect dari nol, bukan nulis ke stream basi.
            printer.close()
            Result.Fail(e.message ?: "gagal cetak", needsUi = true)
        }
    }

    private const val MAX_PDF_BYTES = 10 * 1024 * 1024

    private fun post(block: () -> Unit) = Handler(Looper.getMainLooper()).post(block)

    fun toast(ctx: Context, msg: String, long: Boolean = false) {
        Toast.makeText(
            ctx.applicationContext, msg,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }
}
