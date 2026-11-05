#!/usr/bin/env bash
set -euo pipefail
source_png="$1"
output_icns="$2"
mkdir -p "$(dirname "$output_icns")"
iconset="$(mktemp -d "$(dirname "$output_icns")/hh-clicker.XXXXXX")/hh-clicker.iconset"
mkdir -p "$iconset"
trap 'rm -rf "$(dirname "$iconset")"' EXIT
for size in 16 32 128 256 512; do
  /usr/bin/sips -z "$size" "$size" "$source_png" --out "$iconset/icon_${size}x${size}.png" >/dev/null
  double=$((size * 2))
  /usr/bin/sips -z "$double" "$double" "$source_png" --out "$iconset/icon_${size}x${size}@2x.png" >/dev/null
done
/usr/bin/iconutil -c icns "$iconset" -o "$output_icns"
