import { createTerminalHarfBuzz } from "../kinetica-terminal/web/harfbuzz/terminal-harfbuzz.mjs";

const names = {
  jetbrains_mono: "JetBrainsMonoNoNF-Regular.ttf", inconsolata: "Inconsolata-Regular.ttf",
  monaspace_neon: "MonaspaceNeon-Regular.otf", arabic: "KawkabMono-Regular.ttf",
};
const fontNames = [...Object.values(names), "NotoColorEmoji.ttf", "NotoEmoji-Regular.ttf"];
const check = (condition, message) => { if (!condition) throw new Error(message); };
const equal = (a, b, message) => check(JSON.stringify(a) === JSON.stringify(b), message);
const throws = (body, message) => { let failed = false; try { body(); } catch { failed = true; } check(failed, message); };

// The identical checks run in Node and Chromium, using original Ghostty font bytes.
export function verifyHarfBuzz(runtime, corpus, data) {
  check(runtime.version === "11.0.0", "Pinned HarfBuzz version");
  const fonts = new Map(fontNames.map(name => [name, runtime.createFont(data[name], name === "NotoColorEmoji.ttf" ? 109 : 12)]));
  let runs = 0, glyphs = 0;
  for (const test of corpus) for (const run of test.expected.runs) {
    if (!run.raw.length) continue; // Original geometric sprite, outside any font shaper.
    const name = [names[test.font ?? "jetbrains_mono"], "NotoColorEmoji.ttf", "NotoEmoji-Regular.ttf"][run.font_index >> 3];
    const text = run.input.map(point => String.fromCodePoint(point.codepoint)).join("");
    const starts = [];
    let offset = 0;
    for (const point of run.input) { starts.push(offset); offset += String.fromCodePoint(point.codepoint).length; }
    const actual = fonts.get(name).shape(text, test.features ?? []);
    const expected = run.raw.map(raw => ({ glyph: raw.glyph, index: starts[raw.index],
      xAdvance: raw.x_advance, yAdvance: raw.y_advance, xOffset: raw.x_offset, yOffset: raw.y_offset }));
    equal(actual, expected, `${test.name} run offset ${run.offset}: original glyphs, UTF-16 clusters and all raw positions`);
    runs++; glyphs += actual.length;
  }
  equal([runs, glyphs], [194, 1146], "The complete original font-shaping corpus must execute");
  check(runtime.fontCount === 6, "Own all primary and fallback faces");
  const primary = fonts.get(names.jetbrains_mono);
  const f = primary.nominalGlyph(70);
  check(f > 0, "Resolve F");
  check(primary.nominalGlyph(0x10fffd) === null, "Do not claim coverage for missing scalars");
  check(primary.extents(f).height < 0 && primary.horizontalExtents().ascender > 0, "Return physical 26.6 metrics");
  const outline = primary.outline(f);
  check(outline.length > 20 && outline[0] === 0 && outline.every(Number.isFinite), "Export a real glyph outline");
  const savedOutline = [...outline]; outline.fill(-999);
  equal([...primary.outline(f)], savedOutline, "Outline results must not alias WASM memory");
  const emoji = fonts.get("NotoColorEmoji.ttf");
  const png = emoji.colorPng(emoji.shape("👍🏽")[0].glyph);
  equal([...png.slice(0, 8)], [137, 80, 78, 71, 13, 10, 26, 10], "Export the actual colored emoji PNG");
  check(primary.colorPng(f) === null, "Ordinary outlines have no color bitmap");
  for (const font of fonts.values()) font.dispose();
  equal([runtime.fontCount, runtime.fontBytes], [0, 0], "Release all font data without waiting for GC");
  primary.dispose(); throws(() => primary.shape("abc"), "Reject disposed font handles");
  throws(() => runtime.createFont(new Uint8Array(32)), "Reject invalid fonts");
  throws(() => runtime.createFont(data[names.jetbrains_mono], 0), "Release resources when size validation fails");
  equal([runtime.fontCount, runtime.fontBytes], [0, 0], "Invalid inputs cannot retain faces");
  const copied = new Uint8Array(data[names.jetbrains_mono]).slice();
  const font = runtime.createFont(copied);
  copied.fill(0);
  check(font.shape("a===>b").length > 0, "Font storage does not alias caller bytes");
  throws(() => font.outline(-1), "Reject invalid glyph indices");
  throws(() => font.nominalGlyph(0xd800), "Reject malformed Unicode scalars");
  throws(() => font.shape("x", ["bad feature"]), "Reject malformed features");
  const retained = font.shape("é");
  const expectedRetained = JSON.stringify(retained);
  font.shape("=".repeat(513));
  equal(JSON.stringify(retained), expectedRetained, "Shaping results must not alias the reused buffer");
  font.dispose();
  const exercise = () => {
    const current = runtime.createFont(data[names.jetbrains_mono]);
    current.shape("a===>b é " + "=".repeat(513)); current.outline(current.nominalGlyph(70)); current.dispose();
  };
  for (let i = 0; i < 10; i++) exercise();
  const stableBytes = runtime.memoryBytes;
  for (let i = 0; i < 200; i++) exercise();
  check(runtime.memoryBytes === stableBytes, "Repeated create/shape/outline/dispose must reuse WASM storage");
  const survivor = runtime.createFont(data[names.jetbrains_mono]);
  runtime.dispose(); runtime.dispose(); survivor.dispose();
  equal([runtime.fontCount, runtime.fontBytes, runtime.memoryBytes], [0, 0, 0], "Dispose releases owned faces and the WASM instance");
  throws(() => survivor.shape("x"), "Runtime disposal invalidates retained font handles");
  throws(() => runtime.createFont(data[names.jetbrains_mono]), "Disposed runtimes cannot allocate fonts");
  return { runs, glyphs, stableBytes };
}

if (typeof process !== "undefined" && process.versions?.node) {
  const { readFile } = await import("node:fs/promises");
  const { createHash } = await import("node:crypto");
  const sha256 = bytes => createHash("sha256").update(bytes).digest("hex");
  const root = new URL("../", import.meta.url);
  const wasmBytes = await readFile(new URL("kinetica-terminal/web/harfbuzz/harfbuzz.wasm", root));
  const manifest = JSON.parse(await readFile(new URL("kinetica-terminal/web/harfbuzz/manifest.json", root)));
  equal(sha256(wasmBytes), manifest.artifacts["harfbuzz.wasm"], "Pinned WASM artifact");
  const module = await WebAssembly.compile(wasmBytes);
  equal(WebAssembly.Module.imports(module).map(value => `${value.module}.${value.name}`).sort(),
    ["wasi_snapshot_preview1.fd_close", "wasi_snapshot_preview1.fd_seek", "wasi_snapshot_preview1.fd_write"],
    "No undeclared host capabilities in the WASM runtime");
  const instance = await WebAssembly.instantiate(module, {
    wasi_snapshot_preview1: { fd_close: () => 8, fd_seek: () => 8, fd_write: () => 8 },
  });
  const memory = instance.exports.memory;
  throws(() => memory.grow(2048 - memory.buffer.byteLength / 65536 + 1), "The binary must enforce its 128 MiB memory ceiling");
  const corpusBytes = await readFile(new URL("kinetica-terminal/test-upstream/ghostty/shaping.json", root));
  const reference = JSON.parse(await readFile(new URL("kinetica-terminal/test-upstream/ghostty/shaping-manifest.json", root)));
  equal(sha256(corpusBytes), reference.fixtureSha256, "Original fixture provenance");
  const corpus = JSON.parse(corpusBytes), data = {};
  for (const name of fontNames) {
    data[name] = await readFile(new URL(`build/upstream/ghostty-1.4.0/src/font/res/${name}`, root));
    equal(sha256(data[name]), reference.sources[`src/font/res/${name}`], `Original ${name} bytes`);
  }
  const runtime = await createTerminalHarfBuzz({ wasmBytes });
  console.log("Node HarfBuzz:", verifyHarfBuzz(runtime, corpus, data));
  const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
  const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
  try {
    const page = await browser.newPage();
    await page.goto(`${process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173"}/samples/browser-terminal/web/index.html`);
    const result = await page.evaluate(async fontNames => {
      const { createTerminalHarfBuzz } = await import("/kinetica-terminal/web/harfbuzz/terminal-harfbuzz.mjs");
      const { verifyHarfBuzz } = await import("/scripts/verify-browser-harfbuzz.mjs");
      const corpus = await (await fetch("/kinetica-terminal/test-upstream/ghostty/shaping.json")).json();
      const data = {};
      for (const name of fontNames) data[name] = new Uint8Array(await (await fetch(`/build/upstream/ghostty-1.4.0/src/font/res/${name}`)).arrayBuffer());
      return verifyHarfBuzz(await createTerminalHarfBuzz(), corpus, data);
    }, fontNames);
    console.log("Chromium HarfBuzz:", result);
  } finally { await browser.close(); }
}
