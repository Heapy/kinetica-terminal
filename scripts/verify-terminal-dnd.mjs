import assert from "node:assert/strict";
const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const page = await browser.newPage();
const errors = [];
page.on("pageerror", error => errors.push(String(error)));
try {
  await page.goto(`${process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173"}/samples/browser-terminal/web/index.html`);
  await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 0);
  await page.evaluate(() => {
    globalThis.dndBytes = [];
    globalThis.dndWritable = true;
    kineticaTerminalDemo.connectTransport({
      write(bytes) {
        if (!dndWritable) return 0;
        const size = Math.min(bytes.length, 7);
        for (let i = 0; i < size; i++) dndBytes.push(bytes[i]);
        return size;
      },
      resize() {}, consumed() {}, rejected() { throw Error("DnD response rejected"); },
      failed(message) { throw Error(message); },
    });
  });
  async function feed(bytes) {
    assert.equal(await page.evaluate(bytes => kineticaTerminalDemo.receiveBytes(new Uint8Array(bytes)), [...bytes]), true);
  }
  async function idle() {
    await page.waitForFunction(() => {
      const state = kineticaTerminalDemo.transportState();
      return !state.paused && state.queuedOutputBytes === 0 && state.pendingInputBytes === 0;
    });
  }
  // A binary registration split across transport writes; its ID must survive RIS.
  await feed(Buffer.from("\x1b]72;t=a:i=7;mimetype"));
  await idle();
  await feed(Buffer.from([0x80, 0x9c, 0xff, 7]));
  await feed(Buffer.from("\x1bc\x1b]72;t=r:x=1\x07\x1b]72;t=q:i=9\x1b\\"));
  await idle();
  assert.equal(Buffer.from(await page.evaluate(() => dndBytes)).toString(),
    "\x1b]72;t=R:x=1:i=7:m=0;ENOENT:no drop data available\x07\x1b]72;t=q:i=9\x1b\\");
  await page.evaluate(() => { dndBytes.length = 0; dndWritable = false; });
  const count = 1000;
  const requests = "\x1b]72;t=q:i=42\x07".repeat(count) + "DND_OK";
  await feed(Buffer.from(requests));
  await page.waitForFunction(() => kineticaTerminalDemo.transportState().pendingInputBytes > 0);
  const blocked = await page.evaluate(() => kineticaTerminalDemo.transportState());
  assert.equal(blocked.paused, true);
  assert.ok(blocked.pendingInputBytes <= 32768);
  assert.ok(!(await page.evaluate(() => kineticaTerminalDemo.screen())).includes("DND_OK"));
  await page.evaluate(() => { dndWritable = true; });
  await idle();
  assert.equal(Buffer.from(await page.evaluate(() => dndBytes)).toString(), "\x1b]72;t=q:i=42\x07".repeat(count));
  assert.ok((await page.evaluate(() => kineticaTerminalDemo.screen())).includes("DND_OK"));
  await page.evaluate(() => { dndWritable = false; dndBytes.length = 0; });
  await feed(Buffer.from(requests));
  await page.waitForFunction(() => kineticaTerminalDemo.transportState().pendingInputBytes > 0);
  await page.evaluate(() => kineticaTerminalDemo.disposeTransport());
  assert.deepEqual(await page.evaluate(() => dndBytes), []);
  assert.equal(await page.evaluate(() => kineticaTerminalDemo.transportState().disposed), true);
  assert.deepEqual(errors, []);
  console.log("OSC 72 session: binary registration, reset, exact replies, backpressure and cancellation passed");
} finally { await browser.close(); }
