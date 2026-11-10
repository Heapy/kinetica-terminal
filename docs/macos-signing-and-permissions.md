# macOS signing and permissions

Use a consistent Apple Development signing identity for local Kinetica builds that
need persistent macOS privacy permissions. After switching from ad-hoc signing,
remove and re-add the installed app in Full Disk Access. An enabled permission
left over from an older signature may no longer apply.

## Sign local builds

`scripts/package-native-terminal.sh` currently ends with `codesign --force --sign -`,
which creates an ad-hoc signature. Rebuilding changes the binary identity and can
invalidate permissions recorded by macOS Transparency, Consent, and Control (TCC).
Apple recommends Apple Development signing for day-to-day development because
[ad-hoc identities are not stable across builds](https://developer.apple.com/forums/thread/751658).

List available signing identities:

```sh
security find-identity -v -p codesigning
```

Choose an Apple Development identity from the output. After **each** packaging run,
sign the completed bundle with that identity:

```sh
bash scripts/package-native-terminal.sh

# Replace this value with the identity hash printed by security find-identity.
KINETICA_SIGNING_IDENTITY='YOUR_APPLE_DEVELOPMENT_IDENTITY_HASH'
codesign --force --sign "$KINETICA_SIGNING_IDENTITY" --timestamp=none \
  'build/apps/Kinetica Terminal.app'
codesign --verify --strict --verbose=2 'build/apps/Kinetica Terminal.app'
codesign -dv --verbose=2 'build/apps/Kinetica Terminal.app'
```

Check that the output contains `Authority=Apple Development: ...` and a
`TeamIdentifier`, rather than `Signature=adhoc`. Keep the same signing identity and
bundle identifier (`io.heapy.kinetica.terminal-demo`) across local builds.
`KINETICA_SIGNING_IDENTITY` above is passed directly to `codesign`; the packaging
script does not currently read it.

To re-sign an already installed copy, fully quit Kinetica first and run the same
`codesign` commands from Terminal.app, using `/Applications/Kinetica Terminal.app`
as the bundle path. Do not overwrite or re-sign a running app. Launch the signed
copy again before testing access. Development signing is for local builds; this
procedure is not a distribution or notarization workflow.

## Restore access after changing the signature

The following resolved Firefox profile access on macOS 27.0.1 after switching
Kinetica from ad-hoc to Apple Development signing:

1. Open System Settings → Privacy & Security → Full Disk Access.
2. Remove the existing **Kinetica Terminal** entry with the minus button.
3. Add `/Applications/Kinetica Terminal.app` with the plus button and enable it.
4. Fully quit Kinetica with Command+Q and reopen it.
5. Run this command in Kinetica:

   ```sh
   ls "$HOME/Library/Application Support/Firefox/Profiles"
   ```

A list of profile directories confirms directory access. `Operation not permitted`
means access is still blocked. The verified fix was removing and re-adding the
app after signing, not merely restarting it with the old permission enabled.

On macOS 27, also check Privacy & Security → Files & Folders → Kinetica Terminal
for a Firefox switch if one is shown. A denied access attempt need not display a
permission dialog. Granting access to Terminal.app does not grant it to Kinetica.

Once access works, yt-dlp can request Firefox cookies from a Kinetica shell:

```sh
yt-dlp --cookies-from-browser firefox --no-playlist \
  -f 'bv*[height<=720]+ba/b[height<=720]' \
  -P "$HOME/Downloads" \
  -o '%(title)s [%(id)s].%(ext)s' \
  'https://www.youtube.com/watch?v=VIDEO_ID'
```

## Diagnose a remaining denial

Check the installed signature with `codesign -dv --verbose=2`, then inspect recent
TCC messages:

```sh
/usr/bin/log show --last 5m --style compact \
  --predicate 'subsystem == "com.apple.TCC" AND (eventMessage CONTAINS[c] "kinetica" OR eventMessage CONTAINS[c] "SystemPolicyAppDataDetailed")'
```

`Failed to match existing code requirement` means a stored permission does not
match the current app signature. For `SystemPolicyAllFiles`, remove and re-add
the app in Full Disk Access as described above.

If the mismatch is specifically for `SystemPolicyAppDataDetailed`, the following
reset succeeded on macOS 27.0.1:

```sh
tccutil reset SystemPolicyAppDataDetailed io.heapy.kinetica.terminal-demo
```

This resets Kinetica's detailed access decisions for other apps' data, not just
Firefox. Use it only when diagnosing that stale record, after signing and
relaunching the app. Retry access and check the privacy settings; the reset does
not grant permission or guarantee a prompt. It also does not repair a stale
Full Disk Access entry. Avoid resetting all TCC services or editing the TCC database.

`does not allow prompting for unentitled binaries` was also observed for this
service, including Terminal.app. It is not by itself evidence of a bad signature;
use the supported privacy settings to grant access instead of assuming a popup
will appear.
