package com.ibeeng.mp80m

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth SPP (Serial Port Profile) link to the thermal printer.
 *
 * The printer advertises the classic SPP UUID 00001101-0000-1000-8000-00805F9B34FB.
 * We open an RFCOMM socket, then pace the write in small chunks — thermal BT
 * printers have a tiny receive buffer and drop bytes if flooded, which on the
 * Linux daemon showed up as garbage/"Chinese" output. Pacing keeps the stream
 * in sync. On Android the SPP OutputStream is a raw binary channel (no TTY
 * line-discipline), so bytes pass through untouched.
 */
class PrinterBluetooth {

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val CHUNK = 256
        const val GAP_MS = 20L

        /**
         * Satu socket untuk seluruh proses.
         *
         * PrintBridgeActivity (cetak tanpa UI) dan PrinterViewModel (UI app) HARUS
         * pakai instance yang sama — kalau masing-masing bikin socket sendiri,
         * printer nolak koneksi kedua dan cetak dari qasirku+ jadi gagal random.
         */
        val shared: PrinterBluetooth by lazy { PrinterBluetooth() }
    }

    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null
    var connectedMac: String? = null
        private set

    val isConnected: Boolean
        get() = socket?.isConnected == true

    /** Bonded (paired) devices — the printer must be paired once in Android settings. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<BtDevice> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        return adapter.bondedDevices.orEmpty().map {
            BtDevice(name = it.name ?: "(tanpa nama)", mac = it.address)
        }
    }

    fun isBluetoothOn(): Boolean =
        BluetoothAdapter.getDefaultAdapter()?.isEnabled == true

    /** Connect to a device by MAC. Closes any previous socket first. */
    @SuppressLint("MissingPermission")
    fun connect(mac: String) {
        close()
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: throw IllegalStateException("Bluetooth tidak tersedia")
        if (!adapter.isEnabled) throw IllegalStateException("Bluetooth mati")
        val device: BluetoothDevice = adapter.getRemoteDevice(mac)
        adapter.cancelDiscovery()

        val s = try {
            device.createRfcommSocketToServiceRecord(SPP_UUID)
        } catch (e: Exception) {
            throw IllegalStateException("Gagal membuat socket: ${e.message}")
        }
        try {
            s.connect()
        } catch (e: Exception) {
            // Fallback: reflection channel-1 socket for stubborn printers.
            try {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                val fallback = m.invoke(device, 1) as BluetoothSocket
                fallback.connect()
                socket = fallback
                out = fallback.outputStream
                connectedMac = mac
                return
            } catch (e2: Exception) {
                try { s.close() } catch (_: Exception) {}
                throw IllegalStateException("Gagal connect: ${e.message}")
            }
        }
        socket = s
        out = s.outputStream
        connectedMac = mac
    }

    /** Write raw bytes, paced in chunks so the printer buffer never overflows. */
    fun write(data: ByteArray) {
        val stream = out ?: throw IllegalStateException("Printer belum terhubung")
        var sent = 0
        while (sent < data.size) {
            val end = minOf(sent + CHUNK, data.size)
            stream.write(data, sent, end - sent)
            stream.flush()
            sent = end
            if (sent < data.size) {
                try { Thread.sleep(GAP_MS) } catch (_: InterruptedException) {}
            }
        }
        stream.flush()
    }

    fun close() {
        try { out?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        out = null
        socket = null
        connectedMac = null
    }
}

data class BtDevice(val name: String, val mac: String)
