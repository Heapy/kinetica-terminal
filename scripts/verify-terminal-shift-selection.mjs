import assert from "node:assert/strict";

const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const base = process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173";
try {
  for (const platform of ["MacIntel", "Linux x86_64"]) for (const renderer of ["software", "gpu"]) {
    const page = await browser.newPage({ viewport: { width: 850, height: 500 } });
    const errors = [];
    page.on("pageerror", error => errors.push(String(error)));
    try {
      await page.addInitScript(platform => {
        Object.defineProperty(navigator, "platform", { get: () => platform });
        globalThis.selectionTime = 100;
        // Real pointer/mouse events, with a deterministic monotonic clock for interval boundaries.
        for (const type of ["pointerdown", "mousedown"]) window.addEventListener(type, event =>
          Object.defineProperty(event, "timeStamp", { value: selectionTime }), true);
      }, platform);
      await page.goto(`${base}/samples/browser-terminal/web/index.html?renderer=${renderer}&fonts=system`);
      await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 40);
      await page.evaluate(() => {
        globalThis.selectionBytes = [];
        kineticaTerminalDemo.connectTransport({ write(bytes) { selectionBytes.push(...bytes); return bytes.length; }, resize() {}, consumed() {},
          rejected() { throw Error("Input rejected"); }, failed(message) { throw Error(message); } });
      });
      const geometry = await page.evaluate(() => {
        const rect = document.querySelector("canvas").getBoundingClientRect();
        return { x: rect.x, y: rect.y, ...kineticaTerminalDemo.cellMetrics() };
      });
      const point = (x, y = 0) => [geometry.x + x * geometry.width, geometry.y + (y + 0.5) * geometry.height];
      const time = value => page.evaluate(value => { selectionTime = value; }, value);
      const click = (x, count = 1) => page.mouse.click(...point(x), { clickCount: count });
      const write = text => page.evaluate(text => kineticaTerminalDemo.write(text), text);
      const copy = () => page.evaluate(() => {
        const data = new DataTransfer();
        document.querySelector("[data-terminal]").dispatchEvent(new ClipboardEvent("copy", { clipboardData: data, bubbles: true, cancelable: true }));
        return data.getData("text/plain");
      });
      const drain = async () => {
        await page.waitForFunction(() => kineticaTerminalDemo.transportState().pendingInputBytes === 0);
        return Buffer.from(await page.evaluate(() => selectionBytes.splice(0))).toString();
      };
      await write("\x1bcalpha beta gamma"); await click(1.2, 2); assert.equal(await copy(), "alpha");
      await page.keyboard.down("Shift"); await time(601); await click(7.2); assert.equal(await copy(), "alpha beta");
      await time(602); await click(12.2, 2); assert.equal(await copy(), "alpha beta gamma", "Keep origin, mode and original timestamp");
      await page.keyboard.up("Shift"); await time(1000); await click(7.2, 2); assert.equal(await copy(), "beta");
      await page.keyboard.down("Shift"); await time(1500); await click(7.3);
      assert.equal(await copy(), "alpha beta gamma", "At the interval boundary, a nearby third press selects the line");
      await write("\x1b[?1000h\x1b[?1006h"); await time(2001); await click(1.2);
      assert.equal(await copy(), "alpha beta gamma"); assert.equal(await drain(), "");
      await write("\x1b[>1s"); await time(2002); await click(1.2);
      assert.equal(await copy(), ""); assert.match(await drain(), /\x1b\[<4;2;1M/);
      await page.keyboard.up("Shift"); await write("\x1bc0123456789\r\nabcdefghij"); await time(3000);
      await page.mouse.move(...point(2.2)); await page.mouse.down(); await page.mouse.move(...point(4.8)); await page.mouse.up();
      assert.equal(await copy(), "234");
      await page.keyboard.down("Shift"); await time(3501); await click(7.8); assert.equal(await copy(), "234567");
      await time(3502); await click(0.2); assert.equal(await copy(), "01");
      await page.keyboard.down("Alt");
      if (platform !== "MacIntel") await page.keyboard.down("Control");
      await time(3503); await page.mouse.click(...point(4.8, 1));
      assert.equal(await copy(), "234\ncde", "Shift plus rectangle modifier keeps the original anchor");
      if (platform !== "MacIntel") await page.keyboard.up("Control");
      await page.keyboard.up("Alt"); await page.keyboard.up("Shift");
      await page.evaluate(() => kineticaTerminalDemo.dispose()); assert.deepEqual(errors, []);
      console.log(`${platform} ${renderer}: Shift-click continuation, interval boundaries, rectangle and mouse capture passed`);
    } finally { await page.close(); }
  }
} finally { await browser.close(); }
