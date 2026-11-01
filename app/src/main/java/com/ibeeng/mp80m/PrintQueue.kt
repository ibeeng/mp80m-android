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
        val app = ctx.applicationContext
        if (text.isBlank()) {
            post { onResult(Result.Fail("Struk kosong", needsUi = false)) }
            return
        }
        if (!hasBtPermission(app)) {
            post { onResult(Result.Fail("Izin Bluetooth belum diberikan", needsUi = true)) }
            return
        }
        scope.launch {
            val result = lock.withLock { printNow(app, text) }
            post { onResult(result) }
        }
    }

    private fun printNow(app: Context, text: String): Result {
        val printer = PrinterBluetooth.shared
        val settings = SettingsStore(app).load()
        return try {
            if (!printer.isConnected) {
                val mac = settings.lastMac
                if (mac.isNullOrBlank()) {
                    return Result.Fail("Printer belum pernah dipilih", needsUi = true)
                }
                printer.connect(mac)
            }
            val copies = settings.copies.coerceIn(1, 20)
            val bytes = EscPos.encodeText(text, copies)
            printer.write(bytes)
            Result.Ok(bytes.size, copies)
        } catch (e: Exception) {
            // Socket besar kemungkinan sudah setengah mati — tutup biar percobaan
            // berikutnya connect dari nol, bukan nulis ke stream basi.
            printer.close()
            Result.Fail(e.message ?: "gagal cetak", needsUi = true)
        }
    }

    private fun post(block: () -> Unit) = Handler(Looper.getMainLooper()).post(block)

    fun toast(ctx: Context, msg: String, long: Boolean = false) {
        Toast.makeText(
            ctx.applicationContext, msg,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }
}
