import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";

const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const base = process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173";
const results = [];
// Defaults and full-page size: pinned Ghostty 1.4.0 Config.zig (non-Darwin/Mac
// bindings) and Surface.zig scroll_page_up/down. See third-party/ghostty.json.
try {
  for (const platform of ["MacIntel", "Linux x86_64"]) for (const renderer of ["software", "gpu"]) {
    const page = await browser.newPage({ viewport: { width: 850, height: 500 } });
    const errors = [];
    page.on("pageerror", error => errors.push(String(error)));
    try {
      await page.addInitScript(platform => Object.defineProperty(navigator, "platform", { get: () => platform }), platform);
      await page.goto(`${base}/samples/browser-terminal/web/index.html?renderer=${renderer}&fonts=system`);
      await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 40);
      assert.equal(await page.locator("[data-terminal]").getAttribute("data-terminal-renderer"), renderer === "gpu" ? "webgl2" : "canvas2d");
      const input = page.getByRole("textbox", { name: "Terminal input" });
      const write = text => page.evaluate(text => kineticaTerminalDemo.write(text), text);
      const offset = () => page.evaluate(() => kineticaTerminalDemo.scrollOffset());
      const mac = platform === "MacIntel";
      const scrollMods = mac ? { metaKey: true } : { shiftKey: true };
      const promptMods = mac ? { metaKey: true } : { ctrlKey: true, shiftKey: true };
      // Synthetic key events isolate the bound key's press/repeat/release from
      // separate modifier-key events that Kitty may legitimately report to the PTY.
      const key = (key, modifiers, extra = {}) => input.evaluate((element, options) => {
        const prevented = [];
        for (const [type, repeat] of [["keydown", false], ["keydown", true], ["keyup", false]]) {
          const event = new KeyboardEvent(type, { bubbles: true, cancelable: true, ...options, repeat });
          element.dispatchEvent(event); prevented.push(event.defaultPrevented);
        }
        return prevented;
      }, { key, code: key, ...modifiers, ...extra });
      const consumed = async (name, mods = scrollMods, extra = {}) =>
        assert.deepEqual(await key(name, mods, extra), [true, true, false], `${platform}/${renderer}: ${name}`);
      const drain = async () => {
        await page.waitForFunction(() => kineticaTerminalDemo.transportState().pendingInputBytes === 0);
        return page.evaluate(() => navigationBytes.splice(0));
      };
      await page.evaluate(() => {
        globalThis.navigationBytes = [];
        kineticaTerminalDemo.connectTransport({
          write(bytes) { navigationBytes.push(...bytes); return bytes.length; }, resize() {}, consumed() {},
          rejected() { throw Error("Navigation input rejected"); }, failed(message) { throw Error(message); },
        });
      });
      const rows = await page.evaluate(() => kineticaTerminalDemo.rows());
      await write("\x1bc\x1b[?25l" + Array.from({ length: rows * 3 }, (_, i) =>
        `\x1b]133;A\x07command-${i}\r\n\x1b]133;C\x07output-${i}\r\n`).join(""));
      const history = await page.evaluate(() => kineticaTerminalDemo.history());
      assert.ok(history > rows * 2);
      await input.focus();
      // Real browser keyboard dispatch, including modifier presses, also stays local.
      await page.keyboard.press(`${mac ? "Meta" : "Shift"}+Home`);
      assert.equal(await offset(), history);
      await page.waitForFunction(() => document.querySelector('[role="log"]').textContent.startsWith("command-0\noutput-0"));
      const cdp = await page.context().newCDPSession(page);
      await cdp.send("Accessibility.enable");
      assert.ok((await cdp.send("Accessibility.getFullAXTree")).nodes.some(node =>
        !node.ignored && node.role?.value === "StaticText" && node.name?.value.startsWith("command-0\noutput-0")));
      await page.keyboard.press(`${mac ? "Meta" : "Shift"}+End`);
      assert.equal(await offset(), 0);
      assert.deepEqual(await drain(), []);
      assert.ok((await input.getAttribute("aria-keyshortcuts")).includes(mac ? "Meta+ArrowUp" : "Control+Shift+ArrowUp"));

      await write("\x1b[>31u"); // Includes repeat/release reports: local navigation must emit none.
      await consumed("PageUp"); assert.equal(await offset(), rows * 2);
      await consumed("PageDown"); assert.equal(await offset(), 0);
      await consumed("Home"); assert.equal(await offset(), history);
      await consumed("Home"); assert.equal(await offset(), history); // Boundary still consumed.
      await consumed("End"); assert.equal(await offset(), 0);
      const current = history;
      await consumed("ArrowUp", promptMods);
      const target = Math.floor((current - 1) / 2) * 2 - 2; // Press and repeat visit two prompts.
      assert.equal(await offset(), history - target);
      await page.waitForFunction(index => document.querySelector('[role="log"]').textContent.startsWith(`command-${index}\n`), target / 2);
      await consumed("ArrowDown", promptMods); assert.equal(await offset(), 0);
      if (mac) {
        await consumed("ArrowUp", { metaKey: true, shiftKey: true }); assert.equal(await offset(), history - target);
        await consumed("ArrowDown", { metaKey: true, shiftKey: true }); assert.equal(await offset(), 0);
      }
      assert.equal(await input.evaluate(element => document.activeElement === element), true);
      assert.deepEqual(await drain(), []);

      // Ghostty's macOS Cmd+J reveals an existing selection without changing it.
      // Select through the real pointer path; no test-only selection API is involved.
      await consumed("Home");
      const point = await page.evaluate(() => {
        const rect = document.querySelector("canvas").getBoundingClientRect();
        const cell = kineticaTerminalDemo.cellMetrics();
        return { x: rect.x + cell.width * 2, y: rect.y + cell.height * 1.5 };
      });
      await page.mouse.click(point.x, point.y, { clickCount: 3 });
      const copy = () => page.evaluate(() => {
        const data = new DataTransfer();
        document.querySelector("[data-terminal]").dispatchEvent(new ClipboardEvent("copy", {
          clipboardData: data, bubbles: true, cancelable: true,
        }));
        return data.getData("text/plain");
      });
      const selected = await copy();
      assert.equal(selected, "output-0");
      await consumed("End");
      await write("later output\r\n");
      await drain(); // Pointer input itself can produce negotiated terminal reports.
      await key("j", { metaKey: true }, { code: "KeyJ" });
      if (mac) {
        assert.equal(await offset(), (await page.evaluate(() => kineticaTerminalDemo.history())) - 1);
        assert.equal(await copy(), selected);
        assert.deepEqual(await drain(), []);
        await page.waitForFunction(() => document.querySelector('[role="log"]').textContent.startsWith("output-0\n"));
        await consumed("End");
        await key("j", { metaKey: true, shiftKey: true }, { code: "KeyJ" });
        assert.equal(await offset(), 0);
        assert.ok((await drain()).length > 0, "Extra modifiers remain application input");
      } else {
        assert.equal(await offset(), 0);
        assert.ok((await drain()).length > 0, "Cmd+J is a macOS default only");
      }
      // Forwarded input clears selection. Cmd+J must now fall through to Kitty,
      // including release, instead of becoming an unconditional swallowed shortcut.
      await key("j", { metaKey: true }, { code: "KeyJ" });
      const unselected = Buffer.from(await drain()).toString();
      assert.ok(unselected.includes("\x1b[106;9") && unselected.includes(":3"));

      // Additional modifiers, the other platform's bindings, and keypad identity
      // remain available to terminal applications, including their release events.
      for (const [name, mods, extra] of [
        ["PageUp", { ...scrollMods, altKey: true }],
        ["PageUp", { ...scrollMods, ctrlKey: true }],
        ["Home", mac ? { shiftKey: true } : { metaKey: true }],
        ["ArrowUp", { ...promptMods, altKey: true }],
        ["ArrowUp", mac ? { ctrlKey: true, shiftKey: true } : { metaKey: true }],
        ["PageUp", scrollMods, { code: "Numpad9", location: 3 }],
      ]) {
        await key(name, mods, extra);
        assert.equal(await offset(), 0);
        const bytes = Buffer.from(await drain()).toString();
        assert.ok(bytes.length > 0 && bytes.includes(":2") && bytes.includes(":3"), `${name}: ${JSON.stringify(bytes)}`);
      }
      // KAM blocks application input, not local viewport controls.
      await page.evaluate(() => kineticaTerminalDemo.allowKeyboardLock(true));
      await write("\x1b[2h");
      await consumed("Home"); assert.equal(await offset(), history + 1);
      await consumed("End"); assert.equal(await offset(), 0);
      await write("\x1b[2l");
      // No local shortcut may interrupt IME composition.
      assert.deepEqual(await key("Home", scrollMods, { isComposing: true }), [false, false, false]);
      assert.equal(await offset(), 0);
      // Atomic presentation holds retain the same viewport; alternate screens have no history.
      await write("\x1b[?2026h");
      await consumed("Home"); await consumed("ArrowUp", promptMods);
      assert.equal(await offset(), 0);
      await write("\x1b[?2026l\x1b[?1049h");
      await consumed("Home"); await consumed("ArrowUp", promptMods);
      assert.equal(await offset(), 0);
      await write("\x1b[?1049l");
      // A shell without semantic marks still consumes the local prompt shortcut.
      await write("\x1bc\x1b[>31u" + "plain output\r\n".repeat(rows * 3));
      await consumed("ArrowUp", promptMods); await consumed("ArrowDown", promptMods);
      assert.equal(await offset(), 0);
      assert.deepEqual(await drain(), []);
      assert.deepEqual(errors, []);
      results.push({ platform, renderer, passed: true });
    } finally { await page.close(); }
  }
  await mkdir("build/reports", { recursive: true });
  await writeFile("build/reports/terminal-browser-navigation.json", JSON.stringify(results, null, 2) + "\n");
  console.log(JSON.stringify(results));
} finally { await browser.close(); }
