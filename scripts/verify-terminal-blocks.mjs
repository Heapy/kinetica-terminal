import assert from "node:assert/strict";
import { mkdir } from "node:fs/promises";

const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
try {
  for (const renderer of ["gpu", "software"]) for (const ratio of [1, 1.25, 2]) {
    const page = await browser.newPage({ viewport: { width: 901, height: 500 }, deviceScaleFactor: ratio });
    try {
      await page.goto(`${process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173"}/samples/browser-terminal/web/index.html?renderer=${renderer}`);
      await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() >= 32);
      for (const fontSize of [14, 17]) {
        const result = await page.evaluate(async ({ renderer, fontSize }) => {
          const demo = globalThis.kineticaTerminalDemo;
          demo.reset(); demo.fontSize(fontSize);
          await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
          const artwork = ["█".repeat(32), "█".repeat(32), " ▐▛███▛█", "▝▜██████▀", " ▝▝   ▝▝", "▘▝▖▗▚▞▙▟▀▄▌▐"];
          const quadrants = { " ": 0, "█": 15, "▐": 10, "▛": 7, "▜": 11, "▝": 2, "▀": 3,
            "▄": 12, "▌": 5, "▘": 1, "▖": 4, "▗": 8, "▚": 9, "▞": 6, "▙": 13, "▟": 14 };
          const measure = document.createElement("canvas").getContext("2d");
          measure.font = `${fontSize}px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace`;
          const cellWidth = measure.measureText("M").width, cellHeight = Math.ceil(fontSize * 1.3);
          const canvas = document.querySelector("canvas");
          const gl = renderer === "gpu" ? canvas.getContext("webgl2") : null;
          if (renderer === "gpu" && !gl) throw new Error("Expected WebGL renderer");
          const capturedWidth = Math.ceil(32 * cellWidth * devicePixelRatio);
          const capturedHeight = Math.ceil(artwork.length * cellHeight * devicePixelRatio);
          let pixels;
          const originalDraw = gl?.drawArraysInstanced;
          if (gl) gl.drawArraysInstanced = function (...args) {
            originalDraw.apply(gl, args);
            pixels = new Uint8Array(capturedWidth * capturedHeight * 4);
            gl.readPixels(0, gl.drawingBufferHeight - capturedHeight, capturedWidth, capturedHeight, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
          };
          try {
            demo.write("\x1b[?25l\x1b[38;2;204;124;94m" + artwork.join("\r\n"));
            await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
          } finally { if (gl) gl.drawArraysInstanced = originalDraw; }
          if (gl) {
            if (!pixels || gl.getError() !== gl.NO_ERROR) throw new Error("WebGL block readback failed");
          } else pixels = canvas.getContext("2d").getImageData(0, 0, capturedWidth, capturedHeight).data;
          const rounded = value => value % 1 === 0.5 ? Math.round(value / 2) * 2 : Math.round(value);
          for (let row = 0; row < artwork.length; row++) for (let column = 0; column < 32; column++) {
            const mask = quadrants[artwork[row][column] ?? " "];
            const left = rounded(column * cellWidth * devicePixelRatio), top = rounded(row * cellHeight * devicePixelRatio);
            const right = rounded((column + 1) * cellWidth * devicePixelRatio), bottom = rounded((row + 1) * cellHeight * devicePixelRatio);
            const middleX = rounded((column + 0.5) * cellWidth * devicePixelRatio), middleY = rounded((row + 0.5) * cellHeight * devicePixelRatio);
            for (let y = top; y < bottom; y++) for (let x = left; x < right; x++) {
              const quadrant = (x < middleX ? 0 : 1) + (y < middleY ? 0 : 2);
              const expected = mask & (1 << quadrant) ? [204, 124, 94, 255] : [30, 30, 30, 255];
              const offset = ((gl ? capturedHeight - 1 - y : y) * capturedWidth + x) * 4;
              for (let channel = 0; channel < 4; channel++) if (pixels[offset + channel] !== expected[channel]) {
                return { error: { row, column, x, y, channel, expected: expected[channel], actual: pixels[offset + channel] }, cellWidth };
              }
            }
          }
          return { error: null, cellWidth };
        }, { renderer, fontSize });
        assert.equal(result.error, null, `Block seam: renderer=${renderer}, DPR=${ratio}, font=${fontSize}: ${JSON.stringify(result)}`);
      }
      if (ratio === 2) {
        await mkdir("build/reports/terminal-blocks", { recursive: true });
        await page.screenshot({ path: `build/reports/terminal-blocks/${renderer}.png` });
      }
    } finally { await page.close(); }
  }
  console.log("Solid block artwork: exact cell coverage in WebGL and Canvas at DPR 1, 1.25, 2 and font sizes 14, 17 passed");
} finally { await browser.close(); }
