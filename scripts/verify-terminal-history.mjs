import assert from "node:assert/strict";

const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
try {
  for (const renderer of ["gpu", "software"]) {
    const page = await browser.newPage({ viewport: { width: 1000, height: 640 }, deviceScaleFactor: 2 });
    const errors = [];
    page.on("pageerror", error => errors.push(String(error)));
    const url = `${process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173"}/samples/browser-terminal/web/index.html`;
    await page.goto(`${url}?renderer=${renderer}`);
    await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 80);
    await page.evaluate(() => document.fonts.ready);
    await page.evaluate(() => {
      kineticaTerminalDemo.reset();
      kineticaTerminalDemo.write("\x1b[?25l" + Array.from({ length: 400 }, (_, i) =>
        `\x1b[38;5;${i % 256}mrow ${String(i).padStart(3, "0")} á 界 🙂\x1b[0m\r\n`).join(""));
    });
    await page.locator("textarea").focus();
    await page.keyboard.press("Shift+PageUp");
    await page.keyboard.press("Shift+PageUp");
    const offset = await page.evaluate(() => kineticaTerminalDemo.scrollOffset());
    assert(offset > 0);
    const painted = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await painted();
    await page.waitForFunction(() => document.querySelector('[role="log"]').textContent.includes("row "));
    const canvas = page.locator("canvas");
    const before = await canvas.screenshot();
    const text = await page.locator('[role="log"]').textContent();
    const logical = await page.evaluate(() => kineticaTerminalDemo.historyStorageBytes());
    await page.waitForFunction(() => kineticaTerminalDemo.historyResidentStorageBytes() < kineticaTerminalDemo.historyStorageBytes() / 2,
      null, { timeout: 10000 });
    const resident = await page.evaluate(() => kineticaTerminalDemo.historyResidentStorageBytes());
    assert.equal(await page.evaluate(() => kineticaTerminalDemo.historyStorageBytes()), logical, "Logical history budget is unchanged");
    assert.deepEqual(await canvas.screenshot(), before, `${renderer}: idle compression preserves pixels`);

    // Moving away and back rebuilds the frame from history, including decoded cell arrays.
    await page.keyboard.press("Shift+PageUp");
    await painted();
    await page.keyboard.press("Shift+PageDown");
    await painted();
    assert.equal(await page.evaluate(() => kineticaTerminalDemo.scrollOffset()), offset);
    assert.deepEqual(await canvas.screenshot(), before, `${renderer}: history readback preserves styled Unicode pixels`);
    await page.waitForFunction(text => document.querySelector('[role="log"]').textContent === text, text);
    assert.equal(await page.locator('[role="log"]').textContent(), text);

    // Hidden history and released renderer resources must reconstruct together.
    await page.evaluate(() => { document.querySelector("[data-terminal]").style.display = "none"; });
    await page.waitForTimeout(1200);
    await page.evaluate(() => { document.querySelector("[data-terminal]").style.display = ""; });
    await painted();
    assert.deepEqual(await canvas.screenshot(), before, `${renderer}: showing compressed history restores pixels`);

    await page.evaluate(() => {
      kineticaTerminalDemo.dispose(); kineticaTerminalDemo.reset();
      kineticaTerminalDemo.write("after disposal\r\n".repeat(100));
    });
    await page.waitForTimeout(1200);
    assert.equal(await page.evaluate(() => kineticaTerminalDemo.historyResidentStorageBytes()),
      await page.evaluate(() => kineticaTerminalDemo.historyStorageBytes()), "Disposal cancels the idle worker");
    assert.deepEqual(errors, []);
    console.log(`${renderer}: idle history ${logical} → ${resident} accounted bytes; scroll/restore pixels and disposal passed`);
    await page.close();
  }
} finally { await browser.close(); }
