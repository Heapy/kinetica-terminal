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
    assert.equal(await page.evaluate(() => kineticaTerminalDemo.fontMode), "bundled");
    assert.equal(await page.locator("[data-terminal]").getAttribute("data-terminal-renderer"), renderer === "gpu" ? "webgl2" : "canvas2d");
    const geometry = await page.evaluate(() => {
      const { width, height } = kineticaTerminalDemo.cellMetrics();
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
    await reset("https://example.org/path");
    await page.keyboard.down(modifier); await move(); await page.mouse.down();
    assert.deepEqual((await log()).opened, [], "Press cannot launch");
    await page.mouse.up(); await page.keyboard.up(modifier);
    assert.deepEqual((await log()).opened, [["https://example.org/path", "_blank", "noopener,noreferrer"]]);

    for (const mods of [[], [otherModifier], [modifier, "Shift"], [modifier, "Alt"], [modifier, otherModifier]]) {
      await reset(osc8("https://example.org")); await click(mods);
      assert.deepEqual((await log()).opened, [], `Wrong modifiers: ${mods}`);
    }
    for (const destination of [3.2, 1.8]) {
      await reset(osc8("https://example.org")); await page.keyboard.down(modifier);
      await move(); await page.mouse.down(); await move(destination); await move(); await page.mouse.up();
      await page.keyboard.up(modifier);
      assert.deepEqual((await log()).opened, [], "A drag cannot become a click by returning to its start");
    }
    await reset(osc8("https://example.org")); await page.keyboard.down(modifier);
    await move(); await page.mouse.down();
    await page.evaluate(() => window.dispatchEvent(new Event("blur")));
    await page.mouse.up(); await page.keyboard.up(modifier);
    assert.deepEqual((await log()).opened, [], "Blur cancels activation");

    await reset(osc8("https://example.org"));
    await page.evaluate(() => document.querySelector("[data-terminal]").addEventListener("pointerdown", event => {
      globalThis.cancelLinkPointer = event.pointerId;
    }, { once: true }));
    await page.keyboard.down(modifier); await move(); await page.mouse.down();
    await page.evaluate(() => document.querySelector("[data-terminal]").dispatchEvent(
      new PointerEvent("pointercancel", { pointerId: cancelLinkPointer, bubbles: true })));
    await page.mouse.up(); await page.keyboard.up(modifier);
    assert.deepEqual((await log()).opened, [], "Pointer cancellation cancels activation");

    await reset("https://example.org\x1b[?1000h\x1b[?1006h");
    await click(); assert.deepEqual((await log()).opened, [], "TUI receives captured click");
    await click([modifier, "Shift"]);
    assert.equal((await log()).opened[0]?.[0], "https://example.org/", "Shift bypasses mouse capture");
    await reset(osc8("https://example.org"));
    await page.evaluate(() => kineticaTerminalDemo.osc8LinksEnabled(false));
    await click(); assert.deepEqual((await log()).opened, []);
    await reset(osc8("https://frozen.example"));
    await write("\x1b[?2026h\x1b[Hchanged"); await click();
    assert.equal((await log()).opened[0]?.[0], "https://frozen.example/", "Frozen frame provides target");
    await reset(" ".repeat(38) + "https://wrapped.example/path"); await click([modifier], 2.2, 1);
    assert.equal((await log()).opened[0]?.[0], "https://wrapped.example/path");

    for (const url of ["javascript:alert(1)", "data:text/html,x", "file:///tmp/example.txt", "blob:https://example.org/id", "about:blank", "./relative", "https://example.org/\u202ex"]) {
      await reset(osc8(url)); await click();
      assert.deepEqual((await log()).opened, [], url);
      assert.equal((await log()).alerts.length, 1, url);
    }
    await reset(osc8("mailto:person@example.org")); await click();
    assert.equal((await log()).opened[0]?.[0], "mailto:person@example.org");
    for (const accept of [true, false]) {
      await reset(osc8("ssh://example.org"));
      await page.evaluate(accept => { linkLog.accept = accept; }, accept); await click();
      assert.equal((await log()).confirmations.length, 1);
      assert.equal((await log()).opened.length, accept ? 1 : 0);
    }
    await reset(osc8("ssh://example.org"));
    await page.evaluate(() => { linkLog.onConfirm = () => kineticaTerminalDemo.osc8LinksEnabled(false); }); await click();
    assert.deepEqual((await log()).opened, [], "Policy revocation during confirmation cancels launch");
    await reset("ssh://example.org");
    await page.evaluate(() => { linkLog.onConfirm = () => kineticaTerminalDemo.osc8LinksEnabled(false); }); await click();
    assert.equal((await log()).opened[0]?.[0], "ssh://example.org", "OSC 8 policy does not revoke detected text links");

    await reset("https://example.org/path");
    await page.evaluate(() => kineticaTerminalDemo.detectedLinksEnabled(false)); await click();
    assert.deepEqual((await log()).opened, [], "Detection can be disabled without altering the text");
    await page.mouse.click(...point(), { clickCount: 2 });
    const copiedWord = await page.evaluate(() => {
      const data = new DataTransfer();
      document.querySelector("[data-terminal]").dispatchEvent(new ClipboardEvent("copy", { clipboardData: data, bubbles: true, cancelable: true }));
      return data.getData("text/plain");
    });
    assert.equal(copiedWord, "https", "Disabled detection restores ordinary double-click word selection");
    await reset("ssh://example.org");
    await page.evaluate(() => { linkLog.onConfirm = () => kineticaTerminalDemo.detectedLinksEnabled(false); }); await click();
    assert.deepEqual((await log()).opened, [], "Detection revocation during confirmation cancels launch");
    await reset(osc8("ssh://example.org"));
    await page.evaluate(() => { linkLog.onConfirm = () => kineticaTerminalDemo.detectedLinksEnabled(false); }); await click();
    assert.equal((await log()).opened[0]?.[0], "ssh://example.org", "Detection revocation does not revoke OSC 8");

    // Check disposal before overriding the opener; reload to create a fresh view.
    await reset(osc8("ssh://example.org"));
    await page.evaluate(() => { linkLog.onConfirm = () => kineticaTerminalDemo.dispose(); }); await click();
    assert.deepEqual((await log()).opened, [], "Disposal during confirmation cancels launch");
    await page.reload(); await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 20);
    assert.equal(await page.evaluate(() => kineticaTerminalDemo.fontMode), "bundled");
    await reset(osc8("file:///tmp/example.txt"));
    await page.evaluate(() => kineticaTerminalDemo.openLink(uri => { linkLog.opened.push([uri, "host"]); return true; }));
    await click(); assert.deepEqual((await log()).opened, [["file:///tmp/example.txt", "host"]]);
    await reset(osc8("https://example.org"));
    await page.evaluate(() => kineticaTerminalDemo.openLink(uri => { linkLog.opened.push([uri, "declined"]); return false; }));
    await click(); assert.deepEqual((await log()).opened, [["https://example.org", "declined"]], "Declining a host callback does not invoke a second opener");
    report.cases.push({ platform, renderer, passed: true });
    await page.close();
  }
  assert.deepEqual(report.errors, []);
} finally {
  await browser.close();
  await mkdir("build/reports/terminal-links", { recursive: true });
  await writeFile("build/reports/terminal-links/report.json", JSON.stringify(report, null, 2) + "\n");
}
console.log(JSON.stringify(report));
