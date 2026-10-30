// Explicitly owned HarfBuzz resources. Results are copies, never views into WASM memory.
// Font sizes are physical pixels; positions, extents and outline coordinates are 26.6 units.
export async function createTerminalHarfBuzz({ wasmBytes, wasmUrl = new URL("./harfbuzz.wasm", import.meta.url) } = {}) {
  if (!wasmBytes) {
    const response = await fetch(wasmUrl);
    if (!response.ok) throw new Error(`Cannot load terminal HarfBuzz (${response.status})`);
    wasmBytes = await response.arrayBuffer();
  }
  // The reactor has no filesystem, clock, environment, randomness or network imports.
  // libc's diagnostic streams are closed; unavailable descriptors return WASI EBADF.
  const imports = { wasi_snapshot_preview1: { fd_close: () => 8, fd_seek: () => 8, fd_write: () => 8 } };
  let { instance } = await WebAssembly.instantiate(wasmBytes, imports);
  let h = instance.exports;
  h._initialize();
  const versionPtr = h.hb_version_string();
  const version = new TextDecoder().decode(new Uint8Array(h.memory.buffer).subarray(
    versionPtr, new Uint8Array(h.memory.buffer).indexOf(0, versionPtr)));
  if (version !== "11.0.0") throw new Error(`Unexpected terminal HarfBuzz version: ${version}`);
  const buffer = h.hb_buffer_create();
  const fonts = new Set();
  let fontBytes = 0;
  const alive = () => { if (!h) throw new Error("Terminal HarfBuzz is disposed"); };
  const allocate = size => {
    const pointer = h.malloc(Math.max(1, size));
    if (!pointer) throw new Error("Terminal HarfBuzz memory limit reached");
    return pointer;
  };
  const scratch = (size, body) => {
    const pointer = allocate(size);
    try { return body(pointer); } finally { h.free(pointer); }
  };
  const words = () => new Int32Array(h.memory.buffer);
  const scalar = code => {
    if (!Number.isInteger(code) || code < 0 || code > 0x10ffff || code >= 0xd800 && code <= 0xdfff)
      throw new RangeError("Expected a Unicode scalar");
  };
  const feature = value => {
    const match = /^([+-]?)([ -~]{4})(?:=(\d+))?$/.exec(value);
    if (!match) throw new RangeError(`Invalid font feature: ${value}`);
    const amount = match[3] === undefined ? (match[1] === "-" ? 0 : 1) : Number(match[3]);
    if (!Number.isInteger(amount) || amount < 0 || amount > 0xffffffff) throw new RangeError("Invalid font feature value");
    const tag = [...match[2]].reduce((result, char) => (result << 8) | char.charCodeAt(0), 0);
    return [tag, amount, 0, -1];
  };

  return {
    version,
    get fontCount() { return fonts.size; },
    get fontBytes() { return fontBytes; },
    get memoryBytes() { return h ? h.memory.buffer.byteLength : 0; },
    createFont(input, pixels = 12) {
      alive();
      const bytes = input instanceof Uint8Array ? input : new Uint8Array(input);
      const length = bytes.byteLength;
      if (!length || length > 32 * 1024 * 1024 || fontBytes + length > 64 * 1024 * 1024)
        throw new RangeError("Terminal font data exceeds the resource limit");
      let pointer = 0;
      let glyphCount = 0;
      scratch(length, data => {
        new Uint8Array(h.memory.buffer).set(bytes, data);
        // DUPLICATE gives the blob independent storage; scratch is freed on every path.
        const blob = h.hb_blob_create(data, length, 0, 0, 0);
        try {
          if (h.hb_blob_get_length(blob) !== length) throw new Error("Cannot allocate font data");
          const face = h.hb_face_create(blob, 0);
          try {
            glyphCount = h.hb_face_get_glyph_count(face);
            if (!glyphCount) throw new Error("Invalid or unsupported terminal font");
            pointer = h.hb_font_create(face);
            if (!h.hb_face_get_glyph_count(h.hb_font_get_face(pointer))) {
              h.hb_font_destroy(pointer); pointer = 0;
              throw new Error("Cannot allocate terminal font");
            }
          } finally { h.hb_face_destroy(face); }
        } finally { h.hb_blob_destroy(blob); }
      });
      fontBytes += length;
      const liveFont = () => { alive(); if (!pointer) throw new Error("Terminal font is disposed"); };
      const validGlyph = glyph => {
        liveFont();
        if (!Number.isInteger(glyph) || glyph < 0 || glyph >= glyphCount) throw new RangeError("Invalid glyph index");
      };
      const font = {
        glyphCount,
        setSize(size) {
          liveFont();
          if (!Number.isFinite(size) || size <= 0 || size > 4096) throw new RangeError("Invalid font size");
          const scale = Math.round(size * 64);
          h.hb_font_set_scale(pointer, scale, scale);
          h.hb_font_set_ppem(pointer, Math.round(size), Math.round(size));
        },
        nominalGlyph(code, variation = 0) {
          liveFont(); scalar(code);
          if (variation) scalar(variation);
          return scratch(4, result => {
            const supported = variation ? h.hb_font_get_variation_glyph(pointer, code, variation, result)
              : h.hb_font_get_nominal_glyph(pointer, code, result);
            return supported ? words()[result / 4] : null;
          });
        },
        extents(glyph) {
          validGlyph(glyph);
          return scratch(16, result => {
            if (!h.hb_font_get_glyph_extents(pointer, glyph, result)) return null;
            const values = words().slice(result / 4, result / 4 + 4);
            return { xBearing: values[0], yBearing: values[1], width: values[2], height: values[3] };
          });
        },
        horizontalExtents() {
          liveFont();
          return scratch(48, result => {
            if (!h.hb_font_get_h_extents(pointer, result)) return null;
            const values = words().slice(result / 4, result / 4 + 3);
            return { ascender: values[0], descender: values[1], lineGap: values[2] };
          });
        },
        shape(text, features = []) {
          liveFont();
          if (typeof text !== "string" || text.length > 1048576 || !Array.isArray(features) || features.length > 64)
            throw new RangeError("Terminal shaping input exceeds the resource limit");
          if (!text.length) return [];
          const settings = [feature("liga"), ...features.map(feature)];
          h.hb_buffer_reset(buffer);
          scratch(text.length * 2, data => {
            const values = new Uint16Array(h.memory.buffer);
            for (let i = 0; i < text.length; i++) values[data / 2 + i] = text.charCodeAt(i);
            h.hb_buffer_add_utf16(buffer, data, text.length, 0, text.length);
          });
          h.hb_buffer_set_cluster_level(buffer, 2); // CHARACTERS, as in original Ghostty.
          h.hb_buffer_set_direction(buffer, 4); // Forced LTR, independent of the script.
          h.hb_buffer_guess_segment_properties(buffer);
          scratch(settings.length * 16, data => {
            const values = words();
            settings.forEach((setting, i) => values.set(setting, data / 4 + i * 4));
            h.hb_shape(pointer, buffer, data, settings.length);
          });
          if (!h.hb_buffer_allocation_successful(buffer)) throw new Error("Terminal shaping exhausted WASM memory");
          const count = h.hb_buffer_get_length(buffer);
          if (count > 1048576) throw new RangeError("Terminal shaping output exceeds the resource limit");
          const info = h.hb_buffer_get_glyph_infos(buffer, 0) / 4;
          const positions = h.hb_buffer_get_glyph_positions(buffer, 0) / 4;
          const values = words();
          return Array.from({ length: count }, (_, i) => ({
            glyph: values[info + i * 5], index: values[info + i * 5 + 2],
            xAdvance: values[positions + i * 5], yAdvance: values[positions + i * 5 + 1],
            xOffset: values[positions + i * 5 + 2], yOffset: values[positions + i * 5 + 3],
          }));
        },
        outline(glyph) {
          validGlyph(glyph);
          const outline = h.kt_hb_outline(pointer, glyph);
          if (!outline) throw new Error("Terminal glyph outline exceeds the resource limit");
          try {
            const data = h.kt_hb_outline_data(outline), count = h.kt_hb_outline_length(outline);
            const values = new Float32Array(h.memory.buffer, data, count).slice();
            if (!values.every(Number.isFinite)) throw new Error("Invalid glyph outline coordinates");
            return values;
          } finally { h.kt_hb_outline_destroy(outline); }
        },
        colorPng(glyph) {
          validGlyph(glyph);
          const blob = h.hb_ot_color_glyph_reference_png(pointer, glyph);
          try {
            const length = h.hb_blob_get_length(blob);
            if (!length) return null;
            const data = h.hb_blob_get_data(blob, 0);
            return new Uint8Array(h.memory.buffer, data, length).slice();
          } finally { h.hb_blob_destroy(blob); }
        },
        dispose() {
          if (!pointer) return;
          h.hb_font_destroy(pointer); pointer = 0;
          fontBytes -= length; fonts.delete(font);
        },
      };
      fonts.add(font);
      try { font.setSize(pixels); } catch (error) { font.dispose(); throw error; }
      return font;
    },
    dispose() {
      if (!h) return;
      for (const font of fonts) font.dispose();
      h.hb_buffer_destroy(buffer);
      // Disposed handles do not keep the entire WASM heap reachable.
      h = null; instance = null;
    },
  };
}
