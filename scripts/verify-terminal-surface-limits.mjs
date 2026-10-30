import assert from "node:assert/strict";

const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const url = `${process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173"}/samples/browser-terminal/web/index.html?renderer=gpu`;
const red = [240, 20, 30, 255], blue = [10, 20, 200, 255];
const image = "\x1b_Ga=T,q=2,i=1,f=24,s=1,v=1,c=1,r=1,C=1;" + Buffer.from(red.slice(0, 3)).toString("base64") + "\x1b\\";
try {
  for (const ratio of [1, 1.25, 2]) for (const simulated of [false, true]) {
    const page = await browser.newPage({ viewport: { width: 900, height: 400 }, deviceScaleFactor: ratio });
    const errors = [];
    page.on("pageerror", error => errors.push(String(error)));
    if (simulated) await page.addInitScript(() => {
      // Exercise independent viewport-axis/renderbuffer limits without a huge allocation.
      globalThis.surfaceLimitsOverride = [128, 96, 256];
      const get = WebGL2RenderingContext.prototype.getParameter;
      WebGL2RenderingContext.prototype.getParameter = function (parameter) {
        if (parameter === this.MAX_VIEWPORT_DIMS) return new Int32Array(surfaceLimitsOverride.slice(0, 2));
        if (parameter === this.MAX_RENDERBUFFER_SIZE) return surfaceLimitsOverride[2];
        return get.call(this, parameter);
      };
    });
    await page.goto(url);
    await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 60);
    const limits = await page.evaluate(() => {
      const canvas = document.querySelector("canvas"), gl = canvas.getContext("webgl2");
      const root = document.querySelector("[data-terminal]");
      globalThis.surfaceProbe = { canvas, gl, root, frames: 0 };
      const draw = gl.drawArraysInstanced.bind(gl);
      gl.drawArraysInstanced = (...args) => {
        draw(...args);
        const width = Math.min(gl.drawingBufferWidth, 128), height = Math.min(gl.drawingBufferHeight, 96);
        const pixels = new Uint8Array(width * height * 4);
        gl.readPixels(0, gl.drawingBufferHeight - height, width, height, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
        surfaceProbe.pixels = [...pixels]; surfaceProbe.sample = [width, height]; surfaceProbe.frames++;
      };
      const viewport = gl.getParameter(gl.MAX_VIEWPORT_DIMS), buffer = gl.getParameter(gl.MAX_RENDERBUFFER_SIZE);
      return [Math.min(viewport[0], buffer), Math.min(viewport[1], buffer)];
    });
    const settle = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const resize = async (width, height) => {
      await page.evaluate(([width, height]) => {
        surfaceProbe.root.style.cssText += `;position:fixed;left:0;top:0;width:${width}px;height:${height}px;min-width:0;min-height:0;box-sizing:border-box;padding:0;`;
      }, [width, height]);
      await settle();
      await page.evaluate(image => {
        kineticaTerminalDemo.reset();
        kineticaTerminalDemo.write("\x1b[?25l\x1b]11;#0a14c8\x07\x1b[H" + image);
      }, image);
      await settle();
    };
    const check = async (expected, actual = expected) => {
      const state = await page.evaluate(() => {
        const { canvas, gl, root, pixels, sample, frames } = surfaceProbe;
        return { requested: [canvas.width, canvas.height], actual: [gl.drawingBufferWidth, gl.drawingBufferHeight],
          viewport: [...gl.getParameter(gl.VIEWPORT)], css: [parseFloat(canvas.style.width), parseFloat(canvas.style.height)],
          root: [root.clientWidth, root.clientHeight], pixels, sample, frames, metrics: kineticaTerminalDemo.cellMetrics(), error: gl.getError() };
      });
      assert.deepEqual(state.requested, expected, "Backing request stays within both GPU limits");
      assert.deepEqual(state.actual, actual, "Actual allocation may be smaller than the requested attributes");
      assert.deepEqual(state.viewport, [0, 0, ...actual]);
      assert.deepEqual(state.css, actual.map(value => value / ratio), "Keep one device pixel per backing texel");
      assert.equal(state.error, 0);
      assert.ok(state.frames > 0);
      const pixel = (x, y) => {
        const offset = ((state.sample[1] - 1 - y) * state.sample[0] + x) * 4;
        return state.pixels.slice(offset, offset + 4);
      };
      assert.deepEqual(pixel(0, 0), red);
      assert.deepEqual(pixel(Math.floor(state.metrics.width * ratio) - 2, 0), red, "Image width retains physical cell geometry");
      assert.deepEqual(pixel(Math.ceil(state.metrics.width * ratio) + 2, 0), blue);
      assert.deepEqual(pixel(0, Math.floor(state.metrics.height * ratio) - 2), red, "Image height retains physical cell geometry");
      assert.deepEqual(pixel(0, Math.ceil(state.metrics.height * ratio) + 2), blue);
    };
    // Only the long axis exceeds the real device limit: at most ~10 MiB, not a huge square.
    for (const wide of [true, false]) {
      await resize(wide ? (limits[0] + 128) / ratio : 64, wide ? 64 : (limits[1] + 128) / ratio);
      await check([Math.min(Math.floor((wide ? (limits[0] + 128) / ratio : 64) * ratio), limits[0]),
        Math.min(Math.floor((wide ? 64 : (limits[1] + 128) / ratio) * ratio), limits[1])]);
    }
    await page.evaluate(() => { surfaceProbe.root.style.visibility = "hidden"; }); await settle();
    assert.deepEqual(await page.evaluate(() => [surfaceProbe.canvas.width, surfaceProbe.canvas.height]), [0, 0]);
    await page.evaluate(() => { surfaceProbe.root.style.visibility = "visible"; }); await settle();
    await check([Math.min(64 * ratio, limits[0]), limits[1]]);
    if (simulated) {
      await page.evaluate(() => {
        globalThis.surfaceLost = false; globalThis.surfaceRestored = false;
        surfaceProbe.canvas.addEventListener("webglcontextlost", () => { surfaceLost = true; }, { once: true });
        surfaceProbe.canvas.addEventListener("webglcontextrestored", () => { surfaceRestored = true; }, { once: true });
        surfaceProbe.extension = surfaceProbe.gl.getExtension("WEBGL_lose_context");
        surfaceProbe.extension.loseContext();
      });
      await page.waitForFunction(() => surfaceLost);
      await page.evaluate(() => {
        // Restored contexts may expose different limits. The buffer limit now wins on X.
        surfaceLimitsOverride = [128, 80, 96];
        surfaceProbe.extension.restoreContext();
      });
      await page.waitForFunction(() => surfaceRestored); await settle();
      await resize(256 / ratio, 256 / ratio); await check([96, 80]);
    }
    await resize(64, 64); await check([Math.min(64 * ratio, simulated ? 96 : limits[0]), Math.min(64 * ratio, simulated ? 80 : limits[1])]);
    if (simulated) {
      await page.evaluate(() => {
        const prototype = HTMLCanvasElement.prototype;
        // Emulate the WebGL-specified allocation fallback: attributes retain the request,
        // but the real backing buffer is smaller. Pixels still come from a real GL target.
        for (const [key, cap] of [["width", 80], ["height", 64]]) {
          const descriptor = Object.getOwnPropertyDescriptor(prototype, key);
          let requested;
          Object.defineProperty(surfaceProbe.canvas, key, {
            configurable: true,
            get() { return requested ?? descriptor.get.call(this); },
            set(value) { requested = value; descriptor.set.call(this, Math.min(value, cap)); },
          });
        }
      });
      await resize(32, 32); // Change both attributes before returning to the requested cap.
      await resize(256 / ratio, 256 / ratio);
      await check([96, 80], [80, 64]);
    }
    assert.deepEqual(errors, []);
    await page.evaluate(() => kineticaTerminalDemo.dispose());
    assert.deepEqual(await page.evaluate(() => [surfaceProbe.canvas.width, surfaceProbe.canvas.height]), [0, 0]);
    await page.close();
    console.log(`WebGL surface limits: DPR ${ratio}, ${simulated ? "independent and restored limits" : "actual device limits"}, pixels and disposal passed`);
  }
} finally { await browser.close(); }
