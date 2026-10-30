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
        globalThis.gestureClock = 0;
        window.addEventListener("pointerdown", event => Object.defineProperty(event, "timeStamp", { value: gestureClock }), true);
      }, platform);
      await page.goto(`${base}/samples/browser-terminal/web/index.html?renderer=${renderer}&fonts=system`);
      await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 40);
      await page.evaluate(() => {
        globalThis.gestureBytes = [];
        kineticaTerminalDemo.connectTransport({ write(bytes) { gestureBytes.push(...bytes); return bytes.length; }, resize() {}, consumed() {},
          rejected() { throw Error("Input rejected"); }, failed(message) { throw Error(message); } });
      });
      const geometry = await page.evaluate(() => {
        const rect = document.querySelector("canvas").getBoundingClientRect();
        return { x: rect.x, y: rect.y, ...kineticaTerminalDemo.cellMetrics() };
      });
      const point = (x, row = 0) => [geometry.x + x * geometry.width, geometry.y + (row + 0.5) * geometry.height];
      const time = value => page.evaluate(value => { gestureClock = value; }, value);
      const move = (x, row = 0) => page.mouse.move(...point(x, row));
      const click = async (x, clock) => { await time(clock); await page.mouse.click(...point(x)); };
      const write = text => page.evaluate(text => kineticaTerminalDemo.write(text), text);
      const copy = () => page.evaluate(() => {
        const data = new DataTransfer();
        document.querySelector("[data-terminal]").dispatchEvent(new ClipboardEvent("copy", { clipboardData: data, bubbles: true, cancelable: true }));
        return data.getData("text/plain");
      });
      await write("\x1bc0123456789"); await move(2.2); await page.mouse.down(); await move(4.8);
      assert.equal(await copy(), "234");
      await write("\x1b[?2026h\x1b[H\x1b[2Khidden"); await move(7.8); await page.mouse.up();
      assert.equal(await copy(), "234567", "Held drag crosses capture without losing its original anchor");
      await page.keyboard.down("Shift"); await click(4.8, 501); await page.keyboard.up("Shift");
      assert.equal(await copy(), "234", "Shift continues the edited frozen gesture");
      await write("\x1b[?2026l"); assert.equal(await copy(), "");
      await click(2.2, 502); assert.equal(await copy(), "", "Frozen-only sequence is not reused on the revealed screen");
      await write("\x1bcalpha beta gamma"); await click(1.2, 1000);
      await write("\x1b[?2026h\x1b[?1049hHIDDEN"); await click(1.2, 1100);
      assert.equal(await copy(), "alpha", "Repeat count and pin survive a hidden screen switch");
      await page.keyboard.down("Shift"); await click(7.2, 1601); await page.keyboard.up("Shift");
      assert.equal(await copy(), "alpha beta");
      await write("\x1b[?2026l"); assert.equal(await copy(), "");
      await write("\x1bcalpha beta"); await click(1.2, 2000); await click(1.2, 2010);
      await write("\x1b[?2026h\x1b[Hchanged");
      await page.keyboard.down("Shift"); await click(7.2, 2511); await page.keyboard.up("Shift");
      assert.equal(await copy(), "alpha beta", "A completed word gesture is imported lazily from the snapshot");
      await write("\x1b[?2026l"); assert.equal(await copy(), "");
      await write("\x1bcalpha beta"); await click(1.2, 3000);
      await write("\x1b[?2026h\x1b[?2026l"); await click(1.2, 3010); assert.equal(await copy(), "alpha");
      await write("\x1b[?2026h\x1b[?2026l");
      await page.keyboard.down("Shift"); await click(7.2, 3511); await page.keyboard.up("Shift");
      assert.equal(await copy(), "alpha beta", "Untouched hold does not break the live gesture");
      await page.waitForFunction(() => kineticaTerminalDemo.transportState().pendingInputBytes === 0);
      assert.deepEqual(await page.evaluate(() => gestureBytes), []);
      await page.evaluate(() => kineticaTerminalDemo.dispose()); assert.deepEqual(errors, []);
      console.log(`${platform} ${renderer}: held drag, inherited Shift/word gestures, hidden screen changes and thaw passed`);
    } finally { await page.close(); }
  }
} finally { await browser.close(); }
