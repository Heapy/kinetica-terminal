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
            globalThis.boldPixels = pixels;
          };
          globalThis.captureBold = () => boldPixels.slice();
        } else {
          const ctx = canvas.getContext("2d");
          globalThis.captureBold = () => ctx.getImageData(0, 0, canvas.width, canvas.height).data;
        }
      }, renderer);
      const frame = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      const write = async text => { await page.evaluate(text => kineticaTerminalDemo.write(text), text); await frame(); };
      const policy = async value => { await page.evaluate(value => kineticaTerminalDemo.boldColor(value), value); await frame(); };
      await write("\x1bc\x1b[?25l\x1b]10;#123456\x07\x1b]11;#0a14c8\x07\x1b]4;1;#123456;9;#204060;200;#abcdef\x07");
      await page.evaluate(() => { globalThis.boldBackground = captureBold(); });
      for (const configured of [-1, 0x2468ac]) {
        const defaultColor = configured < 0 ? 0x123456 : configured;
        for (const [sgr, expected] of [["39", defaultColor], ["31", 0x204060], ["91", 0x204060],
          ["38;5;200", 0xabcdef], ["38;2;18;52;86", defaultColor], ["38;2;9;8;7", 0x090807], ["31;7", 0x204060]]) {
          await policy(null);
          await write(`\x1b[H\x1b[0;1;${sgr};4:3;53;9;58;2;20;210;40mA█g😀`);
          await policy(configured);
          await page.evaluate(() => { globalThis.configuredPixels = captureBold(); });
          await policy(null);
          const inverse = sgr === "31;7" ? ";7" : "";
          await write(`\x1b[H\x1b[0;1;38;2;${expected >> 16};${expected >> 8 & 255};${expected & 255}${inverse};4:3;53;9;58;2;20;210;40mA█g😀`);
          const result = await page.evaluate(() => {
            const reference = captureBold();
            let maxError = 0;
            const affected = reference.reduce((count, value, i) => count + (value !== boldBackground[i] ? 1 : 0), 0);
            for (let i = 0; i < reference.length; i++) maxError = Math.max(maxError, Math.abs(reference[i] - configuredPixels[i]));
            return { maxError, affected };
          });
          assert.ok(result.affected > 16, "Must exercise visible glyphs and decorations");
          assert.ok(result.maxError <= 2, `${renderer} DPR ${scale}: policy=${configured}, sgr=${sgr}, pixel error=${result.maxError}`);
        }
      }
      assert.deepEqual(errors, []);
      console.log(`${renderer} DPR ${scale}: bold default/indexed/RGB, inverse, live policy changes, text/blocks/emoji/decorations passed`);
    } finally { await page.close(); }
  }
} finally { await browser.close(); }
