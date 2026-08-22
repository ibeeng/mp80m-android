#!/usr/bin/env bash
# create a GitHub Release for mp80m-android and upload the APK as an asset.
set -uo pipefail

REPO="ibeeng/mp80m-android"
TAG="v1.1.0"
NAME="MP-80M Printer v1.1.0"
APK="$HOME/AndroidProjects/mp80m-android/app/build/outputs/apk/debug/app-debug.apk"
ASSET_NAME="mp80m-printer-v1.1.0.apk"

TOKEN=$(sed -n 's|https://[^:]*:\([^@]*\)@github.com|\1|p' "$HOME/.git-credentials")
if [ -z "$TOKEN" ]; then echo "NO_TOKEN"; exit 1; fi
[ -f "$APK" ] || { echo "APK_NOT_FOUND: $APK"; exit 1; }

api() { curl -s --max-time 60 -H "Authorization: token $TOKEN" -H "User-Agent: mp80m" -H "Accept: application/vnd.github+json" "$@"; }

NOTES=$(cat <<'MD'
Aplikasi Android mandiri untuk cetak ke printer thermal Bluetooth MP-80M / RPP02N.

## Cara pakai
1. Download **mp80m-printer-v1.1.0.apk** di bawah (Assets).
2. Install di HP (izinkan "Install dari sumber tak dikenal" bila diminta).
3. Pairing printer di Setelan Bluetooth HP (sekali saja).
4. Buka app -> beri izin Bluetooth -> pilih printer -> Hubungkan.

## Fitur
- Cetak teks, gambar (galeri), dan PDF multi-halaman
- Auto-reconnect ke printer terakhir
- Pratinjau sebelum cetak
- Cetak via Share dari app lain (galeri / WhatsApp / PDF viewer)
- Template teks tersimpan
- Jumlah copy 1-20
- Hemat kertas: potong ruang kosong otomatis
- Dark / light theme mengikuti sistem

> APK debug untuk pemakaian internal. Butuh Android 7.0+.
MD
)

# build JSON body safely via python
BODY=$(TAG="$TAG" NAME="$NAME" NOTES="$NOTES" python3 - <<'PY'
import json, os
print(json.dumps({
    "tag_name": os.environ["TAG"],
    "name": os.environ["NAME"],
    "body": os.environ["NOTES"],
    "draft": False,
    "prerelease": False,
}))
PY
)

echo ">> creating release $TAG ..."
RESP=$(api -X POST -d "$BODY" "https://api.github.com/repos/$REPO/releases")
UPLOAD_URL=$(echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('upload_url','').split('{')[0]); " 2>/dev/null)
REL_HTML=$(echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('html_url','')); " 2>/dev/null)

if [ -z "$UPLOAD_URL" ]; then
  echo "RELEASE_CREATE_FAILED:"
  echo "$RESP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('message'), d.get('errors'))" 2>/dev/null || echo "$RESP" | head -c 400
  exit 1
fi

echo ">> uploading APK asset ..."
UP=$(curl -s --max-time 300 -H "Authorization: token $TOKEN" -H "User-Agent: mp80m" \
     -H "Content-Type: application/vnd.android.package-archive" \
     --data-binary @"$APK" \
     "${UPLOAD_URL}?name=${ASSET_NAME}")
DL=$(echo "$UP" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('browser_download_url','')); " 2>/dev/null)

echo ""
echo "RELEASE_PAGE: $REL_HTML"
echo "APK_DOWNLOAD: $DL"
