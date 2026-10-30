import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";

const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const base = process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173";
const results = [];
try {
  for (const platform of ["MacIntel", "Linux x86_64"]) for (const renderer of ["software", "gpu"]) {
    const page = await browser.newPage({ viewport: { width: 850, height: 500 } });
    const errors = [];
    page.on("pageerror", error => errors.push(String(error)));
    try {
      await page.addInitScript(platform => {
        Object.defineProperty(navigator, "platform", { get: () => platform });
        globalThis.editCopies = [];
        // Keep clipboard writes entirely inside this isolated test page.
        Object.defineProperty(navigator, "clipboard", { value: { writeText(text) { editCopies.push(text); return Promise.resolve(); } } });
      }, platform);
      await page.goto(`${base}/samples/browser-terminal/web/index.html?renderer=${renderer}&fonts=system`);
      await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 40);
      assert.equal(await page.locator("[data-terminal]").getAttribute("data-terminal-renderer"), renderer === "gpu" ? "webgl2" : "canvas2d");
      const input = page.getByRole("textbox", { name: "Terminal input" });
      const mac = platform === "MacIntel";
      const modifiers = mac ? { metaKey: true } : { ctrlKey: true, shiftKey: true };
      const write = value => page.evaluate(value => kineticaTerminalDemo.write(value), value);
      const key = (key, mods = modifiers, extra = {}) => input.evaluate((element, options) => {
        return [["keydown", false], ["keydown", true], ["keyup", false]].map(([type, repeat]) => {
          const event = new KeyboardEvent(type, { bubbles: true, cancelable: true, ...options, repeat });
          element.dispatchEvent(event); return event.defaultPrevented;
        });
      }, { key, code: `Key${key.toUpperCase()}`, ...mods, ...extra });
      const selection = () => page.evaluate(() => {
        const data = new DataTransfer();
        document.querySelector("[data-terminal]").dispatchEvent(new ClipboardEvent("copy", { clipboardData: data, bubbles: true, cancelable: true }));
        return data.getData("text/plain");
      });
      const drain = async () => {
        await page.waitForFunction(() => kineticaTerminalDemo.transportState().pendingInputBytes === 0);
        return page.evaluate(() => editBytes.splice(0));
      };
      await page.evaluate(() => {
        globalThis.editBytes = [];
        kineticaTerminalDemo.connectTransport({
          write(bytes) { editBytes.push(...bytes); return bytes.length; }, resize() {}, consumed() {},
          rejected() { throw Error("Editing input rejected"); }, failed(message) { throw Error(message); },
        });
      });
      const rows = await page.evaluate(() => kineticaTerminalDemo.rows());
      const lines = ["first 🌍", ...Array.from({ length: rows * 2 }, (_, i) => `line-${i}`), "last 界"];
      await write("\x1bc\x1b[?25l" + lines.join("\r\n"));
      await input.focus();
      await page.keyboard.press(mac ? "Meta+a" : "Control+Shift+a");
      assert.equal(await selection(), lines.join("\n"), "Select all includes scrollback and Unicode");
      assert.deepEqual(await drain(), []);
      assert.equal(await input.evaluate(element => document.activeElement === element && element.value === ""), true);
      assert.equal(await page.evaluate(() => window.getSelection().toString()), "");

      await write("\x1b[>31u");
      assert.deepEqual(await key("a"), [true, true, false]);
      assert.deepEqual(await key("c"), [true, true, false]);
      assert.deepEqual(await page.evaluate(() => editCopies.splice(0)), [lines.join("\n"), lines.join("\n")]);
      assert.deepEqual(await drain(), [], "Local editing emits no Kitty press/repeat/release");
      // The browser owns paste acquisition; the key remains available for its native paste event.
      assert.deepEqual(await key("v"), [false, false, false]);
      assert.deepEqual(await drain(), []);
      for (const name of ["a", "c", "v"]) for (const mods of [
        { ...modifiers, altKey: true },
        mac ? { metaKey: true, ctrlKey: true } : { ...modifiers, metaKey: true },
        mac ? { metaKey: true, shiftKey: true } : { ctrlKey: true },
        mac ? { ctrlKey: true, shiftKey: true } : { metaKey: true },
      ]) {
        await key("a");
        assert.ok((await selection()).length > 0);
        await key(name, mods);
        const bytes = Buffer.from(await drain()).toString();
        assert.ok(bytes.length > 0 && bytes.includes(":2") && bytes.includes(":3"), `${platform}: forwarded ${name}`);
        assert.deepEqual(await page.evaluate(() => editCopies.splice(0)), []);
        assert.equal(await selection(), "", "Application input clears the old selection");
      }
      // Copy with no selection still reaches the application.
      await key("c");
      assert.ok((await drain()).length > 0);
      // KAM does not disable local selection/copy, and composition owns its keys.
      await page.evaluate(() => kineticaTerminalDemo.allowKeyboardLock(true));
      await write("\x1b[2h");
      await key("a"); assert.equal(await selection(), lines.join("\n"));
      await key("c"); assert.equal((await page.evaluate(() => editCopies.splice(0))).length, 2);
      assert.deepEqual(await drain(), []);
      await write("\x1b[2l");
      await key("x", {}); await drain();
      assert.deepEqual(await key("a", modifiers, { isComposing: true }), [false, false, false]);
      assert.equal(await selection(), ""); assert.deepEqual(await drain(), []);
      // A held frame selects only the presented text; alternate-screen selection excludes history.
      await write("\x1b[?2026h\x1b[HUNPRESENTED");
      await key("a"); assert.ok(!(await selection()).includes("UNPRESENTED"));
      await write("\x1b[?2026l\x1b[?1049h\x1b[Halternate");
      await key("a"); assert.equal(await selection(), "alternate");
      assert.deepEqual(await drain(), []);
      // Clipboard formatting is independent of selection geometry and changes with host policy.
      const rawCopy = "first  \n second\u00a0  \nthird";
      const trimmedCopy = "first\n second\u00a0\nthird";
      await write("\x1bcfirst  \r\n second\u00a0  \r\nthird");
      await key("a"); assert.equal(await selection(), trimmedCopy);
      await key("c"); assert.deepEqual(await page.evaluate(() => editCopies.splice(0)), [trimmedCopy, trimmedCopy]);
      await page.evaluate(() => kineticaTerminalDemo.copyTrimTrailingSpaces(false));
      assert.equal(await selection(), rawCopy);
      await key("c"); assert.deepEqual(await page.evaluate(() => editCopies.splice(0)), [rawCopy, rawCopy]);
      await write("\x1b[?2026h\x1b[HCHANGED");
      await page.evaluate(() => kineticaTerminalDemo.copyTrimTrailingSpaces(true));
      assert.equal(await selection(), trimmedCopy);
      await page.evaluate(() => kineticaTerminalDemo.copyTrimTrailingSpaces(false));
      assert.equal(await selection(), rawCopy, "Both clipboard forms retain the presented selection");
      assert.deepEqual(await drain(), []);
      await write("\x1b[?2026l\x1bc");
      const point = await page.evaluate(() => {
        const rect = document.querySelector("canvas").getBoundingClientRect();
        const { width, height } = kineticaTerminalDemo.cellMetrics();
        return { x: rect.x + width * 0.1, y: rect.y + height * 1.5, end: rect.x + width * 3.8 };
      });
      await page.mouse.move(point.x, point.y); await page.mouse.down();
      await page.mouse.move(point.end, point.y); await page.mouse.up();
      const blank = await page.evaluate(() => {
        const data = new DataTransfer(); data.setData("text/plain", "stale");
        const event = new ClipboardEvent("copy", { clipboardData: data, bubbles: true, cancelable: true });
        document.querySelector("[data-terminal]").dispatchEvent(event);
        return { text: data.getData("text/plain"), consumed: event.defaultPrevented };
      });
      assert.deepEqual(blank, { text: "", consumed: true }, "Selected empty cells replace the clipboard with empty text");
      await key("c"); assert.deepEqual(await page.evaluate(() => editCopies.splice(0)), ["", ""]);
      assert.deepEqual(await drain(), []);
      assert.deepEqual(errors, []);
      results.push({ platform, renderer, passed: true });
    } finally { await page.close(); }
  }
  await mkdir("build/reports", { recursive: true });
  await writeFile("build/reports/terminal-browser-editing.json", JSON.stringify(results, null, 2) + "\n");
  console.log(JSON.stringify(results));
} finally { await browser.close(); }
