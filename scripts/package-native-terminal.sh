#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"
app="$repo_root/build/apps/Kinetica Terminal.app"
variant=Release
build_variant=release
skip_build=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build) skip_build=true; shift ;;
    --debug) variant=Debug; build_variant=debug; shift ;;
    --output) app="${2:?--output requires an app path}"; shift 2 ;;
    *) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
  esac
done
if [[ "$skip_build" != true ]]; then
  ./kotlin build -m native-terminal -p macosArm64 -v "$build_variant"
fi
binary="$repo_root/build/tasks/_native-terminal_linkMacosArm64$variant/native-terminal.kexe"
test -f "$binary"
mkdir -p "$app/Contents/MacOS"
cp "$binary" "$app/Contents/MacOS/kinetica-terminal"
mkdir -p "$app/Contents/Resources/LICENSES"
cp kinetica-terminal/resources/META-INF/LICENSES/* "$app/Contents/Resources/LICENSES/"
# Compile the layered Icon Composer source and its legacy macOS fallback together.
icon_build="$repo_root/build/terminal-icon"
mkdir -p "$icon_build"
xcrun actool "$repo_root/Kinetica-Kotlin.icon" \
  --compile "$icon_build" \
  --output-format human-readable-text \
  --app-icon Kinetica-Kotlin \
  --output-partial-info-plist "$icon_build/partial-info.plist" \
  --platform macosx --target-device mac --minimum-deployment-target 12.0
cp "$icon_build/Assets.car" "$icon_build/Kinetica-Kotlin.icns" "$app/Contents/Resources/"
cat > "$app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleExecutable</key><string>kinetica-terminal</string>
  <key>CFBundleIdentifier</key><string>io.heapy.kinetica.terminal-demo</string>
  <key>CFBundleName</key><string>Kinetica Terminal</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleVersion</key><string>8</string>
  <key>CFBundleShortVersionString</key><string>0.1.0</string>
  <key>NSPrincipalClass</key><string>NSApplication</string>
  <key>NSHighResolutionCapable</key><true/>
</dict></plist>
PLIST
/usr/libexec/PlistBuddy -c "Merge '$icon_build/partial-info.plist'" "$app/Contents/Info.plist"
plutil -lint "$app/Contents/Info.plist"
codesign --force --sign - "$app"
printf '%s\n' "$app"
