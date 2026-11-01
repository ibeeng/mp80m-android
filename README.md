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

## 📥 Download

**[⬇️ Download APK terbaru (v1.2.0)](https://github.com/ibeeng/mp80m-android/releases/latest/download/mp80m-printer-v1.2.0.apk)**

Atau lihat semua versi di halaman [**Releases**](https://github.com/ibeeng/mp80m-android/releases).

Setelah download di HP: buka file → install (izinkan "Install dari sumber tak
dikenal" bila diminta). Ukuran APK ±1 MB.

> **Memperbarui dari versi lama?** Cukup pasang di atas app yang sudah ada — tanda
> tangannya sama dengan v1.1.0, jadi tidak perlu dicopot dan setelan printer tetap
> tersimpan. **Cetak langsung dari web (`mp80m://print`) baru ada di v1.2.0**; v1.1.0
> tidak punya fitur ini, jadi tombol cetak di web tidak berefek.

---

## Fitur

- **Konek Bluetooth** ke printer (dari daftar perangkat yang sudah di-pairing)
- **Auto-reconnect** ke printer terakhir saat app dibuka
- **Cetak teks** (ASCII, font bawaan printer)
- **Cetak gambar** dari galeri (PNG/JPEG) — auto resize ke 576 dot, dither
- **Cetak PDF** multi-halaman (render via `PdfRenderer` bawaan Android)
- **Pratinjau sebelum cetak** — lihat hasil di layar sebelum keluar kertas
- **Cetak langsung dari web** (v1.2.0) — tap tombol di web (mis. admin TELBA) → kertas
  keluar tanpa membuka app, lewat link `mp80m://print?data=…` (lihat bagian di bawah)
- **Cetak via Share** — dari galeri / WhatsApp / PDF viewer, tap Share → MP-80M Printer
- **Template teks** — simpan format struk/label yang sering dipakai
- **Jumlah copy** 1–20 sekali cetak
- **Hemat kertas**: potong ruang kosong atas/bawah otomatis (bisa diatur)
- **Tema gelap/terang** mengikuti sistem
- **Setelan tersimpan**: printer terakhir, trim, margin, copy

---

## Persyaratan

- **Android 7.0 (API 24)** ke atas
- Printer sudah **di-pairing** ke HP via Setelan Bluetooth (sekali saja)
- Izin **Bluetooth** (diminta otomatis saat app dibuka pertama kali)

---

## Pemakaian

1. Pairing printer di **Setelan Bluetooth HP** (cari `RPP02N` / nama printer). Sekali saja.
2. Buka app **MP-80M Printer** → beri izin Bluetooth.
3. Pilih printer dari daftar → **Hubungkan** (berikutnya auto-reconnect).
4. Cetak: **Teks**, **Gambar (galeri)**, atau **PDF** — muncul **pratinjau** dulu
   sebelum benar-benar dicetak.
5. Atau dari app lain: **Share** gambar/PDF → pilih **MP-80M Printer**.
6. **Setelan** (opsional): potong ruang kosong, margin bawah, jumlah copy, auto-reconnect.

### Cetak langsung dari web (`mp80m://print`)

Web bisa mencetak ke printer dengan membuka link:

```
mp80m://print?data=<base64url(teks)>
```

- `data` = teks struk (ASCII) yang di-encode **base64url**. Byte kontrol ESC/POS
  (rata tengah, tebal, QR bawaan printer, potong kertas) ikut diteruskan apa adanya
  karena semuanya ASCII. Karakter non-ASCII tidak tercetak, jadi ganti dulu di sisi web.
- Ditangkap `PrintBridgeActivity` — tanpa tampilan, langsung `finish()`, pekerjaan cetak
  jalan di `PrintQueue`. Hasilnya muncul sebagai toast ("Struk tercetak" / "Gagal cetak: …").
- Link harus dibuka dari **gestur pengguna** (klik tombol/`<a>`) di **Chrome Android**.
  Browser dalam aplikasi (WhatsApp, Instagram, dll.) sering memblokir link seperti ini.
- **Syarat di tiap HP, sekali saja:** buka app → beri izin Bluetooth → pilih & hubungkan
  printer. Bila belum, app terbuka dan meminta ini lebih dulu (struk dibawa supaya bisa
  dicetak manual).
- Printer Bluetooth hanya melayani **satu HP sekaligus**; putuskan dari HP lain dulu.

Contoh dari JavaScript:

```js
const data = btoa('Halo\n\n\n').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
location.href = `mp80m://print?data=${data}`; // panggil dari handler klik
```

Dipakai oleh admin TELBA (nota & label pesanan) dan qasirku+.

### Merilis versi baru (untuk maintainer)

Setelah build APK, buat GitHub Release + upload APK:
```bash
bash make-release.sh   # baca token dari ~/.git-credentials, bikin tag + upload APK
```
Ubah `TAG` / `versionName` / `ASSET_NAME` di script + `versionName` & `versionCode` di
`app/build.gradle.kts` untuk versi berikutnya. Skrip mengunggah build **release**:
```bash
./gradlew assembleRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease
# -> app/build/outputs/apk/release/app-release.apk
```
(`-x lint…` hanya perlu bila offline; tanpa itu Gradle mengunduh lint dulu.)

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

# build APK debug (±16 MB, tanpa pengecilan)
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# build APK release (±1 MB, R8 minify + shrink resources)
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

> Gradle butuh **JDK 17 lengkap** (ada `javac`). Bila `JAVA_HOME` menunjuk ke JRE saja,
> build gagal dengan *"does not provide the required capabilities: [JAVA_COMPILER]"* —
> set `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`.

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
├── PrintBridgeActivity.kt penangkap link mp80m://print (tanpa UI)
├── PrintQueue.kt          antrean cetak level-proses + toast hasil
├── ReceiptData.kt         parser payload dari web (base64url, meta opsional)
└── SettingsStore.kt       simpan preferensi (SharedPreferences)
```

---

## Catatan teknis

- **Bluetooth Classic (SPP)**, bukan BLE — sesuai printer thermal MP-80M/RPP02N.
- Cetak **dipacing** (256 byte / 20ms) agar buffer kecil printer tidak overflow
  (overflow = cetakan "tulisan China" / garbage, sama seperti yang pernah terjadi
  di sisi daemon Linux; di Android SPP stream sudah raw jadi tidak ada translasi byte).
- **Batas Bluetooth ~10m**: HP harus dalam jangkauan printer saat mencetak.
- Rilis memakai build **release** (R8 + shrink resources) yang **ditandatangani dengan
  kunci debug** (`~/.android/debug.keystore`) supaya pembaruan bisa menimpa versi lama.
  Simpan/backup file keystore itu: bila hilang, HP harus mencopot app lama sebelum
  memasang yang baru. Cukup untuk pemakaian internal, bukan untuk Play Store.

---

## Lisensi

Penggunaan internal / pribadi.
