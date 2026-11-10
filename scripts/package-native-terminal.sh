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
app_version="$("$binary" --version)"
app_version="${app_version#Kinetica }"
if [[ ! "$app_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  printf 'Invalid application version: %s\n' "$app_version" >&2
  exit 1
fi
mkdir -p "$app/Contents/MacOS"
cp "$binary" "$app/Contents/MacOS/kinetica-terminal"
mkdir -p "$app/Contents/Resources/LICENSES"
cp LICENSE "$app/Contents/Resources/LICENSES/Kinetica-Terminal-LICENSE.txt"
cp kinetica-terminal/resources/META-INF/LICENSES/* "$app/Contents/Resources/LICENSES/"
cp samples/native-terminal/fonts/jetbrains-mono/OFL.txt "$app/Contents/Resources/LICENSES/JETBRAINS-MONO-OFL.txt"
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
cat > "$app/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleExecutable</key><string>kinetica-terminal</string>
  <key>CFBundleIdentifier</key><string>io.heapy.kinetica.terminal-demo</string>
  <key>CFBundleName</key><string>Kinetica Terminal</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleVersion</key><string>29</string>
  <key>CFBundleShortVersionString</key><string>$app_version</string>
  <key>NSPrincipalClass</key><string>NSApplication</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSServices</key>
  <array><dict>
    <key>NSMenuItem</key><dict><key>default</key><string>Open Kinetica Here</string></dict>
    <key>NSMessage</key><string>openKineticaHere</string>
    <key>NSPortName</key><string>Kinetica Terminal</string>
    <key>NSSendFileTypes</key><array><string>public.folder</string></array>
    <key>NSRequiredContext</key><dict><key>NSApplicationIdentifier</key><string>com.apple.finder</string></dict>
  </dict></array>
</dict></plist>
PLIST
/usr/libexec/PlistBuddy -c "Merge '$icon_build/partial-info.plist'" "$app/Contents/Info.plist"
plutil -lint "$app/Contents/Info.plist"
codesign --force --sign - "$app"
printf '%s\n' "$app"
