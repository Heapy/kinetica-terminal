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
        globalThis.copies = []; globalThis.pendingCopies = 0;
        Object.defineProperty(navigator, "clipboard", { value: {
          async write(items) {
            pendingCopies++;
            try {
              if (items.length !== 1) throw Error("Expected one clipboard item");
              copies.push(Object.fromEntries(await Promise.all(items[0].types.map(async type =>
                [type, await (await items[0].getType(type)).text()]))));
            } finally { pendingCopies--; }
          },
          writeText(text) { throw Error(`Unexpected primary fallback: ${text}`); },
        } });
      }, platform);
      await page.goto(`${base}/samples/browser-terminal/web/index.html?renderer=${renderer}&fonts=system`);
      await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 40);
      const input = page.getByRole("textbox", { name: "Terminal input" });
      const shortcut = key => page.keyboard.press(`${platform === "MacIntel" ? "Meta" : "Control+Shift"}+${key}`);
      const drain = async () => {
        await page.waitForFunction(() => pendingCopies === 0);
        return page.evaluate(() => copies.splice(0));
      };
      const write = text => page.evaluate(text => kineticaTerminalDemo.write(text), text);
      const policy = value => page.evaluate(value => kineticaTerminalDemo.copyOnSelect(value), value);
      await write("\x1bcalpha beta"); await input.focus();
      await shortcut("a"); assert.deepEqual(await drain(), [], "Platform defaults do not redirect primary to the standard clipboard");
      await page.evaluate(() => kineticaTerminalDemo.copyFormat("VT"));
      for (const value of ["NONE", "PRIMARY", "CLIPBOARD", "BOTH"]) {
        await policy(value); await shortcut("a");
        const copies = await drain();
        assert.equal(copies.length, value === "CLIPBOARD" || value === "BOTH" ? 1 : 0);
        if (copies.length) {
          assert.deepEqual(Object.keys(copies[0]).sort(), ["text/html", "text/plain"]);
          assert.equal(copies[0]["text/plain"], "alpha beta");
        }
      }
      await shortcut("c");
      const explicit = await drain(); assert.equal(explicit.length, 1);
      assert.deepEqual(Object.keys(explicit[0]), ["text/plain"]);
      assert.ok(explicit[0]["text/plain"].startsWith("\x1b]10;rgb:"));
      await policy("CLIPBOARD");
      const geometry = await page.evaluate(() => {
        const rect = document.querySelector("canvas").getBoundingClientRect();
        const metrics = kineticaTerminalDemo.cellMetrics();
        return { x: rect.x, y: rect.y, ...metrics };
      });
      const point = (x, y = 0) => ({ x: geometry.x + geometry.width * x, y: geometry.y + geometry.height * (y + 0.5) });
      const move = async (x, y = 0) => { const p = point(x, y); await page.mouse.move(p.x, p.y); };
      await move(0.1); await page.mouse.down(); await move(4.8);
      assert.deepEqual(await drain(), [], "Pointer motion never writes the clipboard");
      await page.mouse.up();
      assert.equal((await drain())[0]["text/plain"], "alpha");
      await move(7.5); await page.mouse.down({ clickCount: 2 });
      assert.deepEqual(await drain(), [], "Double click waits for release too");
      await page.mouse.up({ clickCount: 2 });
      assert.equal((await drain())[0]["text/plain"], "beta");
      const contextMenu = async (x, modified = false) => page.evaluate(({ p, modified, mac }) => {
        const event = new MouseEvent("contextmenu", { bubbles: true, cancelable: true, button: 2,
          clientX: p.x, clientY: p.y, metaKey: modified && mac, ctrlKey: modified && !mac });
        document.querySelector("[data-terminal]").dispatchEvent(event);
        return event.defaultPrevented;
      }, { p: point(x), modified, mac: platform === "MacIntel" });
      assert.equal(await contextMenu(7.5), false); assert.deepEqual(await drain(), []);
      assert.equal(await contextMenu(2.5), false); assert.equal((await drain())[0]["text/plain"], "alpha");
      assert.equal(await contextMenu(35), false); assert.deepEqual(await drain(), [], "Unwritten cells preserve the previous selection");
      await move(0.1); await page.mouse.down(); await move(4.8);
      await page.evaluate(() => window.dispatchEvent(new Event("blur")));
      await page.mouse.up(); assert.deepEqual(await drain(), [], "Cancelled gestures never commit");
      await input.focus();
      await write("\x1b[?1000h\x1b[?1006h");
      await move(0.1); await page.mouse.down(); await move(4.8); await page.mouse.up();
      assert.deepEqual(await drain(), [], "Application-owned mouse gestures do not auto-copy");
      assert.equal(await contextMenu(7.5), true); assert.deepEqual(await drain(), []);
      await write("\x1b[?1000l\x1b[?2026h\x1b[HCHANGED");
      await shortcut("a"); assert.equal((await drain())[0]["text/plain"], "alpha beta", "Select All copies the retained frame");
      await write("\x1b[?2026l");
      await write("\x1bchttps://example.org/path");
      await contextMenu(10.5); assert.notEqual((await drain())[0]["text/plain"], "https://example.org/path");
      await move(35); await page.mouse.click(point(35).x, point(35).y); await drain();
      await contextMenu(10.5, true); assert.equal((await drain())[0]["text/plain"], "https://example.org/path");
      await write("\x1bc\x1b]8;;https://example.org\x07hello world\x1b]8;;\x07");
      await contextMenu(1.5, true); assert.equal((await drain())[0]["text/plain"], "e");
      assert.deepEqual(errors, []);
      console.log(`${platform} ${renderer}: copy-on-select policies, mixed content, release, context menus, cancellation and holds passed`);
    } finally { await page.close(); }
  }
} finally { await browser.close(); }
