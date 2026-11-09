#!/usr/bin/env bash
# Sign a provenance-checked Release app and submit it to Apple.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  notarize-native-terminal.sh submit APP SOURCE_BUILD_INFO VERSION BUILD_NUMBER OUTPUT_DIR
  notarize-native-terminal.sh finish OUTPUT_DIR

Required environment:
  KINETICA_NOTARY_PROFILE       Existing notarytool Keychain profile
  KINETICA_SIGNING_IDENTITY     Developer ID Application identity (submit only)

OUTPUT_DIR must not exist for submit. No GitHub or Homebrew writes are performed.
Submission is asynchronous. Run finish after Apple reports Accepted.
USAGE
}

[[ $# -gt 0 ]] || { usage; exit 2; }
action="$1"
shift
[[ "$action" != --help ]] || { usage; exit 0; }
case "$action" in
  submit) [[ $# == 5 ]] || { usage; exit 2; } ;;
  finish) [[ $# == 1 ]] || { usage; exit 2; } ;;
  *) usage; exit 2 ;;
esac
: "${KINETICA_NOTARY_PROFILE:?Set an existing notarytool Keychain profile}"

if [[ "$action" == submit ]]; then
  source_app="$1"
  source_info="$2"
  version="$3"
  build_number="$4"
  output="$5"
  : "${KINETICA_SIGNING_IDENTITY:?Set a Developer ID Application signing identity}"
  [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Expected X.Y.Z version' >&2; exit 2; }
  [[ "$build_number" =~ ^[1-9][0-9]*$ ]] || { echo 'Expected positive build number' >&2; exit 2; }
  [[ ! -e "$output" ]] || { echo 'Output already exists; use finish to resume a submitted release' >&2; exit 2; }
  codesign --verify --strict --verbose=2 "$source_app"
  python3 - "$source_app" "$source_info" "$version" "$build_number" <<'PY'
import hashlib, json, pathlib, plistlib, sys
app, info_path, version, build = sys.argv[1:]
info = json.loads(pathlib.Path(info_path).read_text())
plist = plistlib.loads((pathlib.Path(app) / 'Contents/Info.plist').read_bytes())
binary = pathlib.Path(app) / 'Contents/MacOS/kinetica-terminal'
if hashlib.sha256(binary.read_bytes()).hexdigest() != info['executable_sha256']:
    raise SystemExit('Executable does not match source build provenance')
if info.get('variant') != 'release' or info.get('architecture') != 'arm64':
    raise SystemExit('Expected a Release arm64 source build')
if info.get('source_modifications') is not False:
    raise SystemExit('Source build must come from clean tracked sources')
if plist['CFBundleIdentifier'] != 'io.heapy.kinetica.terminal-demo':
    raise SystemExit('Unexpected bundle identifier')
if plist['CFBundleShortVersionString'] != info['version'] or plist['CFBundleVersion'] != info['bundle_version']:
    raise SystemExit('Source bundle version does not match provenance')
if tuple(map(int, version.split('.'))) < tuple(map(int, info['version'].split('.'))):
    raise SystemExit('Release version must not decrease')
if int(build) < int(plist['CFBundleVersion']):
    raise SystemExit('Bundle build number must not decrease')
PY
  # Validate credentials before producing or uploading an artifact.
  xcrun notarytool history --keychain-profile "$KINETICA_NOTARY_PROFILE" --output-format json >/dev/null
  mkdir -p "$output"
  output="$(cd "$output" && pwd)"
  cp "$source_info" "$output/source-build-info.json"
  mkdir "$output/staging"
  app="$output/staging/Kinetica Terminal.app"
  ditto "$source_app" "$app"
  python3 - "$app/Contents/Info.plist" "$source_info" "$version" "$build_number" <<'PY'
import json, pathlib, plistlib, sys
path = pathlib.Path(sys.argv[1])
plist = plistlib.loads(path.read_bytes())
plist['CFBundleShortVersionString'] = sys.argv[3]
plist['CFBundleVersion'] = sys.argv[4]
plist['LSMinimumSystemVersion'] = json.load(open(sys.argv[2]))['deployment_target']
path.write_bytes(plistlib.dumps(plist))
PY
  codesign --force --sign "$KINETICA_SIGNING_IDENTITY" --options runtime --timestamp "$app"
  codesign --verify --strict --verbose=2 "$app"
  codesign --display --verbose=4 "$app" 2>"$output/signature.txt"
  grep -q '^Authority=Developer ID Application:' "$output/signature.txt"
  grep -q 'flags=.*runtime' "$output/signature.txt"
  grep -q '^Timestamp=' "$output/signature.txt"
  ditto -c -k --sequesterRsrc --keepParent "$app" "$output/submission.zip"
  xcrun notarytool submit "$output/submission.zip" \
    --keychain-profile "$KINETICA_NOTARY_PROFILE" --output-format json >"$output/submission.json"
  cat "$output/submission.json"
  printf '\nAfter Apple accepts the submission: %s finish %q\n' "$0" "$output"
  exit 0
fi

output="$(cd "$1" && pwd)"
app="$output/staging/Kinetica Terminal.app"
submission_id="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$output/submission.json")"
xcrun notarytool info "$submission_id" --keychain-profile "$KINETICA_NOTARY_PROFILE" \
  --output-format json >"$output/notarization.json"
python3 - "$output/notarization.json" <<'PY'
import json, sys
result = json.load(open(sys.argv[1]))
if result['status'] != 'Accepted':
    raise SystemExit('Apple notarization status: ' + result['status'] + '. No release archive created.')
PY
xcrun stapler staple "$app"
xcrun stapler validate "$app"
codesign --verify --deep --strict --verbose=2 "$app"
codesign --display --verbose=4 "$app" 2>"$output/signature.txt"
grep -q '^Authority=Developer ID Application:' "$output/signature.txt"
grep -q 'flags=.*runtime' "$output/signature.txt"
grep -q '^Timestamp=' "$output/signature.txt"
spctl --assess --type execute --verbose=2 "$app"
version="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$app/Contents/Info.plist")"
archive="Kinetica-Terminal-$version-macos-arm64.zip"
[[ ! -e "$output/$archive" ]] || { echo 'Final archive already exists' >&2; exit 2; }
ditto -c -k --sequesterRsrc --keepParent "$app" "$output/$archive"
# Gatekeeper and the stapled ticket must survive the actual download format.
verification="$(mktemp -d "$output/verify.XXXXXX")"
ditto -x -k "$output/$archive" "$verification"
codesign --verify --deep --strict --verbose=2 "$verification/Kinetica Terminal.app"
xcrun stapler validate "$verification/Kinetica Terminal.app"
spctl --assess --type execute --verbose=2 "$verification/Kinetica Terminal.app"
python3 - "$output" "$archive" <<'PY'
import datetime, hashlib, json, pathlib, plistlib, sys
output = pathlib.Path(sys.argv[1])
archive = output / sys.argv[2]
source = json.loads((output / 'source-build-info.json').read_text())
plist = plistlib.loads((output / 'staging/Kinetica Terminal.app/Contents/Info.plist').read_bytes())
sha256 = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
info = {
    'version': plist['CFBundleShortVersionString'],
    'bundle_version': plist['CFBundleVersion'],
    'bundle_identifier': plist['CFBundleIdentifier'],
    'packaged_at': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'terminal_revision': source['terminal_revision'],
    'framework_revision': source['framework_revision'],
    'architecture': source['architecture'],
    'deployment_target': plist['LSMinimumSystemVersion'],
    'prerelease': True,
    'packaging': ('Fresh Release build; Developer ID signed and notarized'
                  if source['version'] == plist['CFBundleShortVersionString'] and source['bundle_version'] == plist['CFBundleVersion']
                  else 'Existing Release executable; bundle version updated; Developer ID signed and notarized'),
    'source_build_provenance': 'source-build-info.json',
    'source_build_provenance_sha256': sha256(output / 'source-build-info.json'),
    'code_signing': 'Developer ID Application; hardened runtime; secure timestamp',
    'notarized': True,
    'notarization_id': json.loads((output / 'notarization.json').read_text())['id'],
    'validation': ['codesign strict verification', 'stapler validation', 'Gatekeeper assessment',
                   'ZIP extraction followed by all three checks'],
    'known_limitations': source.get('known_limitations', []),
    'archive': {'name': archive.name, 'bytes': archive.stat().st_size, 'sha256': sha256(archive)},
    'executable_sha256': sha256(output / 'staging/Kinetica Terminal.app/Contents/MacOS/kinetica-terminal'),
}
(output / 'build-info.json').write_text(json.dumps(info, indent=2) + '\n')
version = info['version']
(output / 'kinetica-terminal.rb').write_text(f'''cask "kinetica-terminal" do
  version "{version}"
  sha256 "{sha256(archive)}"

  url "https://github.com/Heapy/kinetica-terminal/releases/download/v#{{version}}/Kinetica-Terminal-#{{version}}-macos-arm64.zip"
  name "Kinetica Terminal"
  desc "Terminal with Metal rendering, tabs, search, and scrollback"
  homepage "https://github.com/Heapy/kinetica-terminal"

  depends_on arch: :arm64
  depends_on macos: :monterey

  app "Kinetica Terminal.app"

  caveats <<~EOS
    This is a preview release. Known limitations are documented at:
      https://github.com/Heapy/kinetica-terminal/releases/tag/v#{{version}}
  EOS
end
''')
files = [archive, output / 'build-info.json', output / 'source-build-info.json']
(output / 'SHA256SUMS.txt').write_text(''.join(f'{sha256(p)}  {p.name}\n' for p in files))
PY
printf 'Verified release artifacts and Homebrew cask: %s\n' "$output"
