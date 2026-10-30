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
            globalThis.decorationPixels = pixels;
          };
          globalThis.captureDecoration = () => decorationPixels.slice();
        } else {
          const ctx = canvas.getContext("2d");
          globalThis.captureDecoration = () => ctx.getImageData(0, 0, canvas.width, canvas.height).data;
        }
      }, renderer);
      const write = async value => {
        await page.evaluate(value => kineticaTerminalDemo.write(value), value);
        await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      };
      for (const image of [false, true]) for (const faint of [false, true]) for (const style of [1, 2, 3, 4, 5, "strike"]) {
        const strike = style === "strike", text = strike ? "😀😀" : "gypq██", cells = strike ? 4 : 6;
        const decoration = strike ? "9" : `4:${style};58;2;0;255;0`;
        const pen = `38;2;255;255;255${faint ? ";2" : ""}`;
        const reset = "\x1bc\x1b[?25l\x1b]11;#000000\x07" + (image ? `\x1b_Ga=T,q=2,i=44,f=24,s=1,v=1,c=6,r=2,C=1,z=-1;${Buffer.from([0, 0, 120]).toString("base64")}\x1b\\` : "");
        await write(reset);
        await page.evaluate(() => { globalThis.decorationBackground = captureDecoration(); });
        await write(`\x1b[H\x1b[${pen}m${text}`);
        await page.evaluate(() => { globalThis.decorationText = captureDecoration(); });
        await write(`${reset}\x1b[H\x1b[${pen};${decoration}m${" ".repeat(cells)}`);
        await page.evaluate(() => { globalThis.decorationLine = captureDecoration(); });
        await write(`${reset}\x1b[H\x1b[${pen};${decoration}m${text}`);
        const result = await page.evaluate(strike => {
          const actual = captureDecoration();
          let maxError = 0, overlaps = 0, worstByte = -1;
          for (let i = 0; i < actual.length; i += 4) {
            const background = decorationBackground[i];
            const coverage = Math.max(0, Math.min(1, ((strike ? decorationLine[i] : decorationText[i]) - background) / (255 - background)));
            if (coverage > 0.1 && (strike ? decorationText[i + 1] : decorationLine[i + 1]) - decorationBackground[i + 1] > 30) overlaps++;
            for (let channel = 0; channel < 3; channel++) {
              const behind = strike ? decorationText[i + channel] : decorationLine[i + channel];
              const expected = Math.round(255 * coverage + behind * (1 - coverage));
              const error = Math.abs(actual[i + channel] - expected);
              if (error > maxError) { maxError = error; worstByte = i + channel; }
            }
          }
          const inkRows = pixels => {
            const width = document.querySelector("canvas").width, rows = new Set();
            for (let i = 0; i < pixels.length; i += 4) if (pixels[i] > 25 || pixels[i + 1] > 30) rows.add(Math.floor(i / 4 / width));
            return [...rows];
          };
          const start = Math.floor(worstByte / 4) * 4;
          return { maxError, overlaps, worstByte, ...(maxError ? {
            glyph: [...decorationText.slice(start, start + 4)], line: [...decorationLine.slice(start, start + 4)], actual: [...actual.slice(start, start + 4)],
          } : {}), ...(overlaps ? {} : { glyphRows: inkRows(decorationText), lineRows: inkRows(decorationLine) }) };
        }, strike);
        assert.ok(result.overlaps > 4, `${renderer} DPR ${scale}, image=${image}, faint=${faint}, style=${style}: insufficient overlap ${JSON.stringify(result)}`);
        assert.ok(result.maxError <= 3, `${renderer} DPR ${scale}, image=${image}, faint=${faint}, style=${style}: ${JSON.stringify(result)}`);
      }
      assert.deepEqual(errors, []);
      console.log(`${renderer} DPR ${scale}: colored underlines behind descenders/blocks and strike over emoji, with faint and images passed`);
    } finally { await page.close(); }
  }
} finally { await browser.close(); }
