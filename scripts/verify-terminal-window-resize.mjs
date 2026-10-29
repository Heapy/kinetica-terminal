import assert from "node:assert/strict";
const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE});
const errors = [];
try {
  for (const renderer of ["software", "gpu"]) {
    const page = await browser.newPage({viewport: {width: 1200, height: 850}});
    page.on("pageerror", error => errors.push(String(error)));
    await page.goto(`${process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173"}/samples/browser-terminal/web/index.html?renderer=${renderer}`);
    await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 0);
    const geometry = () => page.evaluate(() => {
      const box = document.querySelector("#app").getBoundingClientRect();
      return {width: box.width, height: box.height, rows: kineticaTerminalDemo.rows(), columns: kineticaTerminalDemo.columns()};
    });
    const settle = () => page.waitForTimeout(100);
    const initial = await geometry();
    await page.evaluate(() => kineticaTerminalDemo.write("\x1b[8;20;80t"));
    await settle(); assert.deepEqual(await geometry(), initial, "Disabled by default");
    await page.check("#window-resize");
    assert.deepEqual(await page.evaluate(() => {
      kineticaTerminalDemo.write("\x1b[8;20;80t");
      return [kineticaTerminalDemo.columns(), kineticaTerminalDemo.rows()];
    }), [initial.columns, initial.rows], "Layout must be deferred");
    await page.waitForFunction(() => kineticaTerminalDemo.columns() === 80 && kineticaTerminalDemo.rows() === 20);
    const sized = await geometry();
    await page.evaluate(() => kineticaTerminalDemo.write("\x1b[8;;90t"));
    await page.waitForFunction(() => kineticaTerminalDemo.columns() === 90);
    assert.equal((await geometry()).height, sized.height, "Zero keeps actual height");
    await page.evaluate(() => {
      for (let i = 0; i < 10000; i++) kineticaTerminalDemo.write("\x1b[8;;85t\x1b[8;22t");
    });
    await page.waitForFunction(() => kineticaTerminalDemo.columns() === 85 && kineticaTerminalDemo.rows() === 22);
    const before = await geometry();
    await page.evaluate(() => {
      kineticaTerminalDemo.write("\x1b[8;30;100t");
      kineticaTerminalDemo.windowResizeEnabled(false);
    });
    await settle(); assert.deepEqual(await geometry(), before, "Permission is rechecked");
    await page.evaluate(() => {
      kineticaTerminalDemo.windowResizeEnabled(true);
      kineticaTerminalDemo.write("\x1b[8;30;100t");
      globalThis.detachedTerminal = document.querySelector("[data-terminal]");
      detachedTerminal.remove();
    });
    await settle(); assert.equal((await geometry()).width, before.width, "Detached host cannot resize");
    await page.evaluate(() => document.querySelector("#app").appendChild(detachedTerminal));
    await settle();
    const reattached = await geometry();
    await page.evaluate(() => {
      kineticaTerminalDemo.write("\x1b[8;30;100t");
      const parent = detachedTerminal.parentElement;
      detachedTerminal.remove(); parent.appendChild(detachedTerminal);
    });
    await settle(); assert.deepEqual(await geometry(), reattached, "Immediate reattachment must cancel stale work");
    await page.evaluate(() => kineticaTerminalDemo.write("\x1b[8;65535;65535t"));
    await settle();
    const bounded = await geometry();
    assert.ok(bounded.width <= 1200 && bounded.height <= 850);
    assert.ok(bounded.columns * bounded.rows <= 1000000);
    await page.evaluate(() => { kineticaTerminalDemo.write("\x1b[8;15;60t"); kineticaTerminalDemo.dispose(); });
    await settle();
    assert.equal((await geometry()).width, bounded.width);
    assert.equal((await geometry()).height, bounded.height);
    await page.close();
  }
  assert.deepEqual(errors, []);
  console.log("Window resize: policy, deferred/coalesced axes, zero dimensions, bounds, detachment and disposal passed on Canvas/WebGL");
} finally { await browser.close(); }
