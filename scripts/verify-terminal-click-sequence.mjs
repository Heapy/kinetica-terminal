import assert from "node:assert/strict";

const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const base = process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173";
try {
  for (const platform of ["MacIntel", "Linux x86_64"]) for (const renderer of ["software", "gpu"]) {
    const page = await browser.newPage({ viewport: { width: 850, height: 500 }, deviceScaleFactor: 2 });
    const errors = [];
    page.on("pageerror", error => errors.push(String(error)));
    try {
      await page.addInitScript(platform => {
        Object.defineProperty(navigator, "platform", { get: () => platform });
        globalThis.clickClock = 0;
        window.addEventListener("pointerdown", event => Object.defineProperty(event, "timeStamp", { value: clickClock }), true);
      }, platform);
      await page.goto(`${base}/samples/browser-terminal/web/index.html?renderer=${renderer}&fonts=system`);
      await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 40);
      const geometry = await page.evaluate(() => {
        const rect = document.querySelector("canvas").getBoundingClientRect();
        kineticaTerminalDemo.clickRepeatInterval(100);
        kineticaTerminalDemo.copyTrimTrailingSpaces(false);
        return { x: rect.x, y: rect.y, ...kineticaTerminalDemo.cellMetrics() };
      });
      const point = (x, row = 0) => [geometry.x + x * geometry.width, geometry.y + (row + 0.5) * geometry.height];
      const press = async (x, time, row = 0, hint = 1) => {
        await page.evaluate(time => { clickClock = time; }, time);
        await page.mouse.move(...point(x, row)); await page.mouse.down({ clickCount: hint });
      };
      const click = async (x, time, row = 0, hint = 1) => { await press(x, time, row, hint); await page.mouse.up({ clickCount: hint }); };
      const write = text => page.evaluate(text => kineticaTerminalDemo.write(text), text);
      const copy = () => page.evaluate(() => {
        const data = new DataTransfer();
        document.querySelector("[data-terminal]").dispatchEvent(new ClipboardEvent("copy", { clipboardData: data, bubbles: true, cancelable: true }));
        return data.getData("text/plain");
      });
      await write("\x1bcalpha beta\r\nsecond");
      await click(1.2, 0, 0, 99); assert.equal(await copy(), "", "An arbitrary host hint does not create extra presses");
      await click(1.2, 100); assert.equal(await copy(), "alpha", "Exact interval boundary repeats");
      await click(1.2, 200); assert.equal(await copy(), "alpha beta");
      await click(1.2, 201); assert.equal(await copy(), "alpha beta", "Count saturates at three");
      await click(1.2, 302); assert.equal(await copy(), "", "More than configured interval restarts");
      await click(2.3, 303); assert.equal(await copy(), "", "Cell-width distance limit");
      await click(2.3, 304); assert.equal(await copy(), "alpha");
      await click(2.3, 305, 1); assert.equal(await copy(), "", "Vertical distance also counts");
      await click(2.3, 306, 1); assert.equal(await copy(), "second");
      await write("\x1b[?1049hother"); await click(2.3, 307, 1); assert.equal(await copy(), "");
      await write("\x1b[?1049l"); await click(2.3, 308, 1); assert.equal(await copy(), "");
      await write("\x1bcone two three"); await click(2.8, 1000);
      await press(3.7, 1100); assert.equal(await copy(), " ");
      await page.mouse.move(...point(7.2)); await page.mouse.up(); assert.equal(await copy(), "one two ", "Dragging retains the first press pin");
      await page.evaluate(() => kineticaTerminalDemo.clickRepeatInterval(0));
      await click(1.2, 2000); await click(1.2, 2500); assert.equal(await copy(), "one", "Zero restores 500 ms browser fallback");
      await page.evaluate(() => kineticaTerminalDemo.dispose()); assert.deepEqual(errors, []);
      console.log(`${platform} ${renderer}: actual presses, repeat interval/distance, original pin, screen reset and Retina passed`);
    } finally { await page.close(); }
  }
} finally { await browser.close(); }
