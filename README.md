# MP-80M Printer — Android App

Aplikasi Android mandiri untuk mencetak ke printer thermal Bluetooth **MP-80M**
(juga RPP02N / Zijiang-class, 80mm). HP langsung terhubung ke printer via Bluetooth
SPP — **tidak butuh laptop, server, atau koneksi internet**.

> Dibuat karena app printer thermal yang beredar rata-rata terbatas / berwatermark /
> memaksa beli pro. Ini pengganti bebas untuk pemakaian internal.

Protokol cetaknya di-port dari daemon Linux yang sudah terbukti
([github.com/ibeeng/mp80m-printer](https://github.com/ibeeng/mp80m-printer)):
encoder ESC/POS `GS v 0` raster + Floyd–Steinberg dither + trim whitespace.

---

## Fitur

- **Konek Bluetooth** ke printer (dari daftar perangkat yang sudah di-pairing)
- **Cetak teks** (ASCII, font bawaan printer)
- **Cetak gambar** dari galeri (PNG/JPEG) — auto resize ke 576 dot, dither
- **Cetak PDF** multi-halaman (render via `PdfRenderer` bawaan Android)
- **Hemat kertas**: potong ruang kosong atas/bawah otomatis (bisa diatur)
- **Setelan tersimpan**: printer terakhir, trim on/off, margin bawah

---

## Persyaratan

- **Android 7.0 (API 24)** ke atas
- Printer sudah **di-pairing** ke HP via Setelan Bluetooth (sekali saja)
- Izin **Bluetooth** (diminta otomatis saat app dibuka pertama kali)

---

## Pemakaian

1. Pairing printer di **Setelan Bluetooth HP** (cari `RPP02N` / nama printer). Sekali saja.
2. Buka app **MP-80M Printer** → beri izin Bluetooth.
3. Pilih printer dari daftar → **Hubungkan**.
4. Cetak: **Teks**, **Gambar (galeri)**, atau **PDF**.
5. **Setelan** (opsional): aktif/matikan "Potong ruang kosong" + atur sisa margin bawah.

---

## Build dari source

Butuh **Android Studio** (atau Android SDK + JDK 17) dan koneksi internet untuk
mengunduh dependensi.

### Via Android Studio
Buka folder project → tunggu Gradle sync → **Build > Build APK(s)** atau **Run**.

### Via command line
```bash
# set path SDK (sesuaikan)
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# build APK debug
./gradlew assembleDebug

# APK ada di:
# app/build/outputs/apk/debug/app-debug.apk
```

Install ke HP yang terhubung ADB:
```bash
./gradlew installDebug
# atau: adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Struktur

```
app/src/main/java/com/ibeeng/mp80m/
├── MainActivity.kt        UI Jetpack Compose (status, printer, cetak, setelan)
├── PrinterViewModel.kt    state + orkestrasi cetak (coroutines)
├── PrinterBluetooth.kt    SPP socket (UUID 00001101), connect, write ter-pace
├── EscPos.kt              encoder ESC/POS: fit + dither + trim + GS v 0
├── DocRender.kt           render PDF -> bitmap (PdfRenderer bawaan)
└── SettingsStore.kt       simpan preferensi (SharedPreferences)
```

---

## Catatan teknis

- **Bluetooth Classic (SPP)**, bukan BLE — sesuai printer thermal MP-80M/RPP02N.
- Cetak **dipacing** (256 byte / 20ms) agar buffer kecil printer tidak overflow
  (overflow = cetakan "tulisan China" / garbage, sama seperti yang pernah terjadi
  di sisi daemon Linux; di Android SPP stream sudah raw jadi tidak ada translasi byte).
- **Batas Bluetooth ~10m**: HP harus dalam jangkauan printer saat mencetak.
- APK **debug** untuk internal/testing. Untuk distribusi, buat build **release** signed.

---

## Lisensi

Penggunaan internal / pribadi.
