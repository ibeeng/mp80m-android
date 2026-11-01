package com.ibeeng.mp80m

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Jembatan cetak tanpa UI.
 *
 * qasirku+ manggil `mp80m://print?data=<base64url>` dari Chrome. Activity ini
 * yang nangkep — BUKAN MainActivity — supaya layar app gak pernah kelihatan:
 *
 *   - theme `Theme.MP80M.Invisible` -> `android:windowNoDisplay=true`,
 *     jadi window-nya gak pernah dirender sama sekali (bukan cuma transparan).
 *   - `finish()` dipanggil di onCreate. WAJIB untuk windowNoDisplay: activity
 *     yang gak nampilin window tapi lanjut ke onResume bikin Android nge-crash.
 *   - kerjaan cetak dilempar ke [PrintQueue] yang scope-nya level-proses, jadi
 *     tetap jalan walau activity-nya udah mati.
 *   - `noHistory` + `excludeFromRecents` + `taskAffinity=""` bikin dia gak nongol
 *     di Recents dan gak numpuk di task MainActivity.
 *
 * Syarat yang butuh campur tangan user (izin BT belum ada / printer belum pernah
 * dipilih) dicek SINKRON di sini, sebelum finish(). Alasannya: startActivity dari
 * callback async setelah activity mati kena blokir background-activity-launch di
 * Android 10+, jadi user gak bakal lihat apa-apa.
 *
 * Efek buat kasir: tap tombol di Chrome -> kertas keluar. Layar gak pindah.
 */
class PrintBridgeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        finish()
    }

    private fun handle(intent: Intent?) {
        val data = intent?.data
        if (data == null || !data.scheme.equals("mp80m", ignoreCase = true)) return

        val encoded = data.getQueryParameter("data")
        if (encoded.isNullOrBlank()) {
            PrintQueue.toast(this, "Struk kosong")
            return
        }

        val text = try {
            String(ReceiptParser.base64UrlDecode(encoded), Charsets.UTF_8)
        } catch (e: Exception) {
            PrintQueue.toast(this, "Struk rusak: ${e.message}")
            return
        }

        // --- Pre-check sinkron: hal-hal yang cuma bisa dibenerin user di UI app ---
        if (!PrintQueue.hasBtPermission(this)) {
            PrintQueue.toast(this, "Beri izin Bluetooth dulu di app MP-80M", long = true)
            openMainWithText(text)
            return
        }
        if (SettingsStore(this).load().lastMac.isNullOrBlank()) {
            PrintQueue.toast(this, "Pilih printer dulu di app MP-80M", long = true)
            openMainWithText(text)
            return
        }

        // --- Cetak: async, tanpa UI. Gagal = toast aja, kasir bisa tap ulang. ---
        PrintQueue.submit(this, text) { result ->
            when (result) {
                is PrintQueue.Result.Ok -> PrintQueue.toast(this, "Struk tercetak")
                is PrintQueue.Result.Fail ->
                    PrintQueue.toast(this, "Gagal cetak: ${result.message}", long = true)
            }
        }
    }

    /** Fallback: buka app + bawa struknya biar user bisa cetak manual. */
    private fun openMainWithText(text: String) {
        val i = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra(EXTRA_PENDING_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(i)
    }

    companion object {
        const val EXTRA_PENDING_TEXT = "com.ibeeng.mp80m.PENDING_TEXT"
    }
}
