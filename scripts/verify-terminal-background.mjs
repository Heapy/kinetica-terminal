import assert from "node:assert/strict";

const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const base = process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173";
const rgb = value => [value >> 16 & 255, value >> 8 & 255, value & 255, 255];
try {
  for (const renderer of ["software", "gpu"]) for (const scale of [1, 1.25, 2]) {
    const page = await browser.newPage({ viewport: { width: 503, height: 277 }, deviceScaleFactor: scale });
    const errors = [];
    page.on("pageerror", error => errors.push(String(error)));
    try {
      await page.goto(`${base}/samples/browser-terminal/web/index.html?renderer=${renderer}`);
      await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 20);
      assert.equal(await page.locator("[data-terminal]").getAttribute("data-terminal-renderer"), renderer === "gpu" ? "webgl2" : "canvas2d");
      const painted = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      const write = async value => { await page.evaluate(value => kineticaTerminalDemo.write(value), value); await painted(); };
      let geometry;
      for (let offset = 0; offset < 20; offset++) {
        await page.setViewportSize({ width: 503 + offset, height: 277 + offset });
        await painted();
        geometry = await page.evaluate(() => {
          const c = document.querySelector("canvas"), m = kineticaTerminalDemo.cellMetrics();
          return { right: c.width - Math.round(kineticaTerminalDemo.columns() * m.width * devicePixelRatio),
            bottom: c.height - Math.round(kineticaTerminalDemo.rows() * m.height * devicePixelRatio) };
        });
        if (geometry.right >= 2 && geometry.bottom >= 2) break;
      }
      assert.ok(geometry.right >= 2 && geometry.bottom >= 2, `Must exercise right and bottom margins: ${JSON.stringify(geometry)}`);
      await page.evaluate(renderer => {
        const canvas = document.querySelector("canvas"), m = kineticaTerminalDemo.cellMetrics();
        globalThis.backgroundPaints = 0;
        globalThis.backgroundExtra = null;
        const points = () => [[canvas.width - 1, 1], [1, canvas.height - 1], [canvas.width - 1, canvas.height - 1],
          [Math.ceil(2 * m.width * devicePixelRatio) + 1, 1], ...(backgroundExtra ? [backgroundExtra] : [])];
        if (renderer === "gpu") {
          const gl = canvas.getContext("webgl2"), draw = gl.drawArraysInstanced.bind(gl);
          gl.drawArraysInstanced = (...args) => {
            draw(...args);
            backgroundPaints++;
            globalThis.backgroundPixels = points().map(([x, y]) => {
              const bytes = new Uint8Array(4);
              gl.readPixels(x, gl.drawingBufferHeight - 1 - y, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, bytes);
              return [...bytes];
            });
          };
          globalThis.captureBackground = () => backgroundPixels;
        } else {
          const ctx = canvas.getContext("2d"), fill = ctx.fillRect.bind(ctx);
          ctx.fillRect = (...args) => { fill(...args); backgroundPaints++; };
          globalThis.captureBackground = () => points().map(([x, y]) => [...ctx.getImageData(x, y, 1, 1).data]);
        }
      }, renderer);
      const check = async (color, label) => {
        const pixels = await page.evaluate(() => captureBackground());
        for (const [index, pixel] of pixels.entries()) assert.deepEqual(pixel, rgb(color), `${renderer} DPR ${scale}: ${label}, probe ${index}`);
      };
      await write("\x1bc\x1b[?25l\x1b]111\x07");
      await check(0x1e1e1e, "configured initial background");
      await write("\x1b]11;#123456\x07");
      await check(0x123456, "OSC 11 updates both grid and margins");
      await write("\x1b]21;background=#654321\x1b\\");
      await check(0x654321, "Kitty color update");
      await write("\x1b]111\x07");
      await check(0x1e1e1e, "OSC 111 restores configured default");
      await page.evaluate(() => kineticaTerminalDemo.configureTheme(0x234567)); await painted();
      await check(0x234567, "host theme update");
      await write("\x1b]10;#abcdef\x07\x1b[?5h");
      await check(0xabcdef, "DEC reverse defaults");
      await write("\x1b[?5l"); await check(0x234567, "reverse reset");
      await write("\x1b[?2026h\x1b]11;#345678\x07");
      const before = await page.evaluate(() => backgroundPaints);
      const box = await page.locator("canvas").boundingBox();
      const metrics = await page.evaluate(() => kineticaTerminalDemo.cellMetrics());
      await page.mouse.click(box.x + 5 * metrics.width + 2, box.y + 2 * metrics.height + 2);
      await painted();
      assert.equal(await page.evaluate(() => kineticaTerminalDemo.synchronizedOutput()), true);
      assert.ok(await page.evaluate(() => backgroundPaints) > before, "Force a real repaint while the frame is held");
      await check(0x234567, "held frame keeps its old background");
      await write("\x1b[?2026l"); await check(0x345678, "hold release publishes new background");
      await write(`\x1b[3;3H\x1b_Ga=T,q=2,i=44,f=24,s=1,v=1,c=1,r=1,C=1,z=-1;${Buffer.from([255, 0, 0]).toString("base64")}\x1b\\\x1b]11;#456789\x07`);
      await check(0x456789, "image composition path");
      await write("\x1b_Ga=d,d=A,q=2\x1b\\\x1b]11;#56789a\x07");
      await check(0x56789a, "return from image composition");
      // Keep the host surface fixed while DECCOLM reduces the grid to 80 columns.
      await page.setViewportSize({ width: 1253, height: 277 }); await painted();
      await write("\x1b[?40h\x1b[?3h\x1b[?25l");
      assert.equal(await page.evaluate(() => kineticaTerminalDemo.columns()), 132);
      await page.evaluate(() => {
        const m = kineticaTerminalDemo.cellMetrics();
        globalThis.backgroundExtra = [Math.ceil(99 * m.width * devicePixelRatio) + 1, Math.ceil(m.height * devicePixelRatio) + 1];
      });
      await write("\x1b[2;100H\x1b[48;2;255;0;0m \x1b[0m");
      assert.deepEqual((await page.evaluate(() => captureBackground())).at(-1), rgb(0xff0000));
      await write("\x1b[?3l");
      assert.equal(await page.evaluate(() => kineticaTerminalDemo.columns()), 80);
      await check(0x56789a, "shrinking grid clears old cells outside it");
      assert.deepEqual(errors, []);
      console.log(`${renderer} DPR ${scale}: margins follow colors, theme, reverse, held frames, images and grid shrink`);
    } finally { await page.close(); }
  }
} finally { await browser.close(); }
