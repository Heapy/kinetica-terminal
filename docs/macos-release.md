# Signed macOS preview releases

Distribute the Apple Silicon app through GitHub Releases and the existing
[`Heapy/homebrew-tap`](https://github.com/Heapy/homebrew-tap). A public release
requires Developer ID Application signing, hardened runtime, a secure timestamp,
Apple notarization, and a stapled ticket. Apple Development signing is for local
development and does not meet this requirement.

## Apple account setup

1. Wait until the paid Apple Developer Program membership is active. A pending
   enrollment or Xcode Personal Team cannot issue Developer ID certificates.
2. In Xcode Settings → Apple Accounts, select the paid team → Manage Certificates
   → **Developer ID Application**. Keep the private key in the local Keychain.
   If Xcode rejects creation but the Apple Developer website permits it, use
   Certificates → **+** → **Developer ID Application** on the website. Generate
   a CSR with Keychain Access → Certificate Assistant → Request a Certificate
   From a Certificate Authority, choose **Saved to disk**, and use RSA 2048 bits.
   Upload the CSR, download the issued `.cer`, and import it into the same Mac's
   login Keychain. Verify that its public key matches the CSR. The private key
   stays in Keychain; only the CSR is uploaded.
3. Confirm the identity with `security find-identity -v -p codesigning`.
4. Create an app-specific password in the Apple Account website, then store it
   using the secure interactive prompt in a local terminal:

   ```sh
   xcrun notarytool store-credentials kinetica-terminal-notary \
     --apple-id YOUR_APPLE_ID --team-id YOUR_PAID_TEAM_ID
   ```

Do not put the password or private key in chat, Git, release assets or shell
history. An existing App Store Connect API key can also authenticate notarytool.
The paid Team ID is not necessarily the identifier shown for an old Personal Team.

See Apple's [Developer ID guide](https://developer.apple.com/developer-id/) and
[custom notarization workflow](https://developer.apple.com/documentation/security/customizing-the-notarization-workflow).

## Build a new preview

Use a clean tracked terminal checkout and a clean framework checkout. Preserve the
user's working changes and installed application by using separate worktrees.
The application version comes from `samples/native-terminal/src/TerminalVersion.kt`;
`publish.module-template.yaml` carries the library version and the packaging script
carries the increasing `CFBundleVersion`. Version 0.4.0 uses build 27.

Bootstrap the selected framework revision, then build the actual Release artifact:

```sh
bash scripts/bootstrap-kinetica.sh /path/to/clean/kinetica
./kotlin build -m native-terminal -p macosArm64 -v release
bash scripts/package-native-terminal.sh --skip-build --output 'build/release-source/Kinetica Terminal.app'
```

Record `source-build-info.json` with `version`, `bundle_version`,
`terminal_revision`, `framework_revision`, `variant: "release"`,
`architecture: "arm64"`, `deployment_target`, `source_modifications: false`,
`executable_sha256`, toolchain versions, test commands/results and known limitations.
Read the deployment target from the executable. Verify the packaged `--version`
and Info.plist before signing. A fresh build keeps its source version/build;
repackaging an older release may increase them, but may never decrease either.

```sh
export KINETICA_SIGNING_IDENTITY='Developer ID Application: YOUR_NAME (TEAM_ID)'
export KINETICA_NOTARY_PROFILE=kinetica-terminal-notary
bash scripts/notarize-native-terminal.sh submit \
  'build/release-source/Kinetica Terminal.app' \
  build/source-build-info.json 0.4.0 27 build/releases/0.4.0
```

The script validates the source executable hash, clean-source provenance and
credentials before copying and signing the bundle. Submission returns immediately
with an ID in `submission.json`. Check status using `xcrun notarytool info ID
--keychain-profile kinetica-terminal-notary`. Do not resubmit an in-progress request.
After Apple reports **Accepted**, run:

```sh
bash scripts/notarize-native-terminal.sh finish build/releases/0.4.0
```

This staples the ticket and verifies codesign, Gatekeeper and the final extracted
ZIP. The distribution files are the ZIP, `build-info.json`, `source-build-info.json`
and `SHA256SUMS.txt`. The generated cask uses the final ZIP hash.

## Runtime verification and publication

Run the signed app's built-in renderer diagnostic through LaunchServices. It
creates and closes only its own window and PTY:

```sh
open -n -W 'build/releases/0.4.0/staging/Kinetica Terminal.app' --args \
  --benchmark-renderer "$PWD/scripts/terminal-render-load.py" \
  "$PWD/build/releases/0.4.0/runtime-check.json"
python3 -c 'import json; r=json.load(open("build/releases/0.4.0/runtime-check.json")); assert r["valid"], r["failures"]'
```

Keep the test window visible during its roughly 10-second run. Direct binary
launches may leave it occluded; the diagnostic JSON determines success, not
`open`'s exit status. Never replace or terminate the user's installed app or shells.
Record new checks separately from historical test results and keep preview
limitations explicit, including untested older macOS and Intel hardware.

Push the clean release source branch and tag its exact commit. Create a draft
GitHub prerelease, upload only the four distribution files, download and verify
their hashes, then publish it. Do not upload staging/submission ZIPs or credentials.
After the download is public, update `Heapy/homebrew-tap/Casks/kinetica-terminal.rb`
from the generated cask. Run style, audit and fetch checks before pushing; do not
install over the user's app to validate the cask.

```sh
brew install --cask Heapy/tap/kinetica-terminal
# Existing installations:
brew update
brew upgrade --cask Heapy/tap/kinetica-terminal
```
