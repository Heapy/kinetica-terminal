import assert from "node:assert/strict";
const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE});
const page = await browser.newPage();
const errors = [];
page.on("pageerror", error => errors.push(String(error)));
try {
  await page.goto(`${process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173"}/samples/browser-terminal/web/index.html`);
  await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 0);
  await page.evaluate(() => {
    globalThis.titleReply = [];
    kineticaTerminalDemo.connectTransport({
      write(bytes) { titleReply.push(...bytes); return bytes.length; },
      resize() {}, consumed() {}, rejected() { throw Error("Title response rejected"); },
      failed(message) { throw Error(message); },
    });
    kineticaTerminalDemo.write("\x1b]2;disabled\x07\x1b[21t");
  });
  assert.deepEqual(await page.evaluate(() => titleReply), []);
  const title = "x".repeat(1023) + "😀";
  await page.evaluate(title => {
    kineticaTerminalDemo.titleReporting(true);
    kineticaTerminalDemo.write("\x1b]2;" + title + "\x1b\\\x1b[21t");
  }, title);
  await page.waitForFunction(() => titleReply.length === 1029);
  const expected = Buffer.concat([Buffer.from("\x1b]l"), Buffer.from(title).subarray(0, 1024), Buffer.from("\x1b\\")]);
  assert.deepEqual(Buffer.from(await page.evaluate(() => titleReply)), expected);
  await page.evaluate(() => {
    titleReply.length = 0;
    kineticaTerminalDemo.write("\x1bc\x1b[21t");
  });
  await page.waitForFunction(() => titleReply.length === 5);
  assert.equal(Buffer.from(await page.evaluate(() => titleReply)).toString(), "\x1b]l\x1b\\");
  await page.evaluate(() => {
    titleReply.length = 0;
    kineticaTerminalDemo.titleReporting(false);
    kineticaTerminalDemo.write("\x1b]0;silent\x07\x1b[21t");
  });
  assert.deepEqual(await page.evaluate(() => titleReply), []);
  assert.deepEqual(errors, []);
  console.log("Title reporting: default denial, exact UTF-8 truncation bytes, reset and revocation passed");
} finally { await browser.close(); }
