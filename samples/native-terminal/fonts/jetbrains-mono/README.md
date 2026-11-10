# Bundled JetBrains Mono

Unmodified Regular, Bold, Italic and Bold Italic faces from JetBrains Mono 2.304,
pinned to upstream commit `cd5227bd1f61dff3bbd6c814ceaf7ffd95e947d9`.
Copyright and the SIL Open Font License 1.1 are preserved in `OFL.txt`.
`manifest.json` records the source, file hashes and generated Kotlin hash.

The native app embeds these faces in its executable and registers them for its
own process. It does not install fonts into the user's or system font directories.
The standalone terminal engine continues to accept host-selected fonts.

Regenerate or verify offline from the repository root:

```sh
python3 scripts/generate-native-text-fonts.py
python3 scripts/generate-native-text-fonts.py --check
```

Missing source files are fetched from the pinned upstream commit when generating;
`--check` never downloads. Packaging includes the original license in
`Contents/Resources/LICENSES/JETBRAINS-MONO-OFL.txt`.
