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

## Prepared 0.2.1 release

The authorized source is published `main`, commit
`79be7d72e9fe7383c2a784dd4da762fff760abb0`, also tagged `v0.2.0`. Local accessibility
and selection changes are excluded. Version **0.2.1, build 25** repackages the
existing Release executable and resources; it does not rebuild with potentially
different unpublished framework dependencies. Its source framework revision is
`d8170fee855838c002746e63efe3ca17c35d0e75`.

Download and verify the original release before extracting it:

```sh
mkdir -p build/previous-release
gh release download v0.2.0 -R Heapy/kinetica-terminal --dir build/previous-release
(cd build/previous-release && shasum -a 256 -c SHA256SUMS.txt)
ditto -x -k build/previous-release/Kinetica-Terminal-0.2.0-macos-arm64.zip \
  build/previous-release/extracted
```

The published ZIP SHA-256 is
`9f192a63760127fb377a4814897f4b31b61bd3398a9f2e63e1abd66fcfbbc519`.
The original executable SHA-256 is
`a762f10ff75b176bfd73fe1c1c3f40f0b6b5bc5007137acbadbdce39b74bc109`.
Both were verified during preparation. Signing changes executable bytes, so the
final release records its own hash alongside the original build provenance.

```sh
export KINETICA_SIGNING_IDENTITY='Developer ID Application: YOUR_NAME (TEAM_ID)'
export KINETICA_NOTARY_PROFILE=kinetica-terminal-notary
bash scripts/notarize-native-terminal.sh submit \
  'build/previous-release/extracted/Kinetica Terminal.app' \
  build/previous-release/build-info.json 0.2.1 25 build/releases/0.2.1
```

Submission returns immediately with an ID in `submission.json`. Check status
with `xcrun notarytool info ID --keychain-profile kinetica-terminal-notary`.
Use `notarytool log ID` with the same profile to diagnose rejection. Do not submit
again merely because Apple's first submission is still processing.

After the status is **Accepted**:

```sh
bash scripts/notarize-native-terminal.sh finish build/releases/0.2.1
```

The script staples the ticket, checks codesign and Gatekeeper, and repeats the
checks after extracting the final ZIP. It produces the ZIP, `build-info.json`,
`source-build-info.json`, `SHA256SUMS.txt`, and a cask with the final archive hash.
No GitHub or tap changes happen in the script.

## Runtime verification and publication

Before publication, launch the signed app in an isolated test window and verify
shell startup, keyboard input, a command, rendering, and normal window closure.
Never replace or terminate the installed app or the user's live shells. Record
these checks in the release notes; historical source-build tests are recorded in
`source-build-info.json` and must not be described as fresh test runs.

Keep 0.2.1 a preview: signing does not resolve the previous release's documented
test failure, accessibility limitations, or older-macOS coverage gaps. State that
this release changes distribution signing and bundle metadata only.

Commit the release tooling, push its branch, and tag that commit `v0.2.1` so the
source archive includes the tooling as well as the original app sources. Create
a draft GitHub prerelease, upload only the four distribution files named above,
verify downloaded hashes, and publish it. Do not upload the staging directory,
submission ZIP, credentials, or private keys.

After the GitHub download is public, update
`Heapy/homebrew-tap/Casks/kinetica-terminal.rb` from the generated cask. Run
Homebrew style/audit and fetch checks before pushing. Do not install over the
user's application to test the cask. Retain its preview caveat; the old ad-hoc
signing and unnotarized warnings can be removed only after successful notarization.

The installation command remains:

```sh
brew install --cask Heapy/tap/kinetica-terminal
```
