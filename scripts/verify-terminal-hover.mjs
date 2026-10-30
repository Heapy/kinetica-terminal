import assert from "node:assert/strict";
import { mkdir, writeFile } from "node:fs/promises";

const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const report = { cases: [], errors: [] };
try {
  for (const platform of ["MacIntel", "Linux x86_64"]) for (const renderer of ["gpu", "software"]) {
    const page = await browser.newPage({ viewport: { width: 900, height: 600 }, deviceScaleFactor: 2 });
    page.on("pageerror", error => report.errors.push(String(error)));
    await page.addInitScript(platform => {
      Object.defineProperty(navigator, "platform", { get: () => platform });
      globalThis.linkLog = { opened: [], alerts: [], confirmations: [], accept: true, onConfirm: null };
      window.open = (...args) => { linkLog.opened.push(args); return null; };
      window.alert = message => linkLog.alerts.push(message);
      window.confirm = message => {
        linkLog.confirmations.push(message); linkLog.onConfirm?.(); return linkLog.accept;
      };
    }, platform);
    await page.goto(`${process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173"}/samples/browser-terminal/web/index.html?renderer=${renderer}`);
    await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 20);
    assert.equal(await page.locator("[data-terminal]").getAttribute("data-terminal-renderer"), renderer === "gpu" ? "webgl2" : "canvas2d");
    const geometry = await page.evaluate(() => {
      const measure = document.createElement("canvas").getContext("2d");
      measure.font = '14px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace';
      const width = measure.measureText("M").width, height = Math.ceil(14 * 1.3);
      document.querySelector("#app").style.cssText = `flex:none;width:${Math.ceil(width * 40)}px;height:${height * 5}px`;
      const rect = document.querySelector("canvas").getBoundingClientRect();
      return { x: rect.x, y: rect.y, width, height };
    });
    await page.waitForFunction(() => kineticaTerminalDemo.columns() === 40 && kineticaTerminalDemo.rows() === 5);
    const modifier = platform === "MacIntel" ? "Meta" : "Control";
    const otherModifier = platform === "MacIntel" ? "Control" : "Meta";
    const point = (x = 1.2, y = 0) => [geometry.x + x * geometry.width, geometry.y + (y + .5) * geometry.height];
    const move = (x, y) => page.mouse.move(...point(x, y));
    const log = () => page.evaluate(() => ({ opened: linkLog.opened, alerts: linkLog.alerts, confirmations: linkLog.confirmations }));
    const osc8 = url => `\x1b]8;;${url}\x1b\\click\x1b]8;;\x1b\\`;
    const write = text => page.evaluate(text => kineticaTerminalDemo.write(text), text);
    const reset = async text => {
      await page.evaluate(() => {
        kineticaTerminalDemo.reset(); kineticaTerminalDemo.osc8LinksEnabled(true); kineticaTerminalDemo.detectedLinksEnabled(true);
        linkLog.opened = []; linkLog.alerts = []; linkLog.confirmations = []; linkLog.accept = true; linkLog.onConfirm = null;
      });
      await write(text);
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    };
    const click = async (modifiers = [modifier], x = 1.2, y = 0) => {
      for (const key of modifiers) await page.keyboard.down(key);
      await page.mouse.click(...point(x, y));
      for (const key of modifiers.toReversed()) await page.keyboard.up(key);
    };

    const paint = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const preview = () => page.evaluate(() => {
      const root = document.querySelector("[data-terminal]");
      const label = root.querySelector("[data-terminal-link-preview]");
      return { cursor: root.style.cursor, hidden: label.hidden, text: label.textContent };
    });
    await page.evaluate(({ width, height }) => {
      globalThis.linkHoverPixel = null;
      const gl = document.querySelector("canvas").getContext("webgl2");
      if (!gl) return;
      const draw = gl.drawArraysInstanced.bind(gl);
      gl.drawArraysInstanced = (...args) => {
        draw(...args);
        const pixel = new Uint8Array(4);
        gl.readPixels(Math.floor(1.2 * width * devicePixelRatio), gl.drawingBufferHeight - Math.floor((height - 2) * devicePixelRatio) - 1,
          1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixel);
        linkHoverPixel = [...pixel];
      };
    }, geometry);
    const pixel = () => page.evaluate(({ width, height }) => linkHoverPixel ?? [...document.querySelector("canvas").getContext("2d").getImageData(
      Math.floor(1.2 * width * devicePixelRatio), Math.floor((height - 2) * devicePixelRatio), 1, 1).data], geometry);
    await reset(osc8("https://hidden.example")); await move(); await paint();
    assert.equal((await preview()).hidden, true);
    assert.deepEqual(await pixel(), [30, 30, 30, 255]);
    await page.keyboard.down(modifier); await paint();
    assert.deepEqual(await preview(), { cursor: "pointer", hidden: false, text: "https://hidden.example" });
    assert.deepEqual(await pixel(), [212, 212, 212, 255], "Shared hover underline reaches the real renderer");
    await write("\x1b]22;crosshair\x1b\\");
    assert.equal((await preview()).cursor, "pointer");
    await page.keyboard.up(modifier); await paint();
    assert.equal((await preview()).cursor, "crosshair"); assert.equal((await preview()).hidden, true);
    assert.deepEqual(await pixel(), [30, 30, 30, 255], "Removing modifiers erases the hover underline");
    await page.keyboard.down(modifier);
    await write("\x1b[?1000h"); await paint();
    assert.equal((await preview()).hidden, true, "Capture change clears hover without pointer movement");
    await page.keyboard.down("Shift"); await paint(); assert.equal((await preview()).hidden, false);
    await write("\x1b[>1s"); await paint(); assert.equal((await preview()).hidden, true, "Application Shift capture wins");
    await write("\x1b[>0s"); await paint(); assert.equal((await preview()).hidden, false);
    await page.keyboard.up("Shift"); await write("\x1b[?1000l"); await paint();
    assert.equal((await preview()).hidden, false);
    await page.mouse.down(); await move(3.2); await paint(); assert.equal((await preview()).hidden, true);
    await move(); await paint(); assert.equal((await preview()).hidden, false);
    await page.mouse.up(); assert.deepEqual((await log()).opened, [], "Returning hover must not restore click activation");
    await page.keyboard.up(modifier);

    await reset("https://example.org/path"); await move(); await page.keyboard.down(modifier); await paint();
    assert.equal((await preview()).text, "https://example.org/path");
    await page.evaluate(() => kineticaTerminalDemo.detectedLinksEnabled(false)); await paint();
    assert.equal((await preview()).hidden, true); assert.equal((await preview()).cursor, "crosshair", "Restore the current OSC 22 cursor");
    await page.evaluate(() => kineticaTerminalDemo.detectedLinksEnabled(true)); await paint();
    assert.equal((await preview()).hidden, false);
    await page.evaluate(() => kineticaTerminalDemo.linkPreviewMode("OSC8"));
    assert.equal((await preview()).hidden, true); assert.equal((await preview()).cursor, "pointer");
    await reset(osc8("https://hidden.example")); await paint(); assert.equal((await preview()).hidden, false);
    await page.evaluate(() => kineticaTerminalDemo.detectedLinksEnabled(false)); await paint();
    assert.equal((await preview()).hidden, false, "Detection policy leaves OSC 8 previews enabled");
    await page.evaluate(() => kineticaTerminalDemo.linkPreviewMode("NONE"));
    assert.equal((await preview()).hidden, true); assert.equal((await preview()).cursor, "pointer");
    await page.evaluate(() => kineticaTerminalDemo.linkPreviewMode("ALL"));
    await write("\x1b[?2026h\x1b[H\x1b[2Kchanged"); await paint();
    assert.equal((await preview()).text, "https://hidden.example");
    await page.evaluate(() => kineticaTerminalDemo.osc8LinksEnabled(false)); await paint(); assert.equal((await preview()).hidden, true);
    await page.evaluate(() => kineticaTerminalDemo.osc8LinksEnabled(true)); await paint(); assert.equal((await preview()).hidden, false);
    await write("\x1b[?2026l"); await paint(); assert.equal((await preview()).hidden, true);
    await reset(osc8("https://example.org/\u202ex")); await paint();
    assert.equal((await preview()).text, "https://example.org/\\u{202E}x");
    await page.mouse.move(geometry.x + geometry.width * 42, geometry.y); await paint(); assert.equal((await preview()).hidden, true);
    await move(); await paint(); assert.equal((await preview()).hidden, false);
    await page.evaluate(() => window.dispatchEvent(new Event("blur"))); await paint(); assert.equal((await preview()).hidden, true);
    await move(1.3); await paint(); assert.equal((await preview()).hidden, false);
    await page.evaluate(() => kineticaTerminalDemo.dispose());
    await page.keyboard.up(modifier);
    report.cases.push({ platform, renderer, passed: true });
    await page.close();
  }
  assert.deepEqual(report.errors, []);
} finally {
  await browser.close();
  await mkdir("build/reports/terminal-hover", { recursive: true });
  await writeFile("build/reports/terminal-hover/report.json", JSON.stringify(report, null, 2) + "\n");
}
console.log(JSON.stringify(report));
