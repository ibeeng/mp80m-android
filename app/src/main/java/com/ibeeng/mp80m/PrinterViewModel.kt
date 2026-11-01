package com.ibeeng.mp80m

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A job staged for preview before actually printing. */
sealed class PendingJob {
    abstract val previewBitmaps: List<Bitmap>
    abstract fun encode(settings: PrintSettings): ByteArray
    abstract val label: String

    class TextJob(val text: String, previews: List<Bitmap>) : PendingJob() {
        override val previewBitmaps = previews
        override val label = "teks"
        override fun encode(settings: PrintSettings) =
            EscPos.encodeText(text.ifBlank { "MP-80M TEST PRINT\nOK\n" }, settings.copies)
    }

    class ImageJob(val bitmaps: List<Bitmap>, previews: List<Bitmap>) : PendingJob() {
        override val previewBitmaps = previews
        override val label = if (bitmaps.size > 1) "PDF (${bitmaps.size} hal)" else "gambar"
        override fun encode(settings: PrintSettings): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            repeat(settings.copies.coerceAtLeast(1)) {
                bitmaps.forEachIndexed { i, bmp ->
                    out.write(EscPos.encodeImage(bmp, trim = settings.trim, trimMargin = settings.trimMargin))
                    if (i < bitmaps.size - 1) out.write(byteArrayOf(0x1B, 0x64, 0x03))
                }
            }
            return out.toByteArray()
        }
    }
}

data class UiState(
    val connected: Boolean = false,
    val connectedMac: String? = null,
    val connecting: Boolean = false,
    val paired: List<BtDevice> = emptyList(),
    val busy: Boolean = false,
    val log: List<String> = emptyList(),
    val settings: PrintSettings = PrintSettings(),
    val templates: List<Template> = emptyList(),
    val pending: PendingJob? = null,   // staged for preview
)

class PrinterViewModel(app: Application) : AndroidViewModel(app) {

    private val printer = PrinterBluetooth.shared
    private val store = SettingsStore(app)

    private val _ui = MutableStateFlow(
        UiState(settings = store.load(), templates = store.loadTemplates())
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private fun log(msg: String) {
        _ui.value = _ui.value.copy(log = (_ui.value.log + msg).takeLast(50))
    }

    fun refreshPaired() {
        val list = try { printer.pairedDevices() } catch (e: Exception) { emptyList() }
        _ui.value = _ui.value.copy(paired = list)
        if (list.isEmpty()) log("Belum ada printer ter-pairing. Pairing dulu di Setelan Bluetooth HP.")
    }

    /** Called once permissions are granted: refresh list + auto-reconnect. */
    fun onReady() {
        refreshPaired()
        val s = _ui.value.settings
        if (s.autoReconnect && !s.lastMac.isNullOrBlank() && !printer.isConnected) {
            log("Auto-reconnect ke ${s.lastMac}…")
            connect(s.lastMac)
        }
    }

    fun connect(mac: String) {
        _ui.value = _ui.value.copy(connecting = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { printer.connect(mac) }
                val s = _ui.value.settings.copy(lastMac = mac)
                store.save(s)
                _ui.value = _ui.value.copy(connected = true, connectedMac = mac, settings = s)
                log("Terhubung ke $mac")
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(connected = false, connectedMac = null)
                log("Gagal connect: ${e.message}")
            } finally {
                _ui.value = _ui.value.copy(connecting = false)
            }
        }
    }

    fun disconnect() {
        printer.close()
        _ui.value = _ui.value.copy(connected = false, connectedMac = null)
        log("Terputus")
    }

    // --- Staging (build preview, don't print yet) ---

    fun stageText(text: String) {
        val s = _ui.value.settings
        viewModelScope.launch {
            val body = text.ifBlank { "MP-80M TEST PRINT\nOK\n" }
            // Text preview: we don't render the built-in font; show the raw text as-is.
            _ui.value = _ui.value.copy(pending = PendingJob.TextJob(body, emptyList()))
            log("Siap cetak teks — cek pratinjau")
        }
    }

    fun stageImage(uri: Uri) {
        setBusy(true)
        viewModelScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { loadBitmap(uri) }
                    ?: throw IllegalStateException("Gagal baca gambar")
                val s = _ui.value.settings
                val preview = withContext(Dispatchers.Default) {
                    EscPos.previewBitmap(bmp, trim = s.trim, trimMargin = s.trimMargin)
                }
                _ui.value = _ui.value.copy(
                    pending = PendingJob.ImageJob(listOf(bmp), listOf(preview))
                )
                log("Siap cetak gambar — cek pratinjau")
            } catch (e: Exception) {
                log("Gagal siapkan gambar: ${e.message}")
            } finally { setBusy(false) }
        }
    }

    fun stagePdf(uri: Uri) {
        setBusy(true)
        viewModelScope.launch {
            try {
                val pages = withContext(Dispatchers.IO) {
                    DocRender.pdfToBitmaps(getApplication(), uri)
                }
                if (pages.isEmpty()) throw IllegalStateException("PDF kosong")
                val s = _ui.value.settings
                val previews = withContext(Dispatchers.Default) {
                    pages.map { EscPos.previewBitmap(it, trim = s.trim, trimMargin = s.trimMargin) }
                }
                _ui.value = _ui.value.copy(pending = PendingJob.ImageJob(pages, previews))
                log("Siap cetak PDF (${pages.size} hal) — cek pratinjau")
            } catch (e: Exception) {
                log("Gagal siapkan PDF: ${e.message}")
            } finally { setBusy(false) }
        }
    }

    fun cancelPending() {
        _ui.value = _ui.value.copy(pending = null)
    }

    /** Actually send the staged job to the printer. */
    fun confirmPrint() {
        val job = _ui.value.pending ?: return
        if (!printer.isConnected) { log("Printer belum terhubung"); return }
        setBusy(true)
        viewModelScope.launch {
            try {
                val s = _ui.value.settings
                val bytes = withContext(Dispatchers.Default) { job.encode(s) }
                withContext(Dispatchers.IO) { printer.write(bytes) }
                log("Cetak ${job.label} OK (${bytes.size} bytes, ${s.copies}x)")
                _ui.value = _ui.value.copy(pending = null)
            } catch (e: Exception) {
                log("Gagal cetak: ${e.message}")
            } finally { setBusy(false) }
        }
    }

    /**
     * Cetak langsung dari deeplink qasirku+ (mp80m://print?data=...).
     * Tanpa pratinjau: auto-connect ke printer terakhir lalu kirim byte-nya.
     * Kalau belum ada printer terakhir / gagal connect, teks di-stage ke
     * pratinjau biasa supaya user tetap bisa cetak manual.
     */
    fun queueDirectPrint(text: String) {
        if (text.isBlank()) return
        val s = _ui.value.settings
        log("Struk masuk dari qasirku+ (${text.lines().size} baris)")
        setBusy(true)
        viewModelScope.launch {
            try {
                if (!printer.isConnected) {
                    if (s.lastMac.isNullOrBlank()) {
                        log("Belum ada printer terakhir — buka pratinjau")
                        _ui.value = _ui.value.copy(
                            pending = PendingJob.TextJob(text, emptyList())
                        )
                        return@launch
                    }
                    log("Auto-connect ${s.lastMac}...")
                    withContext(Dispatchers.IO) { printer.connect(s.lastMac) }
                    _ui.value = _ui.value.copy(
                        connected = true, connectedMac = s.lastMac
                    )
                }
                val copies = s.copies.coerceIn(1, 20)
                val bytes = withContext(Dispatchers.Default) {
                    EscPos.encodeText(text, copies)
                }
                withContext(Dispatchers.IO) { printer.write(bytes) }
                log("Struk qasirku+ tercetak (${bytes.size} bytes, ${copies}x)")
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    connected = false, connectedMac = null,
                    pending = PendingJob.TextJob(text, emptyList())
                )
                log("Cetak langsung gagal: ${e.message} — buka pratinjau")
            } finally { setBusy(false) }
        }
    }

    // --- Settings ---

    fun updateSettings(trim: Boolean, trimMargin: Int, autoReconnect: Boolean, copies: Int) {
        val s = _ui.value.settings.copy(
            trim = trim, trimMargin = trimMargin,
            autoReconnect = autoReconnect, copies = copies.coerceIn(1, 20)
        )
        store.save(s)
        _ui.value = _ui.value.copy(settings = s)
        log("Setelan disimpan")
    }

    // --- Templates ---

    fun addTemplate(name: String, body: String) {
        if (name.isBlank()) { log("Nama template kosong"); return }
        store.addTemplate(Template(name.trim(), body))
        _ui.value = _ui.value.copy(templates = store.loadTemplates())
        log("Template '$name' disimpan")
    }

    fun removeTemplate(name: String) {
        store.removeTemplate(name)
        _ui.value = _ui.value.copy(templates = store.loadTemplates())
        log("Template '$name' dihapus")
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return getApplication<Application>().contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it)
        }
    }

    private fun setBusy(b: Boolean) { _ui.value = _ui.value.copy(busy = b) }

    override fun onCleared() {
        // JANGAN close() di sini: socket-nya milik proses (PrinterBluetooth.shared)
        // dan dipakai juga oleh PrintBridgeActivity. Nutup di sini bikin cetak
        // dari qasirku+ gagal tiap kali UI app pernah dibuka lalu ditutup.
        super.onCleared()
    }
}
