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
        window.addEventListener("pointerdown", event => { globalThis.lastMousePointer = event.pointerId; }, true);
        globalThis.mouseClipboard = { text: "safe", reads: 0, writes: [], pendingWrites: 0, delayed: false, denied: false, pending: [] };
        const state = mouseClipboard;
        const read = () => {
          state.reads++;
          if (state.denied) return Promise.reject(new DOMException("Denied", "NotAllowedError"));
          const text = state.text;
          if (state.delayed) return new Promise(resolve => state.pending.push(() => resolve(text)));
          return Promise.resolve(text);
        };
        Object.defineProperty(navigator, "clipboard", { value: {
          async read() { const text = await read(); return [{ types: ["text/plain"], async getType() { return new Blob([text], { type: "text/plain" }); } }]; },
          readText: read,
          async write(items) {
            state.pendingWrites++;
            try { state.writes.push(Object.fromEntries(await Promise.all(items[0].types.map(async type => [type, await (await items[0].getType(type)).text()])))); }
            finally { state.pendingWrites--; }
          },
        } });
      }, platform);
      await page.goto(`${base}/samples/browser-terminal/web/index.html?renderer=${renderer}&fonts=system`);
      await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 40);
      const input = page.getByRole("textbox", { name: "Terminal input" });
      const dialog = page.getByRole("dialog", { name: "Paste into Terminal?" });
      const write = text => page.evaluate(text => kineticaTerminalDemo.write(text), text);
      const select = async () => { await input.focus(); await page.keyboard.press(`${platform === "MacIntel" ? "Meta" : "Control+Shift"}+a`); await drain(); };
      const setting = (name, value) => page.evaluate(({ name, value }) => kineticaTerminalDemo[name](value), { name, value });
      const clipboard = values => page.evaluate(values => Object.assign(mouseClipboard, values), values);
      const reads = () => page.evaluate(() => mouseClipboard.reads);
      const drain = async () => {
        await page.waitForFunction(() => kineticaTerminalDemo.transportState().pendingInputBytes === 0 && mouseClipboard.pendingWrites === 0);
        return Buffer.from(await page.evaluate(() => mouseBytes.splice(0))).toString();
      };
      const expectOutput = async expected => {
        await page.waitForFunction(() => mouseBytes.length > 0);
        assert.equal(await drain(), expected);
      };
      const selection = () => page.evaluate(() => {
        const data = new DataTransfer();
        document.querySelector("[data-terminal]").dispatchEvent(new ClipboardEvent("copy", { clipboardData: data, bubbles: true, cancelable: true }));
        return data.getData("text/plain");
      });
      await page.evaluate(() => {
        globalThis.mouseBytes = [];
        kineticaTerminalDemo.connectTransport({ write(bytes) { mouseBytes.push(...bytes); return bytes.length; }, resize() {}, consumed() {},
          rejected() { throw Error("Input rejected"); }, failed(message) { throw Error(message); } });
      });
      const rect = await page.locator("canvas").first().boundingBox();
      const click = async button => { await page.mouse.click(rect.x + 20, rect.y + 12, { button }); };
      await write("\x1bcalpha beta"); await select();
      await click("middle"); assert.equal(await reads(), 0, "Default primary has no browser clipboard capability");
      await setting("rightClickAction", "IGNORE"); await click("right"); assert.equal(await selection(), "alpha beta");
      await setting("copyFormat", "VT"); await setting("rightClickAction", "COPY"); await click("right"); await drain();
      const copied = await page.evaluate(() => mouseClipboard.writes.pop());
      assert.deepEqual(Object.keys(copied).sort(), ["text/html", "text/plain"]); assert.equal(copied["text/plain"], "alpha beta");
      await setting("copyFormat", "PLAIN"); assert.equal(await selection(), "");
      await click("right"); assert.equal(await reads(), 0, "Copy without selection never pastes");
      await setting("rightClickAction", "COPY_OR_PASTE"); await select(); await click("right"); await drain(); assert.equal(await selection(), "");
      await click("right"); await expectOutput("safe");
      await setting("rightClickAction", "PASTE"); await select(); await clipboard({ text: "unsafe\n" }); await click("right");
      await dialog.waitFor(); assert.equal(await selection(), "", "Right paste clears before permission/confirmation");
      await dialog.getByRole("button", { name: "Cancel" }).click(); assert.equal(await drain(), "");
      await select(); await clipboard({ denied: true }); await click("right"); assert.equal(await selection(), ""); assert.equal(await drain(), "");
      await clipboard({ denied: false }); await select(); await setting("middleClickAction", "CLIPBOARD_PASTE"); await click("middle");
      await dialog.waitFor(); assert.equal(await selection(), "alpha beta"); await write("\x1b[?2004h");
      await dialog.getByRole("button", { name: "Paste", exact: true }).click();
      assert.equal(await drain(), "\x1b[200~unsafe\n\x1b[201~"); assert.equal(await selection(), "alpha beta");
      await write("\x1b[?2004l"); await setting("middleClickAction", "IGNORE"); const ignored = await reads();
      await click("middle"); assert.equal(await reads(), ignored);
      await setting("middleClickAction", "CLIPBOARD_PASTE"); await clipboard({ text: "safe" });
      await write("\x1b[?1003h\x1b[?1006h"); const beforeReports = await reads();
      await click("right"); await click("middle"); assert.equal(await reads(), beforeReports);
      assert.match(await drain(), /\x1b\[<2;.*M/);
      await page.keyboard.down("Shift"); await click("right"); await page.keyboard.up("Shift"); await expectOutput("safe");
      await write("\x1b[?1003l"); await setting("rightClickAction", "IGNORE");
      await page.mouse.down({ button: "right" }); await write("\x1b[?1003h");
      await page.mouse.move(rect.x + 35, rect.y + 12); await page.mouse.up({ button: "right" });
      assert.equal(await drain(), "", "Local drag/release stays local if application enables reporting");
      await write("\x1b[?1003l\x1b[?5522h"); await click("middle");
      await page.waitForFunction(() => mouseBytes.length > 0);
      assert.match(await drain(), /\x1b\]5522;.*pw=/, "Trusted middle click emits a paste event");
      await page.mouse.down({ button: "right" });
      await page.evaluate(({ x, y }) => document.querySelector("[data-terminal]").dispatchEvent(new PointerEvent("pointermove", {
        bubbles: true, pointerId: lastMousePointer, pointerType: "mouse", buttons: 6, clientX: x + 20, clientY: y + 12,
      })), rect);
      await page.mouse.up({ button: "right" });
      await expectOutput("safe"); // Synthetic events cannot grant mode-5522 clipboard access.
      await write("\x1b[?5522l"); await clipboard({ delayed: true, text: "old" }); await click("middle");
      await clipboard({ text: "new" }); await click("middle");
      await page.evaluate(() => mouseClipboard.pending.splice(0).forEach(resolve => resolve()));
      await page.waitForFunction(() => mouseBytes.length > 0); assert.equal(await drain(), "new");
      await clipboard({ text: "detached" }); await click("middle");
      await page.evaluate(() => {
        const root = document.querySelector("[data-terminal]"); const parent = root.parentNode;
        root.remove(); parent.append(root);
      });
      await page.evaluate(() => mouseClipboard.pending.splice(0).forEach(resolve => resolve()));
      await clipboard({ delayed: false, text: "fallback" }); await page.evaluate(() => { navigator.clipboard.read = undefined; });
      await click("middle"); await expectOutput("fallback");
      await clipboard({ delayed: true }); await click("middle");
      await page.evaluate(() => kineticaTerminalDemo.dispose());
      await page.evaluate(() => mouseClipboard.pending.splice(0).forEach(resolve => resolve()));
      assert.equal(await drain(), ""); assert.deepEqual(errors, []);
      console.log(`${platform} ${renderer}: right/middle clipboard actions, reporting, safe paste and cancellation passed`);
    } finally { await page.close(); }
  }
} finally { await browser.close(); }
