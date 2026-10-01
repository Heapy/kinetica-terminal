import assert from "node:assert/strict";

const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
try {
  for (const ratio of [1, 1.25, 2]) {
    const page = await browser.newPage({ viewport: { width: 900, height: 500 }, deviceScaleFactor: ratio });
    try {
      await page.goto(`${process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173"}/samples/browser-terminal/web/index.html?renderer=gpu`);
      await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() >= 64);
      for (const width of [900, 901]) {
        await page.setViewportSize({ width, height: 500 });
        const result = await page.evaluate(async () => {
          const demo = globalThis.kineticaTerminalDemo;
          demo.reset(); demo.fontSize(14);
          const measure = document.createElement("canvas").getContext("2d");
          measure.font = "14px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";
          const cellWidth = measure.measureText("M").width, cellHeight = Math.ceil(14 * 1.3);
          const styles = ["0", "0;1", "0;2", "0;7"];
          const gl = document.querySelector("canvas").getContext("webgl2");
          const originalDraw = gl.drawArraysInstanced;
          const capturedWidth = Math.ceil(64 * cellWidth * devicePixelRatio);
          const capturedHeight = Math.ceil(styles.length * cellHeight * devicePixelRatio);
          let pixels;
          // Read while the drawing buffer is valid, after each batch. The final batch
          // includes all glyphs; interactive rendering itself never reads back pixels.
          gl.drawArraysInstanced = function (...args) {
            originalDraw.apply(gl, args);
            pixels = new Uint8Array(capturedWidth * capturedHeight * 4);
            gl.readPixels(0, gl.drawingBufferHeight - capturedHeight, capturedWidth, capturedHeight,
              gl.RGBA, gl.UNSIGNED_BYTE, pixels);
          };
          try {
            demo.write("\x1b[?25l" + styles.map((style, row) => `\x1b[${row + 1};1H\x1b[${style}m${"0".repeat(64)}`).join(""));
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
          } finally { gl.drawArraysInstanced = originalDraw; }
          if (!pixels) throw new Error("No rendered glyph frame");
          if (gl.getError() !== gl.NO_ERROR) throw new Error("WebGL readback failed");
          // Round ties to even, as the shared Kotlin grid does.
          const rounded = value => value % 1 === 0.5 ? Math.round(value / 2) * 2 : Math.round(value);
          const glyphWidth = Math.floor(cellWidth * devicePixelRatio), glyphHeight = Math.floor(cellHeight * devicePixelRatio);
          const pixel = (column, row, x, y, channel) => {
            const originX = rounded(column * cellWidth * devicePixelRatio);
            const originY = rounded(row * cellHeight * devicePixelRatio);
            return pixels[((capturedHeight - 1 - originY - y) * capturedWidth + originX + x) * 4 + channel];
          };
          let ink = 0;
          for (let y = 0; y < glyphHeight; y++) for (let x = 0; x < glyphWidth; x++) if (pixel(0, 0, x, y, 0) > 100) ink++;
          if (ink < 5) throw new Error("The reference zero did not produce visible ink");
          for (let row = 0; row < styles.length; row++) for (let column = 1; column < 64; column++) {
            for (let y = 0; y < glyphHeight; y++) for (let x = 0; x < glyphWidth; x++) for (let channel = 0; channel < 4; channel++) {
              const expected = pixel(0, row, x, y, channel), actual = pixel(column, row, x, y, channel);
              if (expected !== actual) return { error: { column, style: styles[row], x, y, channel, expected, actual }, cellWidth };
            }
          }
          return { error: null, cellWidth };
        });
        assert.equal(result.error, null, `Inconsistent zero at DPR=${ratio}, viewport=${width}: ${JSON.stringify(result)}`);
      }
    } finally { await page.close(); }
  }
  console.log("WebGL glyphs: identical repeated zeros in normal/bold/dim/inverse styles at DPR 1, 1.25 and 2, across window widths passed");
} finally { await browser.close(); }
