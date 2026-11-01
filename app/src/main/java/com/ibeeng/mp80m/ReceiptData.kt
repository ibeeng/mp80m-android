package com.ibeeng.mp80m

import org.json.JSONArray
import org.json.JSONObject

/**
 * Data struk yang dikirim dari web (qasirku+) ke app ini lewat custom scheme:
 *
 *   mp80m://print?data=<base64url(text)>
 *
 * Text-nya adalah output dari buildThermalText() di src/utils/btprint.js — plain
 * text 32-kolom. Kita parse item & meta dari teks JSON berikut (dikirim sebagai
 * param `meta` untuk menjaga konsistensi antar device; kalau tidak ada, kita
 * cetak teks mentahnya apa adanya).
 */
data class ReceiptItem(
    val name: String,
    val desc: String,
    val price: String,
)

data class ReceiptData(
    val header: String,
    val lines: List<String>,          // sudah terformat 32-kolom (atau teks mentah)
    val items: List<ReceiptItem>,     // opsional, buat formatter bikin sendiri
    val meta: JSONObject?,            // opsional: {namaToko, subtitle, footer, tanggal, sumber, metode, wa, nota, keterangan, uangDiterima, items:[...]}
) {
    val isBlank: Boolean get() = header.isBlank() && lines.isEmpty()
}

/**
 * Pure-Kotlin parser untuk payload dari web.
 */
object ReceiptParser {

    /** Base64url → bytes. Bisa juga menerima base64 standar (dengan + / =). */
    fun base64UrlDecode(s: String): ByteArray {
        var t = s.replace('-', '+').replace('_', '/')
        while (t.length % 4 != 0) t += "="
        return android.util.Base64.decode(t, android.util.Base64.DEFAULT)
    }

    fun parse(dataB64: String): ReceiptData {
        val bytes = try { base64UrlDecode(dataB64) } catch (e: Exception) {
            return ReceiptData("", listOf(dataB64), emptyList(), null)
        }
        val text = String(bytes, Charsets.UTF_8)
        // Kalau teks-nya ternyata JSON objek dengan field meta/items, pakai itu.
        val lines = text.split("\n")
        val meta = try {
            val o = JSONObject(text)
            if (o.has("meta")) o.getJSONObject("meta") else null
        } catch (e: Exception) { null }
        val header = meta?.optString("namaToko", "") ?: ""
        return ReceiptData(header, lines, emptyList(), meta)
    }
}

/**
 * Formatter struk thermal 32-kolom, pure-Kotlin — MIRROR dari buildThermalText()
 * di web (src/utils/btprint.js). Dipakai kalau app mau bikin ulang dari meta
 * JSON (misal cek konsistensi / debug), atau kalau payload cuma bawa meta.
 */
object ReceiptFormatter {

    private const val W = 32

    fun rupiah(n: Long): String {
        val neg = n < 0
        val s = Math.abs(n).toString().reversed().chunked(3).joinToString(".").reversed()
        return (if (neg) "-" else "") + "Rp " + s
    }

    private fun center(s: String): String {
        val pad = (W - s.length).coerceAtLeast(0)
        val l = pad / 2
        return " ".repeat(l) + s + " ".repeat(pad - l)
    }
    private fun right(s: String): String = " ".repeat((W - s.length).coerceAtLeast(0)) + s
    private fun left(s: String): String = s + " ".repeat((W - s.length).coerceAtLeast(0))

    fun fromMeta(meta: JSONObject?): String {
        if (meta == null) return ""
        val sb = StringBuilder()
        fun add(l: String) { sb.append(l).append("\n") }

        add("")
        add(center(meta.optString("namaToko", "TOKO")))
        if (meta.has("subtitle")) add(center(meta.optString("subtitle")))
        add("\u2501".repeat(W)) // ━━

        add("No " + left(meta.optString("nota", "-")))
        add("Tgl: " + left(meta.optString("tanggal", "")))
        add("Sumber: " + left(meta.optString("sumber", "-")))
        add("Bayar: " + meta.optString("metode", "-"))
        if (meta.has("wa")) add("WA: " + meta.optString("wa"))
        add("\u2500".repeat(W)) // ──

        val items = meta.optJSONArray("items")
        if (items != null) {
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i)
                val name = (it.optString("brand") + " " + it.optString("varian")).trim()
                add(if (name.length > W) name.substring(0, W) else left(name))
                val desc = "  " + it.optString("tipeProduk") + " " + it.optString("volumeLabel") + " x" + it.optString("qty")
                val price = rupiah(it.optLong("hargaJual"))
                val avail = W - price.length - 1
                add(if (desc.length > avail) desc.substring(0, avail) + " " + price else left(desc) + " " + price)
            }
        }
        add("\u2500".repeat(W))
        add("TOTAL" + right(rupiah(meta.optLong("total"))))
        if (meta.optString("metode") == "Cash") {
            add("Tunai" + right(rupiah(meta.optLong("uangDiterima"))))
            add("Kembali" + right(rupiah(meta.optLong("uangDiterima") - meta.optLong("total"))))
        }
        if (meta.has("keterangan") && meta.optString("keterangan").isNotBlank()) {
            add("\u2500".repeat(W))
            add("Note: " + meta.optString("keterangan"))
        }
        add("\u2501".repeat(W))
        if (meta.has("footer")) {
            for (f in meta.optString("footer").split("\n")) {
                val t = f.trim()
                if (t.isNotEmpty()) add(center(t))
            }
        }
        add("")
        add("")
        return sb.toString().trimEnd('\n') + "\n"
    }
}