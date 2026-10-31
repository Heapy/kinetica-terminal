import assert from "node:assert/strict";

const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const base = process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173";
try {
  for (const renderer of ["gpu", "software"]) for (const scale of [1, 1.25, 2]) {
    const page = await browser.newPage({ viewport: { width: 500, height: 250 }, deviceScaleFactor: scale });
    const errors = [];
    page.on("pageerror", error => errors.push(String(error)));
    try {
      await page.addInitScript(() => Object.defineProperty(navigator, "platform", { get: () => "MacIntel" }));
      await page.goto(`${base}/samples/browser-terminal/web/index.html?renderer=${renderer}`);
      await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 20);
      assert.equal(await page.locator("[data-terminal]").getAttribute("data-terminal-renderer"), renderer === "gpu" ? "webgl2" : "canvas2d");
      await page.evaluate(() => document.fonts.ready);
      await page.evaluate(renderer => {
        const canvas = document.querySelector("canvas");
        if (renderer === "gpu") {
          const gl = canvas.getContext("webgl2");
          const draw = gl.drawArraysInstanced.bind(gl);
          gl.drawArraysInstanced = (...args) => {
            draw(...args);
            const pixels = new Uint8Array(gl.drawingBufferWidth * gl.drawingBufferHeight * 4);
            gl.readPixels(0, 0, gl.drawingBufferWidth, gl.drawingBufferHeight, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
            globalThis.selectionPixels = pixels;
          };
          globalThis.captureSelection = () => selectionPixels.slice();
        } else {
          const ctx = canvas.getContext("2d");
          globalThis.captureSelection = () => ctx.getImageData(0, 0, canvas.width, canvas.height).data;
        }
      }, renderer);
      const frame = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      const write = async text => { await page.evaluate(text => kineticaTerminalDemo.write(text), text); await frame(); };
      const element = page.locator("[data-terminal]");
      await element.click({ position: { x: 2, y: 2 } });
      await write("\x1bc\x1b[?25l\x1b]10;#123456\x07\x1b]11;#654321\x07\x1b]4;1;#112233;9;#8899aa;4;#445566\x07");
      await page.evaluate(() => { kineticaTerminalDemo.boldColor(-1); globalThis.selectionBackground = captureSelection(); });
      for (const inverse of [false, true]) {
        const cellForeground = inverse ? 0x445566 : 0x8899aa;
        const cellBackground = inverse ? 0x8899aa : 0x445566;
        for (const [foreground, fg] of [[null, 0x654321], [-1, cellForeground], [-2, cellBackground], [0xabcdef, 0xabcdef]]) {
          for (const [background, bg] of [[null, 0x123456], [-1, cellForeground], [-2, cellBackground], [0x0f1e2d, 0x0f1e2d]]) {
            await page.evaluate(([foreground, background]) => kineticaTerminalDemo.selectionColors(true, foreground, background), [foreground, background]);
            await write(`\x1b[H\x1b[0;1;31;44${inverse ? ";7" : ""};4:3;53;9;58;2;20;210;40mA█g😀`);
            await page.keyboard.press("Meta+a"); await frame();
            await page.evaluate(() => { globalThis.selectedPixels = captureSelection(); });
            await page.keyboard.press("Escape");
            await write(`\x1b[H\x1b[0;1;38;2;${fg >> 16};${fg >> 8 & 255};${fg & 255};48;2;${bg >> 16};${bg >> 8 & 255};${bg & 255};4:3;53;9;58;2;20;210;40mA█g😀`);
            const result = await page.evaluate(() => {
              const reference = captureSelection();
              let maxError = 0;
              const affected = reference.reduce((count, value, i) => count + (value !== selectionBackground[i] ? 1 : 0), 0);
              for (let i = 0; i < reference.length; i++) maxError = Math.max(maxError, Math.abs(reference[i] - selectedPixels[i]));
              return { maxError, affected };
            });
            assert.ok(result.affected > 16, "Must exercise visible glyphs and decorations");
            assert.ok(result.maxError <= 2, `${renderer} DPR ${scale}: foreground=${foreground} background=${background} inverse=${inverse}, pixel error=${result.maxError}`);
          }
        }
      }
      assert.deepEqual(errors, []);
      console.log(`${renderer} DPR ${scale}: 16 selection color combinations, inverse/bold, text/blocks/emoji/decorations passed`);
    } finally { await page.close(); }
  }
} finally { await browser.close(); }
