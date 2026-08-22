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

data class UiState(
    val connected: Boolean = false,
    val connectedMac: String? = null,
    val paired: List<BtDevice> = emptyList(),
    val busy: Boolean = false,
    val log: List<String> = emptyList(),
    val settings: PrintSettings = PrintSettings(),
)

class PrinterViewModel(app: Application) : AndroidViewModel(app) {

    private val printer = PrinterBluetooth()
    private val store = SettingsStore(app)

    private val _ui = MutableStateFlow(UiState(settings = store.load()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private fun log(msg: String) {
        _ui.value = _ui.value.copy(log = (_ui.value.log + msg).takeLast(50))
    }

    fun refreshPaired() {
        val list = try { printer.pairedDevices() } catch (e: Exception) { emptyList() }
        _ui.value = _ui.value.copy(paired = list)
        if (list.isEmpty()) log("Belum ada printer ter-pairing. Pairing dulu di Setelan Bluetooth HP.")
    }

    fun connect(mac: String) {
        setBusy(true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { printer.connect(mac) }
                val s = _ui.value.settings.copy(lastMac = mac)
                store.save(s)
                _ui.value = _ui.value.copy(
                    connected = true, connectedMac = mac, settings = s
                )
                log("Terhubung ke $mac")
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(connected = false, connectedMac = null)
                log("Gagal connect: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    fun disconnect() {
        printer.close()
        _ui.value = _ui.value.copy(connected = false, connectedMac = null)
        log("Terputus")
    }

    fun printText(text: String) = runPrint("teks") {
        EscPos.encodeText(if (text.isBlank()) "MP-80M TEST PRINT\nOK\n" else text)
    }

    fun printImage(uri: Uri) = runPrint("gambar") {
        val bmp = loadBitmap(uri) ?: throw IllegalStateException("Gagal baca gambar")
        val s = _ui.value.settings
        EscPos.encodeImage(bmp, trim = s.trim, trimMargin = s.trimMargin)
    }

    fun printPdf(uri: Uri) = runPrint("PDF") {
        val pages = DocRender.pdfToBitmaps(getApplication(), uri)
        if (pages.isEmpty()) throw IllegalStateException("PDF kosong")
        val s = _ui.value.settings
        val out = java.io.ByteArrayOutputStream()
        pages.forEachIndexed { i, bmp ->
            out.write(EscPos.encodeImage(bmp, trim = s.trim, trimMargin = s.trimMargin))
            if (i < pages.size - 1) {
                out.write(byteArrayOf(0x1B, 0x64, 0x03)) // small feed between pages
            }
        }
        out.toByteArray()
    }

    private fun runPrint(what: String, build: () -> ByteArray) {
        if (!printer.isConnected) { log("Printer belum terhubung"); return }
        setBusy(true)
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.Default) { build() }
                withContext(Dispatchers.IO) { printer.write(bytes) }
                log("Cetak $what OK (${bytes.size} bytes)")
            } catch (e: Exception) {
                log("Gagal cetak $what: ${e.message}")
            } finally {
                setBusy(false)
            }
        }
    }

    fun updateSettings(trim: Boolean, trimMargin: Int) {
        val s = _ui.value.settings.copy(trim = trim, trimMargin = trimMargin)
        store.save(s)
        _ui.value = _ui.value.copy(settings = s)
        log("Setelan disimpan (trim=$trim, margin=$trimMargin)")
    }

    private fun loadBitmap(uri: Uri): Bitmap? {
        return getApplication<Application>().contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it)
        }
    }

    private fun setBusy(b: Boolean) { _ui.value = _ui.value.copy(busy = b) }

    override fun onCleared() {
        printer.close()
        super.onCleared()
    }
}
