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
    globalThis.capabilityBytes = [];
    globalThis.capabilityWritable = true;
    kineticaTerminalDemo.connectTransport({
      write(bytes) {
        if (!capabilityWritable) return 0;
        const size = Math.min(bytes.length, 4093);
        for (let i = 0; i < size; i++) capabilityBytes.push(bytes[i]);
        return size;
      },
      resize() {}, consumed() {}, rejected() { throw Error("Capability input rejected"); },
      failed(message) { throw Error(message); },
    });
  });
  async function feed(text) {
    assert.equal(await page.evaluate(text => kineticaTerminalDemo.receiveBytes(new TextEncoder().encode(text)), text), true);
  }
  async function idle() {
    await page.waitForFunction(() => {
      const state = kineticaTerminalDemo.transportState();
      return !state.paused && state.queuedOutputBytes === 0 && state.pendingInputBytes === 0;
    });
  }
  await feed("\x1bP1;2+q436f;616d;544e;WHO;5;GG\x1b\\");
  await idle();
  assert.equal(Buffer.from(await page.evaluate(() => capabilityBytes)).toString(), "\x1bP1+r436F=323536\x1b\\\x1bP1+r616D\x1b\\");
  const name = "xterm-" + "x".repeat(122);
  await page.evaluate(name => {
    kineticaTerminalDemo.terminfoName(name);
    capabilityBytes.length = 0; capabilityWritable = false;
  }, name);
  const count = 4000;
  const request = "\x1bP+q" + Array(count).fill("544e").join(";") + "\x1b\\CAPS_OK";
  await feed(request);
  await page.waitForFunction(() => kineticaTerminalDemo.transportState().pendingInputBytes > 0);
  const blocked = await page.evaluate(() => kineticaTerminalDemo.transportState());
  assert.equal(blocked.paused, true);
  assert.ok(blocked.pendingInputBytes <= 32768);
  assert.ok(!(await page.evaluate(() => kineticaTerminalDemo.screen())).includes("CAPS_OK"));
  await page.evaluate(() => { capabilityWritable = true; });
  await idle();
  const expected = `\x1bP1+r544E=${Buffer.from(name).toString("hex").toUpperCase()}\x1b\\`.repeat(count);
  assert.equal(Buffer.from(await page.evaluate(() => capabilityBytes)).toString(), expected);
  assert.ok((await page.evaluate(() => kineticaTerminalDemo.screen())).includes("CAPS_OK"));
  await page.evaluate(() => { capabilityWritable = false; capabilityBytes.length = 0; });
  await feed(request);
  await page.waitForFunction(() => kineticaTerminalDemo.transportState().pendingInputBytes > 0);
  await page.evaluate(() => kineticaTerminalDemo.disposeTransport());
  assert.deepEqual(await page.evaluate(() => capabilityBytes), []);
  assert.equal(await page.evaluate(() => kineticaTerminalDemo.transportState().disposed), true);
  assert.deepEqual(errors, []);
  console.log("XTGETTCAP: static replies, configured TN, bounded large replies, backpressure and cancellation passed");
} finally { await browser.close(); }
