import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";

const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const base = process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173";
const results = [];
try {
  for (const renderer of ["software", "gpu"]) {
    const page = await browser.newPage({ viewport: { width: 850, height: 500 } });
    const errors = [];
    page.on("pageerror", error => errors.push(String(error)));
    try {
      await page.goto(`${base}/samples/browser-terminal/web/index.html?renderer=${renderer}&fonts=system`);
      await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 40);
      const output = page.locator('[role="log"]');
      const input = page.getByRole("textbox", { name: "Terminal input" });
      const write = value => page.evaluate(value => kineticaTerminalDemo.write(value), value);
      await write("\x1bc\x1b[?25lstable 🌍 text\r\nsecond line");
      await page.waitForFunction(() => document.querySelector('[role="log"]').textContent.startsWith("stable 🌍 text\nsecond line"));
      const cdp = await page.context().newCDPSession(page);
      await cdp.send("Accessibility.enable");
      const ax = (await cdp.send("Accessibility.getFullAXTree")).nodes;
      assert.ok(ax.some(node => !node.ignored && node.role?.value === "log" && node.name?.value === "Terminal output"));
      assert.ok(ax.some(node => !node.ignored && node.role?.value === "StaticText" && node.name?.value.includes("stable 🌍 text")));
      await page.locator("[data-terminal]").focus();
      assert.equal(await input.evaluate(element => document.activeElement === element), true);
      await page.evaluate(() => {
        const output = document.querySelector('[role="log"]');
        globalThis.accessibilityProbe = { output, node: output.firstChild, changes: 0, range: document.createRange() };
        accessibilityProbe.range.setStart(output.firstChild, 0);
        accessibilityProbe.range.setEnd(output.firstChild, "stable 🌍 text".length);
        accessibilityProbe.observer = new MutationObserver(records => { accessibilityProbe.changes += records.length; });
        accessibilityProbe.observer.observe(output, { childList: true, characterData: true, subtree: true });
      });
      // Several real paints, including cursor blinking, must not rewrite identical text.
      await write("\x1b[?12;25h\x1b[0m");
      await page.waitForTimeout(1200);
      assert.deepEqual(await page.evaluate(() => ({
        changes: accessibilityProbe.changes,
        sameNode: accessibilityProbe.output.firstChild === accessibilityProbe.node,
        reading: accessibilityProbe.range.toString(),
      })), { changes: 0, sameNode: true, reading: "stable 🌍 text" }, renderer);
      // A changed later row keeps an existing reading range and text node alive.
      await write("\x1b[?25l\x1b[2;1Hupdated longer row");
      await page.waitForFunction(() => document.querySelector('[role="log"]').textContent.includes("updated longer row"));
      assert.deepEqual(await page.evaluate(() => [accessibilityProbe.output.firstChild === accessibilityProbe.node, accessibilityProbe.range.toString()]), [true, "stable 🌍 text"]);
      // Reading ranges after an edited span shift with its UTF-16 length delta.
      await page.evaluate(() => {
        const node = accessibilityProbe.node, start = node.data.indexOf("updated longer row");
        accessibilityProbe.range.setStart(node, start); accessibilityProbe.range.setEnd(node, start + "updated longer row".length);
      });
      await write("\x1b[1;1H\x1b[2Kshort");
      await page.waitForFunction(() => document.querySelector('[role="log"]').textContent.startsWith("short\n"));
      assert.equal(await page.evaluate(() => accessibilityProbe.range.toString()), "updated longer row");
      // Synchronized output must not expose text that has not been presented yet.
      await write("\x1b[?2026h\x1b[1;1HHIDDEN");
      await page.waitForTimeout(350);
      assert.ok(!(await output.textContent()).includes("HIDDEN"));
      await write("\x1b[?2026l");
      await page.waitForFunction(() => document.querySelector('[role="log"]').textContent.includes("HIDDEN"));
      const after = (await cdp.send("Accessibility.getFullAXTree")).nodes;
      assert.ok(after.some(node => !node.ignored && node.role?.value === "StaticText" && node.name?.value.includes("HIDDEN")));
      // Selection notifications are separate from the stable reading text node.
      await write("\x1bc\x1b[?25lalpha 🌍 text\r\nsecond line");
      await page.waitForFunction(() => document.querySelector('[role="log"]').textContent.startsWith("alpha 🌍 text"));
      const selectionStatus = page.getByRole("status", { name: "Terminal selection" });
      await page.evaluate(() => {
        const status = document.querySelector('[role="status"][aria-label="Terminal selection"]');
        accessibilityProbe.selectionChanges = 0;
        accessibilityProbe.selectionObserver = new MutationObserver(records => { accessibilityProbe.selectionChanges += records.length; });
        if (status) accessibilityProbe.selectionObserver.observe(status, { childList: true, characterData: true, subtree: true });
        accessibilityProbe.range.setStart(accessibilityProbe.node, 0);
        accessibilityProbe.range.setEnd(accessibilityProbe.node, 5);
      });
      const geometry = await page.evaluate(() => {
        const rect = document.querySelector("canvas").getBoundingClientRect();
        const { width, height } = kineticaTerminalDemo.cellMetrics();
        return { x: rect.x, y: rect.y, width, height };
      });
      const point = (column, row = 0) => [geometry.x + (column + 0.1) * geometry.width, geometry.y + (row + 0.5) * geometry.height];
      await page.mouse.move(...point(0)); await page.mouse.down();
      await page.mouse.move(...point(2)); await page.mouse.move(...point(5)); await page.mouse.up();
      await page.waitForTimeout(250);
      assert.equal(await page.evaluate(() => document.querySelector('[role="status"][aria-label="Terminal selection"]')?.textContent ?? ""),
        "Selected: alpha", "Terminal selection must produce an accessible announcement");
      assert.equal(await selectionStatus.getAttribute("aria-live"), "polite");
      assert.equal(await selectionStatus.getAttribute("aria-atomic"), "true");
      assert.equal(await page.evaluate(() => accessibilityProbe.selectionChanges), 1, "Coalesce rapid drag changes");
      assert.equal(await page.evaluate(() => accessibilityProbe.range.toString()), "alpha");
      assert.equal(await page.evaluate(() => window.getSelection().toString()), "", "Do not replace browser reading ranges with canvas selection");
      const selectionAx = (await cdp.send("Accessibility.getFullAXTree")).nodes;
      assert.ok(selectionAx.some(node => !node.ignored && node.role?.value === "status" && node.name?.value === "Terminal selection"));
      assert.ok(selectionAx.some(node => !node.ignored && node.role?.value === "StaticText" && node.name?.value === "Selected: alpha"));
      await write("\x1b[2;1Hunrelated row"); await page.waitForTimeout(350);
      assert.equal(await page.evaluate(() => accessibilityProbe.selectionChanges), 1, "Output and paints must not repeat selection announcements");
      await write("\x1b[3;1Halpha");
      await page.mouse.move(...point(0, 2)); await page.mouse.down();
      await page.mouse.move(...point(5, 2)); await page.mouse.up();
      await page.waitForTimeout(250);
      assert.equal(await selectionStatus.textContent(), "Selected: alpha");
      assert.equal(await page.evaluate(() => accessibilityProbe.selectionChanges), 2, "An equal string in a different selected range needs a new announcement");
      await page.keyboard.press("Escape");
      await page.waitForFunction(() => document.querySelector('[role="status"][aria-label="Terminal selection"]')?.textContent === "Selection cleared");
      await write("\x1bc\x1b[?25l" + "🌍".repeat(700));
      await page.keyboard.press(await page.evaluate(() => navigator.platform.startsWith("Mac") ? "Meta+a" : "Control+Shift+a"));
      await page.waitForFunction(() => document.querySelector('[role="status"][aria-label="Terminal selection"]')?.textContent.startsWith("Selected: "));
      const announcement = await selectionStatus.textContent();
      assert.ok(announcement.length <= 523 && announcement.endsWith("…"), "Bound large announcements");
      assert.equal(announcement.isWellFormed(), true, "Do not split supplementary Unicode characters");
      await page.evaluate(() => window.dispatchEvent(new Event("blur")));
      assert.equal(await selectionStatus.textContent(), "", "Background terminals must stop announcing selections");
      await page.evaluate(() => window.dispatchEvent(new Event("focus")));
      await page.waitForTimeout(150);
      assert.equal(await selectionStatus.textContent(), "", "Focus alone must not replay an old selection");
      // Queue a selection-clear notification, then dispose before its debounce fires.
      await page.keyboard.press("Escape");
      const selectionChangesBeforeDispose = await page.evaluate(() => accessibilityProbe.selectionChanges);
      // Removing a view with a pending update leaves no late DOM mutations or AX output.
      await write("\x1b[1;1HPENDING");
      await page.evaluate(() => {
        kineticaTerminalDemo.dispose();
        accessibilityProbe.changes = 0;
        accessibilityProbe.observer.takeRecords();
        kineticaTerminalDemo.write("AFTER DISPOSE");
      });
      await page.waitForTimeout(350);
      assert.equal(await page.evaluate(() => accessibilityProbe.changes), 0);
      assert.equal(await output.count(), 0);
      assert.equal(await page.evaluate(() => accessibilityProbe.selectionChanges), selectionChangesBeforeDispose);
      assert.equal(await selectionStatus.count(), 0);
      assert.ok(!(await cdp.send("Accessibility.getFullAXTree")).nodes.some(node => !node.ignored && node.role?.value === "log"));
      await page.evaluate(() => { accessibilityProbe.observer.disconnect(); accessibilityProbe.selectionObserver.disconnect(); });
      assert.deepEqual(errors, []);
      results.push({ renderer, passed: true, scope: "Chromium AX tree, DOM range stability, bounded/debounced selection announcements, focus, presentation hold and disposal; no physical screen-reader acceptance" });
    } finally { await page.close(); }
  }
  await mkdir("build/reports", { recursive: true });
  await writeFile("build/reports/terminal-browser-accessibility.json", JSON.stringify(results, null, 2) + "\n");
  console.log(JSON.stringify(results));
} finally { await browser.close(); }
