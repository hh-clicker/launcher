#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 5 ]]; then
  echo 'Usage: build-linux-appimage.sh <application-image> <AppDir> <icon.png> <output.AppImage> <version>' >&2
  exit 2
fi

application_image="$(realpath "$1")"
appdir="$(realpath -m "$2")"
icon="$(realpath "$3")"
output="$(realpath -m "$4")"
version="$5"
launcher="$(dirname "$(dirname "${BASH_SOURCE[0]}")")"
build_root="$(realpath -m "$launcher/desktopApp/build")"

case "$appdir" in "$build_root"/*) ;; *) echo 'AppDir must be inside desktopApp/build' >&2; exit 2 ;; esac
case "$output" in "$build_root"/*) ;; *) echo 'Output must be inside desktopApp/build' >&2; exit 2 ;; esac
[[ -x "$application_image/bin/HH Clicker Launcher" ]] || { echo 'Packaged Linux launcher is missing' >&2; exit 1; }
[[ -f "$application_image/lib/app/resources/.hh-clicker-bundle" ]] || { echo 'Service payload is missing' >&2; exit 1; }

tool="${APPIMAGETOOL:-}"
if [[ -z "$tool" ]]; then
  if command -v appimagetool >/dev/null 2>&1; then tool="$(command -v appimagetool)"
  elif [[ -x "$HOME/.local/bin/appimagetool" ]]; then tool="$HOME/.local/bin/appimagetool"
  else echo 'appimagetool is missing; put it in ~/.local/bin or set APPIMAGETOOL' >&2; exit 1
  fi
fi

rm -rf -- "$appdir"
mkdir -p -- "$appdir/usr/lib/hh-clicker" "$(dirname "$output")"
cp -a -- "$application_image/." "$appdir/usr/lib/hh-clicker/"
cp -- "$icon" "$appdir/hh-clicker.png"
cat > "$appdir/hh-clicker.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=HH Clicker
Exec=AppRun
Icon=hh-clicker
Categories=Utility;
Terminal=false
X-AppImage-Version=$version
EOF
cat > "$appdir/AppRun" <<'EOF'
#!/usr/bin/env bash
set -e
appdir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
exec "$appdir/usr/lib/hh-clicker/bin/HH Clicker Launcher" "$@"
EOF
chmod 755 "$appdir/AppRun"

ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$tool" "$appdir" "$output"
echo "AppImage ready: $output"
