import assert from "node:assert/strict";

const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const base = process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173";
try {
  for (const renderer of ["gpu", "software"]) for (const scale of [1, 1.25, 2]) {
    const page = await browser.newPage({ viewport: { width: 900, height: 500 }, deviceScaleFactor: scale });
    const errors = [];
    page.on("pageerror", error => errors.push(String(error)));
    try {
      await page.goto(`${base}/samples/browser-terminal/web/index.html?renderer=${renderer}`);
      await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 20);
      assert.equal(await page.locator("[data-terminal]").getAttribute("data-terminal-renderer"), renderer === "gpu" ? "webgl2" : "canvas2d");
      if (renderer === "gpu") await page.evaluate(() => {
        const gl = document.querySelector("canvas").getContext("webgl2");
        const draw = gl.drawArraysInstanced.bind(gl);
        gl.drawArraysInstanced = (...args) => {
          draw(...args);
          const pixel = new Uint8Array(4);
          gl.readPixels(2, gl.drawingBufferHeight - 1, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixel);
          globalThis.overlineTop = [...pixel];
          gl.readPixels(2, gl.drawingBufferHeight - 5, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixel);
          globalThis.overlineBelow = [...pixel];
        };
      });
      const write = async value => {
        await page.evaluate(value => kineticaTerminalDemo.write(value), value);
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      };
      const pixels = () => page.evaluate(renderer => {
        if (renderer === "gpu") return [overlineTop, overlineBelow];
        const ctx = document.querySelector("canvas").getContext("2d");
        return [[...ctx.getImageData(2, 0, 1, 1).data], [...ctx.getImageData(2, 4, 1, 1).data]];
      }, renderer);
      const red = [240, 20, 30, 255], blue = [10, 20, 200, 255];
      await write("\x1bc\x1b[?25l\x1b[H\x1b[38;2;240;20;30;48;2;10;20;200;58;2;20;210;40;53m  ");
      assert.deepEqual(await pixels(), [red, blue], `${renderer} ${scale}: foreground, not underline color`);
      await write("\x1b[H\x1b[55m  ");
      assert.deepEqual(await pixels(), [blue, blue], "SGR 55 removes the overline");
      await write("\x1b[H\x1b[53;8m  ");
      assert.deepEqual(await pixels(), [blue, blue], "Hidden text hides decorations");
      await write("\x1b[H\x1b[28;7m  ");
      assert.deepEqual(await pixels(), [blue, red], "Inverse swaps effective foreground");
      await write("\x1b[H\x1b[0;53;2;38;2;240;20;30m  ");
      assert.deepEqual((await pixels())[0], [120, 10, 15, 255], "Faint applies to overline");
      await write("\x1b[H\x1b[0;53;31m  \x1b]4;1;#123456\x07");
      assert.deepEqual((await pixels())[0], [18, 52, 86, 255], "Palette changes repaint existing overlines");
      assert.deepEqual(errors, []);
      console.log(`${renderer} DPR ${scale}: overline pixels, reset, hidden, inverse, faint and palette passed`);
    } finally { await page.close(); }
  }
} finally { await browser.close(); }
