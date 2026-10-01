# Pinned Unicode data and terminal policy

`sources.tar.gz` preserves seven Unicode source/test files distributed by uucode
at `9d55524551411b493cca41ca06363625d90aff1e`, the dependency pinned by our Ghostty
reference. They identify themselves as Unicode 18.0.0. This is that exact snapshot,
identified by the hashes in `manifest.json`; it is not an implicit dependency on
the current Unicode website, a JDK, JavaScript engine, or operating system.

Regenerate the runtime property table and unchanged break vectors offline:

```sh
python3 scripts/generate-terminal-unicode.py
```

The generator validates every input hash and derives 2,525 property ranges (20,200
bytes before Base64 encoding). Width derivation follows uucode's standalone/inside-
grapheme distinction and Ghostty's terminal policy. The common tests compare all
1,114,112 codepoint widths against ranges generated independently by the original
`libghostty-vt` (`generate-terminal-oracle.py`), including controls and unassigned
codepoints. Invalid codepoints have width one; ambiguous-width characters are narrow.

All 853 original `GraphemeBreakTest.txt` vectors run against the untailored UAX #29
state machine. They also run with Ghostty/uucode's explicit terminal tailoring:
an emoji modifier joins only an immediately preceding modifier base. The tailored
comparison asserts the original non-break before applying this declared exception.
No source vectors are rewritten. Width effects are separate from boundaries:
VS15/VS16 narrow/widen valid bases, invalid selectors are ignored, and spacing marks
can widen a cluster. Original-Ghostty terminal snapshots cover these effects,
streaming input, edge wrapping, reflow and mode changes.

Mode 2027 enables clustering. `TerminalSession(graphemeClustering = true)` chooses
an initial mode restored by reset; the default is false, matching the reference
library. Applications can set/reset/query the mode with DECSET/DECRST/DECRQM.
Cells retain at most 64 suffix codepoints. The raw data and generated tables carry
the Unicode license; adapted algorithms carry uucode/Ghostty MIT notices. Copies
are in `third-party`, runtime resources and packaged native demo bundles.
