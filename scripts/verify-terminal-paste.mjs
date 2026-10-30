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
      await page.addInitScript(() => Object.defineProperty(navigator, "platform", { get: () => "MacIntel" }));
      await page.goto(`${base}/samples/browser-terminal/web/index.html?renderer=${renderer}&fonts=system`);
      await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 40);
      const input = page.getByRole("textbox", { name: "Terminal input" });
      const dialog = page.getByRole("dialog", { name: "Paste into Terminal?" });
      const paste = text => input.evaluate((element, text) => {
        const data = new DataTransfer(); data.setData("text/plain", text);
        const event = new ClipboardEvent("paste", { clipboardData: data, bubbles: true, cancelable: true });
        element.dispatchEvent(event); return event.defaultPrevented;
      }, text);
      const write = text => page.evaluate(text => kineticaTerminalDemo.write(text), text);
      const drain = async () => {
        await page.waitForFunction(() => kineticaTerminalDemo.transportState().pendingInputBytes === 0);
        return Buffer.from(await page.evaluate(() => pasteBytes.splice(0))).toString();
      };
      const selection = () => page.evaluate(() => {
        const data = new DataTransfer();
        document.querySelector("[data-terminal]").dispatchEvent(new ClipboardEvent("copy", { clipboardData: data, bubbles: true, cancelable: true }));
        return data.getData("text/plain");
      });
      await page.evaluate(() => {
        globalThis.pasteBytes = [];
        kineticaTerminalDemo.connectTransport({
          write(bytes) { pasteBytes.push(...bytes); return bytes.length; }, resize() {}, consumed() {},
          rejected() { throw Error("Paste rejected"); }, failed(message) { throw Error(message); },
        });
      });
      await write(Array.from({ length: 80 }, (_, i) => `line ${i}`).join("\r\n"));
      await input.focus(); await page.keyboard.press("Meta+Home"); await page.keyboard.press("Meta+a");
      const selected = await selection();
      const offset = await page.evaluate(() => kineticaTerminalDemo.scrollOffset());
      assert.ok(selected.length > 0 && offset > 0);
      const text = "echo '<b>界😀</b>'\r\necho second";
      assert.equal(await paste(text), true);
      assert.equal(await dialog.getByRole("textbox", { name: "Clipboard text" }).inputValue(), text.replaceAll("\r\n", "\n"));
      assert.equal(await dialog.locator("b").count(), 0, "Clipboard is never interpreted as markup");
      assert.equal(await dialog.getByRole("button", { name: "Cancel" }).evaluate(el => document.activeElement === el), true);
      assert.equal(await drain(), ""); assert.equal(await selection(), selected);
      await page.keyboard.press("Escape");
      assert.equal(await dialog.count(), 0); assert.equal(await drain(), ""); assert.equal(await selection(), selected);
      assert.equal(await page.evaluate(() => kineticaTerminalDemo.scrollOffset()), offset);
      assert.equal(await input.evaluate(el => document.activeElement === el), true);
      await page.keyboard.type("x"); assert.equal(await drain(), "x", "Typing resumes after cancellation");
      await paste(text); await page.keyboard.press("Enter");
      assert.equal(await dialog.count(), 0); assert.equal(await drain(), "", "Return defaults to cancellation");
      await paste(text); await write("\x1b[?2004h");
      await dialog.getByRole("button", { name: "Paste", exact: true }).click();
      assert.equal(await drain(), `\x1b[200~${text}\x1b[201~`, "Encode original CRLF with current mode after confirmation");
      assert.equal(await selection(), ""); assert.equal(await page.evaluate(() => kineticaTerminalDemo.scrollOffset()), 0);
      await page.keyboard.type("2"); assert.equal(await drain(), "2", "Typing resumes immediately after the dialog closes");
      await paste("safe\nmultiline"); assert.equal(await dialog.count(), 0);
      assert.equal(await drain(), "\x1b[200~safe\nmultiline\x1b[201~");
      await paste("a\x1b[201~b"); assert.equal(await dialog.count(), 1);
      await dialog.getByRole("button", { name: "Paste", exact: true }).click();
      assert.equal(await drain(), "\x1b[200~a [201~b\x1b[201~");
      await write("\x1b[?2004l");
      await paste("old\n");
      await dialog.getByRole("button", { name: "Paste", exact: true }).evaluate(el => globalThis.stalePasteButton = el);
      await paste("new\r");
      await page.evaluate(() => stalePasteButton.click()); assert.equal(await drain(), "");
      assert.equal(await dialog.count(), 1);
      await dialog.getByRole("button", { name: "Paste", exact: true }).click(); assert.equal(await drain(), "new\r");
      await paste("old\n"); await paste("safe"); assert.equal(await dialog.count(), 0); assert.equal(await drain(), "safe");
      await paste("old\n"); await paste(""); assert.equal(await dialog.count(), 0); assert.equal(await drain(), "");
      await paste("detached\n");
      await page.evaluate(() => { const root = document.querySelector("[data-terminal]"); const parent = root.parentNode; root.remove(); parent.append(root); });
      await page.waitForFunction(() => !document.querySelector("dialog")); assert.equal(await drain(), "");
      await input.focus(); await paste("disposed\n");
      await page.evaluate(() => kineticaTerminalDemo.dispose());
      assert.equal(await dialog.count(), 0); assert.equal(await drain(), "");
      assert.deepEqual(errors, []);
      results.push({ renderer, passed: true });
    } finally { await page.close(); }
  }
  await mkdir("build/reports", { recursive: true });
  await writeFile("build/reports/terminal-browser-paste.json", JSON.stringify(results, null, 2) + "\n");
  console.log(JSON.stringify(results));
} finally { await browser.close(); }
