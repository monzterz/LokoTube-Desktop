#!/usr/bin/env bash
# LokoTube's AppImage, from the app folder `./gradlew -p desktop createDistributable` makes on Linux.
#   make_appimage.sh <app folder> <icon.png> <output .AppImage> [appimagetool]
# The menu entry and the window's class are both lokotube-LokoTube (window/LinuxDesktop.kt), so the
# dock shows LokoTube's icon on the running window.
set -euo pipefail

app="$1"
icon="$2"
out="$3"
tool="${4:-appimagetool}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
dir="$work/LokoTube.AppDir"
mkdir -p "$dir/usr"
cp -a "$app" "$dir/usr/lokotube"

cat > "$dir/AppRun" <<'RUN'
#!/bin/sh
here="$(dirname "$(readlink -f "$0")")"
exec "$here/usr/lokotube/bin/LokoTube" "$@"
RUN
chmod +x "$dir/AppRun"

cp "$icon" "$dir/lokotube-LokoTube.png"
cp "$icon" "$dir/.DirIcon"
cat > "$dir/lokotube-LokoTube.desktop" <<'ENTRY'
[Desktop Entry]
Type=Application
Name=LokoTube
GenericName=Music player
Comment=Find your song
Exec=LokoTube
Icon=lokotube-LokoTube
Categories=AudioVideo;Audio;Player;
StartupWMClass=lokotube-LokoTube
Terminal=false
ENTRY

ARCH="${ARCH:-x86_64}" "$tool" --no-appstream "$dir" "$out"
