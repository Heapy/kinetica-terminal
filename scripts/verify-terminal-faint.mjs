import assert from "node:assert/strict";

const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const base = process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173";
const samples = [["", "Aa"], ["", "😀"], ["", "█▀"], ...[1, 2, 3, 4, 5].map(style => [`4:${style};53;9;58;2;20;210;40;`, "  "])];
try {
  for (const fontSize of [12.5, 14]) for (const renderer of ["gpu", "software"]) for (const scale of [1, 1.25, 2]) {
    const page = await browser.newPage({ viewport: { width: 500, height: 250 }, deviceScaleFactor: scale });
    const errors = [];
    page.on("pageerror", error => errors.push(String(error)));
    try {
      await page.goto(`${base}/samples/browser-terminal/web/index.html?renderer=${renderer}`);
      await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 20);
      await page.evaluate(size => kineticaTerminalDemo.fontSize(size), fontSize);
      await page.evaluate(() => document.fonts.ready);
      assert.equal(await page.locator("[data-terminal]").getAttribute("data-terminal-renderer"), renderer === "gpu" ? "webgl2" : "canvas2d");
      await page.evaluate(renderer => {
        const canvas = document.querySelector("canvas");
        if (renderer === "gpu") {
          const gl = canvas.getContext("webgl2");
          const draw = gl.drawArraysInstanced.bind(gl);
          gl.drawArraysInstanced = (...args) => {
            draw(...args);
            const pixels = new Uint8Array(gl.drawingBufferWidth * gl.drawingBufferHeight * 4);
            gl.readPixels(0, 0, gl.drawingBufferWidth, gl.drawingBufferHeight, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
            globalThis.faintPixels = pixels;
          };
          globalThis.captureFaint = () => faintPixels.slice();
        } else {
          const ctx = canvas.getContext("2d");
          globalThis.captureFaint = () => ctx.getImageData(0, 0, canvas.width, canvas.height).data;
        }
      }, renderer);
      const write = async value => {
        await page.evaluate(value => kineticaTerminalDemo.write(value), value);
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      };
      for (const image of [false, true]) for (const [attributes, text] of samples) {
        await write("\x1bc\x1b[?25l\x1b]11;#0a14c8\x07" + (image ? `\x1b_Ga=T,q=2,i=44,f=24,s=1,v=1,c=4,r=4,C=1,z=-1;${Buffer.from([20, 210, 40]).toString("base64")}\x1b\\` : ""));
        await page.evaluate(() => { globalThis.faintBackground = captureFaint(); });
        await write(`\x1b[H\x1b[${attributes}38;2;240;20;30m${text}`);
        await page.evaluate(() => { globalThis.faintNormal = captureFaint(); });
        await write(`\x1b[H\x1b[2m${text}`);
        for (const configured of [0, 0.1, 0.5, 0.9, 1]) {
          await page.evaluate(value => kineticaTerminalDemo.faintOpacity(value), configured);
          await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
          const result = await page.evaluate(({ configured, colored }) => {
            const faint = captureFaint();
            const alpha = colored ? 255 : Math.ceil(configured * 255);
            let affected = 0, maxError = 0, worstByte = -1;
            for (let i = 0; i < faint.length; i++) {
              if (faintNormal[i] !== faintBackground[i]) affected++;
              const expected = Math.round((faintBackground[i] * (255 - alpha) + faintNormal[i] * alpha) / 255);
              const error = Math.abs(expected - faint[i]);
              if (error > maxError) { maxError = error; worstByte = i; }
            }
            return { affected, maxError, worstByte };
          }, { configured, colored: text === "😀" });
          assert.ok(result.affected > 16, "Must exercise visible glyphs/decorations");
          assert.ok(result.maxError <= 2, `${renderer} font ${fontSize} DPR ${scale}, image=${image}, opacity=${configured}, ${attributes}${text}: ${JSON.stringify(result)}`);
        }
        await page.evaluate(() => kineticaTerminalDemo.faintOpacity(0.1));
        await write(`\x1b[H\x1b[22m${text}`);
        const restored = await page.evaluate(() => {
          const pixels = captureFaint();
          let maxError = 0, changed = 0, worstByte = -1;
          for (let i = 0; i < pixels.length; i++) {
            const error = Math.abs(pixels[i] - faintNormal[i]);
            if (error) changed++;
            if (error > maxError) { maxError = error; worstByte = i; }
          }
          return { maxError, changed, worstByte, actual: pixels[worstByte], expected: faintNormal[worstByte] };
        });
        // A control repaint without SGR 2 also differs by up to two levels for Canvas text.
        assert.ok(restored.maxError <= 2, `${renderer} font ${fontSize} DPR ${scale}, image=${image}, ${attributes}${text}: SGR 22 ${JSON.stringify(restored)}`);
      }
      assert.deepEqual(errors, []);
      console.log(`${renderer} font ${fontSize} DPR ${scale}: configurable faint text, emoji, five underline styles, strike and overline over colors/images passed`);
    } finally { await page.close(); }
  }
} finally { await browser.close(); }
