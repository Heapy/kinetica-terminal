import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";

const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const report = { renderers: [], errors: [] };
const directory = "build/reports/terminal-selection";
await mkdir(directory, { recursive: true });
try {
  for (const platform of ["MacIntel", "Linux x86_64"]) for (const renderer of ["gpu", "software"]) {
    const page = await browser.newPage({ viewport: { width: 900, height: 600 }, deviceScaleFactor: 2 });
    await page.addInitScript(platform => {
      Object.defineProperty(navigator, "platform", { get: () => platform });
      globalThis.selectionClock = 1000;
      window.addEventListener("pointerdown", event => Object.defineProperty(event, "timeStamp", { value: selectionClock }), true);
    }, platform);
    page.on("pageerror", error => report.errors.push(String(error)));
    await page.goto(`${process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173"}/samples/browser-terminal/web/index.html?renderer=${renderer}`);
    await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 20);
    assert.equal(await page.evaluate(() => kineticaTerminalDemo.fontMode), "bundled");
    const geometry = await page.evaluate(() => {
      const { width, height } = kineticaTerminalDemo.cellMetrics();
      const app = document.querySelector("#app");
      app.style.cssText = `flex:none;width:${Math.ceil(width * 20)}px;height:${height * 5}px`;
      const rect = document.querySelector("canvas").getBoundingClientRect();
      return { x: rect.x, y: rect.y, width, height };
    });
    await page.waitForFunction(() => kineticaTerminalDemo.columns() === 20 && kineticaTerminalDemo.rows() === 5);
    assert.equal(await page.locator("[data-terminal]").getAttribute("data-terminal-renderer"), renderer === "gpu" ? "webgl2" : "canvas2d");
    const paint = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const write = text => page.evaluate(text => kineticaTerminalDemo.write(text), text);
    const reset = async text => {
      await page.evaluate(() => kineticaTerminalDemo.reset());
      await write(`\x1b[?25l${text}`); await paint();
    };
    const point = (x, y) => [geometry.x + (x + 0.3) * geometry.width, geometry.y + (y + 0.5) * geometry.height];
    const click = async (x, y, count = 1) => {
      await page.evaluate(() => { selectionClock += 1000; });
      await page.mouse.click(...point(x, y), { clickCount: count });
    };
    const move = (x, y) => page.mouse.move(...point(x, y));
    const copy = () => page.evaluate(() => {
      const data = new DataTransfer();
      document.querySelector("[data-terminal]").dispatchEvent(new ClipboardEvent("copy", { clipboardData: data, bubbles: true, cancelable: true }));
      return data.getData("text/plain");
    });
    // Sample above the glyph ink while the WebGL framebuffer is still valid.
    await page.evaluate(({ width, height }) => {
      globalThis.selectionPixel = null; globalThis.rectanglePixels = null;
      const gl = document.querySelector("canvas").getContext("webgl2");
      if (!gl) return;
      const draw = gl.drawArraysInstanced.bind(gl);
      gl.drawArraysInstanced = (...args) => {
        draw(...args);
        const pixel = new Uint8Array(4);
        gl.readPixels(Math.floor((width + 1) * devicePixelRatio), gl.drawingBufferHeight - 3, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixel);
        selectionPixel = [...pixel];
        const sample = x => {
          gl.readPixels(Math.floor((x * width + 1) * devicePixelRatio), gl.drawingBufferHeight - Math.floor(height * devicePixelRatio) - 3,
            1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixel);
          return [...pixel];
        };
        rectanglePixels = [sample(0), sample(2), sample(5)];
      };
    }, geometry);
    const pixel = () => page.evaluate(({ width }) => selectionPixel ?? [...document.querySelector("canvas").getContext("2d").getImageData(Math.floor((width + 1) * devicePixelRatio), 2, 1, 1).data], geometry);
    const rectanglePixelsAt = () => page.evaluate(({ width, height }) => rectanglePixels ??
      [0, 2, 5].map(x => [...document.querySelector("canvas").getContext("2d").getImageData(
        Math.floor((x * width + 1) * devicePixelRatio), Math.floor(height * devicePixelRatio) + 2, 1, 1).data]), geometry);
    await reset("alpha-beta gamma\r\nnext");
    await click(2, 0, 2); await paint();
    assert.equal(await copy(), "alpha-beta");
    assert.deepEqual(await pixel(), [38, 79, 120, 255]);
    await write("\x1b[3;1Hunrelated"); await paint();
    assert.equal(await copy(), "alpha-beta");
    assert.deepEqual(await pixel(), [38, 79, 120, 255]);
    await click(2, 0, 3);
    assert.equal(await copy(), "alpha-beta gamma");
    await move(2, 0); await page.mouse.down({ clickCount: 3 });
    await move(1, 1); await page.mouse.up({ clickCount: 3 });
    assert.equal(await copy(), "alpha-beta gamma\nnext");
    assert.equal(await page.evaluate(() => window.getSelection().toString()), "", "No browser DOM selection should accompany terminal selection");
    await write("\x1b[H!"); await paint();
    assert.equal(await copy(), "");
    assert.deepEqual(await pixel(), [30, 30, 30, 255]);

    await reset("abc界é xyz");
    await move(4, 0); await page.mouse.down(); await move(6, 0); await page.mouse.up();
    assert.equal(await copy(), "界é", "Wide tail and combining suffix must copy whole glyphs");
    await page.keyboard.type("q");
    assert.equal(await copy(), "", "Committed input clears selection");
    await reset("x".repeat(30) + "\r\nend");
    await click(2, 1, 3);
    assert.equal(await copy(), "x".repeat(30), "Triple click spans soft wraps");

    await reset("\x1b]133;A\x07 p \x1b]133;B\x07 cmd \x1b]133;C\x07 out ");
    await click(4, 0, 3);
    assert.equal(await copy(), "cmd", "Triple click selects only the trimmed command region");
    await move(4, 0); await page.mouse.down({ clickCount: 3 });
    await move(10, 0);
    assert.equal(await copy(), "cmd  out");
    await move(1, 0); await page.mouse.up({ clickCount: 3 });
    assert.equal(await copy(), "p  cmd", "Reverse drag preserves spaces between semantic regions");

    await reset("\x1b]133;A\x07$ \x1b]133;B\x07one\x1b]133;C\x07\r\nfirst\r\nsecond\r\n" +
      "\x1b]133;A\x07$ \x1b]133;B\x07two\x1b]133;C\x07\r\nthird");
    const outputModifier = await page.evaluate(() => navigator.platform.startsWith("Mac") ? "Meta" : "Control");
    await page.keyboard.down(outputModifier);
    await click(1, 1, 3);
    assert.equal(await copy(), "first\nsecond", "Modified triple-click selects the command's multiline output");
    await move(1, 1); await page.mouse.down({ clickCount: 3 });
    await move(1, 4);
    assert.equal(await copy(), "first\nsecond\n$ two\nthird", "Dragging expands by output blocks");
    await move(1, 3); await page.mouse.up({ clickCount: 3 });
    assert.equal(await copy(), "first\nsecond", "Dragging onto a prompt returns to the original block");
    await page.keyboard.up(outputModifier);

    await reset("0123456\r\nabcdefg\r\nABCDEFG");
    const moveFraction = (x, y) => page.mouse.move(geometry.x + x * geometry.width, geometry.y + (y + 0.5) * geometry.height);
    if (platform.startsWith("Linux")) await page.keyboard.down("Control");
    await page.keyboard.down("Alt");
    await moveFraction(1.2, 0); await page.mouse.down(); await moveFraction(3.8, 2);
    assert.equal(await copy(), "123\nbcd\nBCD", "Platform modifiers select a rectangle");
    await paint();
    assert.deepEqual(await rectanglePixelsAt(), [[30, 30, 30, 255], [38, 79, 120, 255], [30, 30, 30, 255]],
      "Only rectangle columns are highlighted on its interior row");
    await page.keyboard.up("Alt"); await moveFraction(3.8, 2);
    assert.equal(await copy(), "123456\nabcdefg\nABCD", "Releasing Alt changes shape during a held drag");
    // Modifier-only Kitty reports must not clear selection or its active gesture.
    await write("\x1b[>8u");
    await page.keyboard.down("Alt"); await moveFraction(3.8, 2); await page.mouse.up();
    assert.equal(await copy(), "123\nbcd\nBCD");
    await page.keyboard.up("Alt");
    if (platform.startsWith("Linux")) await page.keyboard.up("Control");
    await write("\x1b[<u");
    await page.evaluate(() => { selectionClock += 1000; });
    await page.keyboard.down("Alt");
    await moveFraction(1.2, 0); await page.mouse.down(); await moveFraction(3.8, 2); await page.mouse.up();
    assert.equal(await copy(), platform.startsWith("Mac") ? "123\nbcd\nBCD" : "123456\nabcdefg\nABCD",
      "Alt alone only selects rectangles on macOS");
    await page.keyboard.up("Alt");
    await page.evaluate(() => { selectionClock += 1000; });
    await moveFraction(2.2, 0); await page.mouse.down(); await moveFraction(2.8, 0);
    assert.equal(await copy(), "2", "Crossing the threshold selects within one cell");
    await moveFraction(2.3, 0); await page.mouse.up();
    assert.equal(await copy(), "", "Returning before the threshold clears selection");

    const url = "https://e.test/a(b);q=1";
    await reset(`[${url}].`);
    await click(3, 0, 2);
    assert.equal(await copy(), url, "Double click and release retain the complete visible URL across soft wraps");
    await click(3, 0); // First press of a fresh word-drag sequence.
    await move(3, 0); await page.mouse.down({ clickCount: 2 });
    await move(4, 0); await page.mouse.up({ clickCount: 2 });
    assert.equal(await copy(), "https", "Dragging resumes Ghostty word granularity");

    await reset("one  (two);three");
    await click(3, 0, 2);
    assert.equal(await copy(), "  (", "Adjacent delimiters form one selection");
    await page.evaluate(() => kineticaTerminalDemo.selectionWordChars(" "));
    await click(7, 0, 2);
    assert.equal(await copy(), "(two);three", "Host word-boundary policy reaches the browser view");
    for (const target of ["mailto:me@example.org", "ssh://example.org/a", "/tmp/test folder/file.txt", "src/a.kt:42"]) {
      await reset(`(${target})`);
      await click(3, 0, 2);
      assert.equal(await copy(), target, "Detected paths and non-HTTP schemes override word boundaries");
    }

    await reset(Array.from({ length: 80 }, (_, i) => `line-${i}\r\n`).join(""));
    await move(4, 2); await page.mouse.down(); await move(4, -2);
    await page.waitForFunction(() => {
      const data = new DataTransfer();
      document.querySelector("[data-terminal]").dispatchEvent(new ClipboardEvent("copy", { clipboardData: data, bubbles: true, cancelable: true }));
      return data.getData("text/plain").includes("line-70");
    });
    await page.mouse.up();
    const selected = await copy();
    await page.waitForTimeout(180);
    assert.equal(await copy(), selected, "Release outside the canvas stops autoscroll");
    await write("more\r\n");
    assert.equal(await copy(), selected, "Selected history survives unrelated scrolling output");
    await paint(); await page.screenshot({ path: `${directory}/${renderer}.png` });

    // Cancellation and focus loss must each stop an active timer and retain copied text.
    for (const cancellation of ["pointercancel", "blur"]) {
      await reset(Array.from({ length: 80 }, (_, i) => `line-${i}\r\n`).join(""));
      await page.evaluate(() => {
        document.querySelector("[data-terminal]").addEventListener("pointerdown", e => { globalThis.selectionPointer = e.pointerId; }, { once: true });
      });
      await move(4, 2); await page.mouse.down(); await move(4, -2);
      const initial = await copy();
      // Wait for observed progress, not a wall-clock assumption about Chromium timers.
      await page.waitForFunction(initial => {
        const data = new DataTransfer();
        document.querySelector("[data-terminal]").dispatchEvent(new ClipboardEvent("copy", { clipboardData: data, bubbles: true, cancelable: true }));
        return data.getData("text/plain") !== initial;
      }, initial, { timeout: 3000 });
      await page.evaluate(cancellation => {
        if (cancellation === "blur") document.querySelector("textarea").blur();
        else document.querySelector("[data-terminal]").dispatchEvent(new PointerEvent("pointercancel", { pointerId: selectionPointer, bubbles: true }));
      }, cancellation);
      const canceled = await copy();
      await page.waitForTimeout(180);
      assert.equal(await copy(), canceled, `${cancellation} stops autoscroll`);
      await page.mouse.up();
    }
    await move(4, 2); await page.mouse.down(); await move(4, -2);
    await page.evaluate(() => kineticaTerminalDemo.dispose());
    await page.waitForTimeout(180); await page.mouse.up();
    assert.equal(await page.locator("#app > *").count(), 0);
    report.renderers.push({ platform, renderer, rectangularSelection: "passed", pointerThresholds: "passed", selection: "passed", semanticLines: "passed", commandOutput: "passed", doubleClickUrls: "passed", detectedPaths: "passed", wordBoundaries: "passed", autoscroll: "passed", cancellation: "passed", pixels: "passed" });
    await page.close();
  }
  assert.deepEqual(report.errors, []);
  await writeFile(`${directory}/report.json`, JSON.stringify(report, null, 2) + "\n");
  console.log(JSON.stringify(report));
} finally { await browser.close(); }
