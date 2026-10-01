// Browser retained-heap evidence under dense metadata output. Requires the release demo.
// GC removes allocation noise; values are JS heap, not GPU memory or process RSS.
import assert from "node:assert/strict";
const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
try {
  const page = await browser.newPage({ viewport: { width: 1100, height: 720 } });
  await page.goto(`${process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173"}/samples/browser-terminal/web/index.html`);
  await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 80);
  const cdp = await page.context().newCDPSession(page);
  const samples = [];
  async function sample(stage) {
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    await cdp.send("HeapProfiler.collectGarbage");
    const { usedSize } = await cdp.send("Runtime.getHeapUsage");
    const state = await page.evaluate(() => ({
      columns: kineticaTerminalDemo.columns(), rows: kineticaTerminalDemo.rows(),
      history: kineticaTerminalDemo.history(), historyBytes: kineticaTerminalDemo.historyStorageBytes(),
      historyByteLimit: kineticaTerminalDemo.historyByteLimit(),
    }));
    assert(state.historyBytes <= state.historyByteLimit);
    samples.push({ stage, jsHeapBytesAfterGc: usedSize, ...state });
  }
  await page.evaluate(() => kineticaTerminalDemo.reset());
  await sample("empty");
  for (let phase = 0; phase < 3; phase++) {
    await page.evaluate(async phase => {
      const api = kineticaTerminalDemo;
      for (let row = 0; row < 1200; row++) {
        let text = "";
        for (let x = 0; x < api.columns(); x++) {
          const uri = `https://example.test/${phase}/${row}/${x}/` + "x".repeat(160);
          text += `\u001b]8;;${uri}\u001b\\\u001b[58;2;1;2;3ma${"́".repeat(32)}`;
        }
        api.write(text + "\u001b]8;;\u001b\\\r\n");
        if (row % 32 === 0) await new Promise(requestAnimationFrame);
      }
    }, phase);
    await sample(`saturated-${phase + 1}`);
    assert(samples.at(-1).historyBytes > samples.at(-1).historyByteLimit * 0.9);
  }
  const initial = samples[1].jsHeapBytesAfterGc;
  const final = samples.at(-1).jsHeapBytesAfterGc;
  assert(final <= initial + Math.max(8 * 1024 * 1024, initial * 0.2), "retained heap did not plateau");
  await page.evaluate(() => kineticaTerminalDemo.reset());
  await sample("reset");
  assert.equal(samples.at(-1).historyBytes, 0);
  assert(samples.at(-1).jsHeapBytesAfterGc < initial / 2, "reset retained most of the history heap");
  console.log(JSON.stringify({ scope: "Chromium JS heap after GC; GPU/RSS excluded", samples }, null, 2));
} finally { await browser.close(); }
