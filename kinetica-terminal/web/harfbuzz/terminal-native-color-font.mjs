// Give Canvas access to an already-shaped glyph ID. This private face is never
// used for text layout: cmap maps supplementary PUA scalars directly to glyphs,
// and substitution/positioning tables are removed to prevent a second shaping.
export function prepareTerminalNativeColorFont(input) {
  const bytes = input instanceof Uint8Array ? input : new Uint8Array(input);
  if (bytes.length < 12 || bytes.length > 32 * 1024 * 1024) throw new RangeError('Invalid native glyph font size');
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const bounded = (offset, length, end = bytes.length) => {
    if (!Number.isSafeInteger(offset) || offset < 0 || length < 0 || offset > end - length)
      throw new RangeError('Truncated native glyph font');
  };
  const u16 = offset => { bounded(offset, 2); return view.getUint16(offset); };
  const u32 = offset => { bounded(offset, 4); return view.getUint32(offset); };
  const version = u32(0);
  if (![0x00010000, 0x4f54544f, 0x74727565].includes(version)) return null;
  const count = u16(4); bounded(12, count * 16);
  if (count > 4095) throw new RangeError('Native glyph font has too many tables');
  const tables = new Map();
  for (let i = 0; i < count; i++) {
    const record = 12 + i * 16, tag = u32(record), offset = u32(record + 8), length = u32(record + 12);
    bounded(offset, length);
    if (tables.has(tag)) throw new RangeError('Duplicate native glyph font table');
    tables.set(tag, {offset, length});
  }
  const sbix = tables.get(0x73626978), svg = tables.get(0x53564720), colr = tables.get(0x434f4c52);
  const maxp = tables.get(0x6d617870), head = tables.get(0x68656164);
  if (!sbix && !svg && !colr) return null;
  if (!maxp || maxp.length < 6 || !head || head.length < 12)
    throw new RangeError('Incomplete native glyph font');
  const glyphCount = u16(maxp.offset + 4), glyphs = new Uint8Array(glyphCount);
  if (!glyphCount) return null;
  if (sbix) {
    if (sbix.length < 8) throw new RangeError('Incomplete sbix header');
    if (u16(sbix.offset) === 1) {
      const end = sbix.offset + sbix.length, strikes = u32(sbix.offset + 4);
      if (strikes > 4096) throw new RangeError('Native glyph font has too many bitmap strikes');
      bounded(sbix.offset + 8, strikes * 4, end);
      const starts = [...new Set(Array.from({length: strikes}, (_, s) => sbix.offset + u32(sbix.offset + 8 + s * 4)))].sort((a, b) => a - b);
      for (let s = 0; s < starts.length; s++) {
        const start = starts[s];
        if (start < sbix.offset + 8 + strikes * 4) throw new RangeError('Invalid sbix strike offset');
        const limit = starts[s + 1] ?? end;
        if (limit > end || limit < start) throw new RangeError('Invalid sbix strike bounds');
        bounded(start, 4 + (glyphCount + 1) * 4, limit);
        const targets = new Int32Array(glyphCount).fill(-1), state = new Uint8Array(glyphCount);
        for (let g = 0; g < glyphCount; g++) {
          const first = start + u32(start + 4 + g * 4), last = start + u32(start + 8 + g * 4);
          if (first < start + 4 + (glyphCount + 1) * 4 || last < first) throw new RangeError('Invalid sbix glyph offset');
          bounded(first, last - first, limit);
          if (first === last) { state[g] = 2; continue; }
          bounded(first, 8, last);
          const kind = u32(first + 4);
          if (kind === 0x6a706720 || kind === 0x74696666) state[g] = 3; // jpg / tiff
          else if (kind === 0x64757065) {
            bounded(first + 8, 2, last); targets[g] = u16(first + 8);
            if (targets[g] >= glyphCount) throw new RangeError('Invalid sbix dupe target');
          } else state[g] = 2;
        }
        // Resolve each dupe edge once. Cycles cannot manufacture a native image.
        for (let g = 0; g < glyphCount; g++) {
          let next = g; const path = [];
          while (state[next] === 0) { state[next] = 1; path.push(next); next = targets[next]; }
          const result = state[next] === 3 ? 3 : 2;
          for (const item of path) state[item] = result;
          if (state[g] === 3) glyphs[g] = 1;
        }
      }
    }
  }
  // Vector paints can mix intrinsic colors and the current foreground. Mark
  // them separately so the renderer includes foreground in the atlas key.
  if (svg) {
    const end = svg.offset + svg.length;
    bounded(svg.offset, 10, end);
    if (u16(svg.offset) === 0) {
      const list = svg.offset + u32(svg.offset + 2);
      if (list < svg.offset + 10) throw new RangeError('Invalid SVG document list');
      bounded(list, 2, end);
      const count = u16(list), documents = list + 2 + count * 12;
      bounded(list + 2, count * 12, end);
      let previous = -1;
      for (let i = 0; i < count; i++) {
        const record = list + 2 + i * 12, first = u16(record), last = u16(record + 2);
        const offset = list + u32(record + 4), length = u32(record + 8);
        if (first <= previous || first > last || last >= glyphCount || offset < documents || !length)
          throw new RangeError('Invalid SVG glyph record');
        bounded(offset, length, end); glyphs.fill(2, first, last + 1); previous = last;
      }
    }
  }
  if (colr) {
    const end = colr.offset + colr.length;
    bounded(colr.offset, 14, end);
    if (u16(colr.offset) === 1) {
      bounded(colr.offset, 34, end);
      const relative = u32(colr.offset + 14);
      if (relative) {
        const list = colr.offset + relative;
        if (relative < 34) throw new RangeError('Invalid COLRv1 base glyph list');
        bounded(list, 4, end);
        const count = u32(list), paints = list + 4 + count * 6;
        if (count > glyphCount) throw new RangeError('Invalid COLRv1 glyph count');
        bounded(list + 4, count * 6, end);
        let previous = -1;
        for (let i = 0; i < count; i++) {
          const record = list + 4 + i * 6, glyph = u16(record), paint = list + u32(record + 2);
          if (glyph <= previous || glyph >= glyphCount || paint < paints) throw new RangeError('Invalid COLRv1 glyph record');
          bounded(paint, 1, end); glyphs[glyph] = 2; previous = glyph;
        }
      }
    }
  }
  if (!glyphs.some(Boolean)) return null;
  // Format-12 groups cover every real ID, including glyphs without cmap
  // entries (for example a substitution result or a referenced bitmap glyph).
  const groups = glyphCount > 65534 ? 2 : 1;
  const cmap = new Uint8Array(28 + groups * 12), cv = new DataView(cmap.buffer);
  cv.setUint16(2, 1); cv.setUint16(4, 3); cv.setUint16(6, 10); cv.setUint32(8, 12);
  cv.setUint16(12, 12); cv.setUint32(16, 16 + groups * 12); cv.setUint32(24, groups);
  cv.setUint32(28, 0xf0000); cv.setUint32(32, 0xf0000 + Math.min(glyphCount, 65534) - 1);
  // FFFFE/FFFFF are noncharacters, so the final possible ID uses plane 16 PUA.
  if (groups === 2) { cv.setUint32(40, 0x100000); cv.setUint32(44, 0x100000); cv.setUint32(48, 65534); }
  const omitted = new Set(['DSIG', 'GSUB', 'GPOS', 'GDEF', 'kern', 'kerx', 'morx', 'mort']
    .map(tag => [...tag].reduce((value, c) => value * 256 + c.charCodeAt(0), 0)));
  const entries = [...tables].filter(([tag]) => !omitted.has(tag) && tag !== 0x636d6170);
  entries.push([0x636d6170, null]); entries.sort(([a], [b]) => a - b);
  if (entries.length > 4095) throw new RangeError('Native glyph font has too many tables');
  let length = 12 + entries.length * 16;
  for (const [, table] of entries) length += ((table?.length ?? cmap.length) + 3) & ~3;
  if (length > 32 * 1024 * 1024) throw new RangeError('Native glyph font exceeds the resource limit');
  const output = new Uint8Array(length), out = new DataView(output.buffer);
  out.setUint32(0, version); out.setUint16(4, entries.length);
  const power = Math.floor(Math.log2(entries.length));
  out.setUint16(6, 16 * 2 ** power); out.setUint16(8, power); out.setUint16(10, entries.length * 16 - 16 * 2 ** power);
  const checksum = (offset, size) => {
    let sum = 0;
    for (let i = 0; i < size; i += 4) sum = (sum + out.getUint32(offset + i)) >>> 0;
    return sum;
  };
  let offset = 12 + entries.length * 16, adjustment = 0;
  for (const [i, [tag, table]] of entries.entries()) {
    const data = table ? bytes.subarray(table.offset, table.offset + table.length) : cmap;
    output.set(data, offset);
    if (tag === 0x68656164) { adjustment = offset + 8; out.setUint32(adjustment, 0); }
    const padded = (data.length + 3) & ~3, record = 12 + i * 16;
    out.setUint32(record, tag); out.setUint32(record + 4, checksum(offset, padded));
    out.setUint32(record + 8, offset); out.setUint32(record + 12, data.length);
    offset += padded;
  }
  out.setUint32(adjustment, (0xb1b0afba - checksum(0, output.length)) >>> 0);
  return {bytes: output, glyphs};
}
