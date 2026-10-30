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
        globalThis.copyCompletions = [];
        Object.defineProperty(navigator, "clipboard", { value: {
          write() { return new Promise((resolve, reject) => copyCompletions.push({ resolve, reject })); },
        } });
      }, platform);
      await page.goto(`${base}/samples/browser-terminal/web/index.html?renderer=${renderer}&fonts=system`);
      await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 40);
      const input = page.getByRole("textbox", { name: "Terminal input" });
      await input.focus();
      await page.evaluate(() => {
        globalThis.inputBytes = [];
        kineticaTerminalDemo.copyOnSelect("NONE");
        kineticaTerminalDemo.connectTransport({ write(bytes) { inputBytes.push(...bytes); return bytes.length; },
          resize() {}, consumed() {}, rejected() { throw Error("Input rejected"); }, failed(message) { throw Error(message); } });
      });
      const write = text => page.evaluate(text => kineticaTerminalDemo.write(text), text);
      const select = () => page.keyboard.press(platform === "MacIntel" ? "Meta+a" : "Control+Shift+a");
      const copy = () => page.keyboard.press(platform === "MacIntel" ? "Meta+c" : "Control+Shift+c");
      // This is an explicit DOM Copy, so with clear-on-copy enabled it deliberately
      // consumes the selection. No private view state is exposed to the verifier.
      const copied = () => page.evaluate(() => {
        const data = new DataTransfer();
        document.querySelector("[data-terminal]").dispatchEvent(new ClipboardEvent("copy", { clipboardData: data, bubbles: true, cancelable: true }));
        return data.getData("text/plain");
      });
      const key = (key, code = key, type = "keydown", mods = {}) => input.evaluate((element, event) => {
        element.dispatchEvent(new KeyboardEvent(event.type, { bubbles: true, cancelable: true, ...event }));
      }, { key, code, type, ...mods });
      const text = value => input.evaluate((element, value) => {
        element.value = value;
        element.dispatchEvent(new InputEvent("input", { bubbles: true, inputType: "insertText", data: value }));
      }, value);
      const composition = (type, data = "") => input.evaluate((element, { type, data }) => {
        element.dispatchEvent(new CompositionEvent(type, { bubbles: true, data }));
      }, { type, data });
      for (const mode of ["", "\x1b[>4;2m", "\x1b[>31u"]) {
        await write("\x1bcselected output" + mode);
        await page.evaluate(() => kineticaTerminalDemo.selectionClearOnTyping(false));
        await select(); await key("Enter"); await key("Enter", "Enter", "keyup");
        assert.equal(await copied(), "selected output");
        await text("typed"); assert.equal(await copied(), "selected output");
        await composition("compositionstart"); await composition("compositionupdate", "日");
        assert.equal(await copied(), "selected output");
        await composition("compositionend", "日本"); assert.equal(await copied(), "selected output");
        await key("Escape"); assert.equal(await copied(), "", "Escape always clears when it sends data");
        await key("Escape", "Escape", "keyup");
        await page.evaluate(() => kineticaTerminalDemo.selectionClearOnTyping(true));
        await select(); await key("Enter"); assert.equal(await copied(), ""); await key("Enter", "Enter", "keyup");
        await select(); await text(""); assert.equal(await copied(), "selected output");
        await composition("compositionstart"); assert.equal(await copied(), "", "Starting IME clears before any text is committed");
        await composition("compositionend");
      }
      // Only encoded releases affect selection. Pure modifiers never do; lock keys
      // are ordinary keys in Ghostty's selection policy when Kitty encodes them.
      await key("ArrowUp"); await select(); await key("ArrowUp", "ArrowUp", "keyup");
      assert.equal(await copied(), "");
      await select(); await key("Shift", "ShiftLeft", "keydown", { shiftKey: true });
      await key("Shift", "ShiftLeft", "keyup"); assert.equal(await copied(), "selected output");
      await key("CapsLock"); assert.equal(await copied(), ""); await key("CapsLock", "CapsLock", "keyup");
      await select();
      await page.waitForFunction(() => kineticaTerminalDemo.transportState().pendingInputBytes === 0);
      await page.evaluate(() => { kineticaTerminalDemo.allowKeyboardLock(true); inputBytes.length = 0; });
      await write("\x1b[2h"); await key("Escape"); await text("blocked");
      assert.equal(await copied(), "selected output");
      await page.waitForFunction(() => kineticaTerminalDemo.transportState().pendingInputBytes === 0);
      assert.deepEqual(await page.evaluate(() => inputBytes), []);
      await write("\x1b[2l"); await text("typed"); assert.equal(await copied(), "");

      await select(); await copy(); assert.equal(await copied(), "selected output", "Copy preserves selection by default");
      await page.evaluate(() => copyCompletions.shift().resolve());
      await page.evaluate(() => kineticaTerminalDemo.selectionClearOnCopy(true));
      await copy(); assert.equal(await copied(), "", "Clear immediately after dispatch, before the clipboard promise resolves");
      await select();
      await page.evaluate(() => copyCompletions.shift().reject(new DOMException("Denied", "NotAllowedError")));
      assert.equal(await copied(), "selected output", "An old completion cannot clear a newly created selection");
      assert.equal(await copied(), "", "DOM Copy also honors clear-on-copy");
      await page.evaluate(() => kineticaTerminalDemo.copyOnSelect("CLIPBOARD"));
      await select(); assert.equal(await copied(), "selected output", "Automatic copy preserves selection even with clear-on-copy enabled");
      await page.evaluate(() => copyCompletions.shift().resolve());
      assert.equal(await page.evaluate(() => copyCompletions.length), 0);
      assert.deepEqual(errors, []);
      console.log(`${platform} ${renderer}: selection retention, IME, encoded releases, modifiers, keyboard lock and asynchronous copy passed`);
    } finally { await page.close(); }
  }
} finally { await browser.close(); }
