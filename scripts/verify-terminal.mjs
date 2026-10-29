import assert from "node:assert/strict";

const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const page = await browser.newPage({ viewport: { width: 1100, height: 720 }, deviceScaleFactor: 2 });
const errors = [];
page.on("pageerror", error => errors.push(String(error)));
try {
  // Intercept desktop delivery so this test never posts notifications to the user's desktop.
  await page.addInitScript(() => {
    globalThis.notificationCalls = []; globalThis.notificationPermissionRequests = 0;
    class TestNotification {
      static permission = "default";
      static requestPermission() { notificationPermissionRequests++; this.permission = "granted"; return Promise.resolve("granted"); }
      constructor(title, options) { this.call = { title, ...options, closed: false }; notificationCalls.push(this.call); }
      close() { this.call.closed = true; }
    }
    globalThis.Notification = TestNotification;
  });
  const url = `${process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173"}/samples/browser-terminal/web/index.html`;
  await page.goto(`${url}?renderer=gpu`);
  await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 80);
  await page.waitForFunction(() => +document.querySelector("[data-terminal]")?.dataset.terminalGlyphs > 0);
  await page.evaluate(() => kineticaTerminalDemo.write("\x1b]9;No permission yet\x07"));
  assert.deepEqual(await page.evaluate(() => [notificationPermissionRequests, notificationCalls.length]), [0, 0]);
  await page.locator("#notifications").click();
  await page.waitForFunction(() => document.querySelector("#notifications").disabled);
  await page.evaluate(() => kineticaTerminalDemo.write("\x1b]777;notify;Done;Task completed 世界\x07"));
  assert.equal(await page.evaluate(() => notificationCalls.at(-1).body), "Task completed 世界");
  assert.equal(await page.evaluate(() => notificationCalls.at(-1).title), "Done");
  await page.evaluate(() => { kineticaTerminalDemo.notificationsEnabled(false); kineticaTerminalDemo.write("\x1b]9;Denied by host\x07"); });
  assert.equal(await page.evaluate(() => notificationCalls.length), 1);
  await page.evaluate(() => { kineticaTerminalDemo.notificationsEnabled(true); kineticaTerminalDemo.write("\x1b]9;Replacement\x07"); });
  assert.equal(await page.evaluate(() => notificationCalls[0].closed), true);
  const progress = page.locator("[data-terminal-progress]");
  await page.evaluate(() => kineticaTerminalDemo.write("\x1b[?2026h\x1b]9;4;1;45\x07"));
  assert.equal(await progress.isVisible(), true);
  assert.equal(await progress.getAttribute("value"), "45");
  await page.evaluate(() => kineticaTerminalDemo.write("\x1b]9;4;2\x07"));
  assert.equal(await progress.getAttribute("data-state"), "error");
  assert.equal(await progress.getAttribute("value"), null);
  await page.evaluate(() => kineticaTerminalDemo.write("\x1b]9;4;4\x07"));
  assert.equal(await progress.getAttribute("value"), "100");
  await page.evaluate(() => kineticaTerminalDemo.write("\x1b]9;4;3\x07"));
  assert.equal(await progress.getAttribute("value"), null);
  await page.waitForFunction(() => document.querySelector("[data-terminal-progress]").hidden, null, { timeout: 20000 });
  await page.evaluate(() => kineticaTerminalDemo.write("\x1b]9;4;1;20\x07\x1bc"));
  assert.equal(await progress.isVisible(), false);
  assert.equal(await page.locator("[data-terminal]").getAttribute("data-terminal-renderer"), "webgl2");
  const pointerStyle = () => page.locator("[data-terminal]").evaluate(element => getComputedStyle(element).cursor);
  assert.equal(await pointerStyle(), "text");
  await page.evaluate(() => kineticaTerminalDemo.write("\x1b]22;pointer\x07"));
  assert.equal(await pointerStyle(), "pointer");
  await page.evaluate(() => kineticaTerminalDemo.write("\x1b[?2026h\x1b]22;zoom-in\x1b\\"));
  assert.equal(await pointerStyle(), "zoom-in");
  await page.evaluate(() => kineticaTerminalDemo.write("\x1b]22;invalid\x07\x1b[?2026l"));
  assert.equal(await pointerStyle(), "zoom-in");
  await page.evaluate(() => kineticaTerminalDemo.write("\x1b]22;xterm\x07"));
  assert.equal(await pointerStyle(), "text");
  const input = page.locator("textarea");
  await page.locator("canvas").click();
  await page.keyboard.type("help");
  await page.keyboard.press("Enter");
  assert.match(await page.evaluate(() => kineticaTerminalDemo.screen()), /Commands: help/);
  // The browser input pipeline emits committed Unicode exactly once, including IME completion.
  await input.evaluate(element => {
    element.dispatchEvent(new CompositionEvent("compositionstart", { data: "" }));
    element.value = "日本";
    element.dispatchEvent(new InputEvent("input", { data: "日本", isComposing: true }));
    element.dispatchEvent(new CompositionEvent("compositionend", { data: "日本" }));
    element.value = "日本";
    element.dispatchEvent(new InputEvent("input", { data: "日本", inputType: "insertCompositionText" }));
  });
  assert.match(await page.evaluate(() => kineticaTerminalDemo.screen()), /\$ 日本\s*$/);
  await page.keyboard.press("Control+c");
  await input.evaluate(element => {
    const data = new DataTransfer(); data.setData("text/plain", "colors");
    element.dispatchEvent(new ClipboardEvent("paste", { clipboardData: data, bubbles: true, cancelable: true }));
  });
  await page.keyboard.press("Enter");
  const before = await page.locator("canvas").elementHandle();
  await page.evaluate(() => kineticaTerminalDemo.rerender());
  assert(await before.evaluate(element => element === document.querySelector("canvas")));
  const dimensions = await page.evaluate(() => [kineticaTerminalDemo.columns(), kineticaTerminalDemo.rows()]);
  await page.setViewportSize({ width: 740, height: 480 });
  await page.waitForFunction(([columns, rows]) => kineticaTerminalDemo.columns() < columns && kineticaTerminalDemo.rows() < rows, dimensions);
  const promptNavigation = await page.evaluate(() => {
    for (let i = 0; i < 60; i++) kineticaTerminalDemo.write(`\x1b]133;A\x07command-${i}\r\n\x1b]133;C\x07output\r\n`);
    const previous = kineticaTerminalDemo.jumpToPrompt(-1);
    const offset = kineticaTerminalDemo.scrollOffset();
    const next = kineticaTerminalDemo.jumpToPrompt(1);
    return [previous, offset > 0, next, kineticaTerminalDemo.scrollOffset() < offset];
  });
  assert.deepEqual(promptNavigation, [true, true, true, true]);
  // Sustained output stays in the terminal surface and bounded history, not a growing DOM tree.
  await page.evaluate(() => kineticaTerminalDemo.write("line\r\n".repeat(12000)));
  assert.equal(await page.evaluate(() => kineticaTerminalDemo.history()), 10000);
  assert.equal(await page.locator("canvas").count(), 1);
  assert.equal(await page.locator("[data-terminal] *").count(), 5); // Includes the fixed progress overlay.
  const painted = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  // Real GPU pixels, cache reuse, and bounded draw calls. Read while the drawing buffer is valid.
  await page.evaluate(() => {
    const gl = document.querySelector("canvas").getContext("webgl2");
    const draw = gl.drawArraysInstanced.bind(gl);
    gl.drawArraysInstanced = (...args) => {
      draw(...args);
      const pixel = new Uint8Array(4);
      gl.readPixels(2, gl.drawingBufferHeight - 3, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixel);
      globalThis.terminalGpuPixel = [...pixel];
      gl.readPixels(2, gl.drawingBufferHeight - 53, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixel);
      globalThis.terminalGpuSecondRowPixel = [...pixel];
    };
    kineticaTerminalDemo.write("\u001b[2J\u001b[H\u001b[48;2;240;20;30m \u001b[0mX界é😀");
  });
  await painted();
  assert.deepEqual(await page.evaluate(() => terminalGpuPixel), [240, 20, 30, 255]);
  const rasterizations = +(await page.locator("[data-terminal]").getAttribute("data-terminal-rasterizations"));
  await page.evaluate(() => kineticaTerminalDemo.write("\u001b[1;2HX界é😀"));
  await painted();
  assert.equal(+(await page.locator("[data-terminal]").getAttribute("data-terminal-rasterizations")), rasterizations);
  assert(+(await page.locator("[data-terminal]").getAttribute("data-terminal-draw-calls")) <= 6);
  assert.equal(await page.evaluate(() => document.querySelector("canvas").getContext("webgl2").getError()), 0);
  // Palette changes repaint existing indexed cells, including retained rows away from the cursor.
  await page.evaluate(() => kineticaTerminalDemo.write("\u001b[2J\u001b[H\u001b[44m \r\n \u001b[H"));
  await painted();
  assert.deepEqual(await page.evaluate(() => terminalGpuSecondRowPixel), [36, 114, 200, 255]);
  await page.evaluate(() => kineticaTerminalDemo.write("\u001b]4;4;#123456\u0007\u001b]11;#654321\u0007"));
  await painted();
  assert.deepEqual(await page.evaluate(() => terminalGpuPixel), [18, 52, 86, 255]);
  assert.deepEqual(await page.evaluate(() => terminalGpuSecondRowPixel), [18, 52, 86, 255]);
  assert.equal(await page.locator("[data-terminal]").evaluate(element => getComputedStyle(element).backgroundColor), "rgb(101, 67, 33)");
  await page.evaluate(() => kineticaTerminalDemo.write("\u001b]21;4=#abcdef;background=#345678\u001b\\"));
  await painted();
  assert.deepEqual(await page.evaluate(() => terminalGpuPixel), [171, 205, 239, 255]);
  assert.deepEqual(await page.evaluate(() => terminalGpuSecondRowPixel), [171, 205, 239, 255]);
  assert.equal(await page.locator("[data-terminal]").evaluate(element => getComputedStyle(element).backgroundColor), "rgb(52, 86, 120)");
  await page.evaluate(() => kineticaTerminalDemo.write("\u001b]104;4\u0007\u001b]111\u0007\u001b[0m\u001b[2J\u001b[HX界é😀"));
  await painted();
  // Font metrics invalidate the atlas; lost WebGL resources are rebuilt without losing the session.
  await page.evaluate(() => kineticaTerminalDemo.fontSize(18));
  await page.waitForFunction(before => +document.querySelector("[data-terminal]").dataset.terminalRasterizations > before, rasterizations);
  const beforeFamily = +(await page.locator("[data-terminal]").getAttribute("data-terminal-rasterizations"));
  await page.evaluate(() => kineticaTerminalDemo.fontFamily("Courier New"));
  await painted();
  assert((+(await page.locator("[data-terminal]").getAttribute("data-terminal-rasterizations"))) > beforeFamily);
  assert.equal(await page.evaluate(() => {
    const measure = document.createElement("canvas").getContext("2d");
    measure.font = '18px "Courier New", ui-monospace, SFMono-Regular, Menlo, Consolas, monospace';
    return kineticaTerminalDemo.columns() === Math.floor(document.querySelector("[data-terminal]").clientWidth / measure.measureText("M").width);
  }), true);
  await page.evaluate(() => kineticaTerminalDemo.fontFamily(null));
  await painted();
  // Capture at the mode boundary, not at the previous animation frame. Prefix is blue; body is green.
  await page.evaluate(() => kineticaTerminalDemo.write("\u001b[?25l\u001b[H\u001b[44m X\u001b[?2026h\u001b[H\u001b[42m Y"));
  await painted();
  assert.equal(await page.evaluate(() => kineticaTerminalDemo.synchronizedOutput()), true);
  assert.deepEqual(await page.evaluate(() => terminalGpuPixel), [36, 114, 200, 255]);
  const beforeRestore = +(await page.locator("[data-terminal]").getAttribute("data-terminal-rasterizations"));
  const screenBeforeLoss = await page.evaluate(() => kineticaTerminalDemo.screen());
  await page.evaluate(() => {
    const gl = document.querySelector("canvas").getContext("webgl2");
    globalThis.terminalLoseContext = gl.getExtension("WEBGL_lose_context");
    if (!terminalLoseContext) throw new Error("Context loss extension unavailable");
    terminalLoseContext.loseContext();
  });
  await page.waitForFunction(() => document.querySelector("canvas").getContext("webgl2").isContextLost());
  await page.waitForTimeout(100);
  await page.evaluate(() => terminalLoseContext.restoreContext());
  await page.waitForFunction(before => +document.querySelector("[data-terminal]").dataset.terminalRasterizations > before, beforeRestore);
  assert.equal(await page.evaluate(() => kineticaTerminalDemo.screen()), screenBeforeLoss);
  assert.equal(await page.evaluate(() => document.querySelector("canvas").getContext("webgl2").getError()), 0);
  assert.equal(await page.evaluate(() => kineticaTerminalDemo.synchronizedOutput()), true);
  assert.deepEqual(await page.evaluate(() => terminalGpuPixel), [36, 114, 200, 255], "Context recovery must retain the held frame");
  await page.evaluate(() => kineticaTerminalDemo.write("\u001b[?2026l"));
  await painted();
  assert.deepEqual(await page.evaluate(() => terminalGpuPixel), [13, 188, 121, 255]);
  // The host deadline recovers from an application that never sends the closing sequence.
  await page.evaluate(() => kineticaTerminalDemo.write("\u001b[?2026h\u001b[H\u001b[41m "));
  await page.waitForFunction(() => !kineticaTerminalDemo.synchronizedOutput(), null, { timeout: 3000 });
  await painted();
  assert.deepEqual(await page.evaluate(() => terminalGpuPixel), [205, 49, 49, 255]);
  // Real cursor pixels and the browser's one-shot blink timer.
  await page.evaluate(() => kineticaTerminalDemo.write("\u001b[0m\u001b[2J\u001b[H\u001b[?25h\u001b[2 q"));
  await painted();
  assert.deepEqual(await page.evaluate(() => terminalGpuPixel), [238, 238, 238, 255]);
  await page.evaluate(() => kineticaTerminalDemo.write("\u001b[4 q"));
  await painted();
  assert.deepEqual(await page.evaluate(() => terminalGpuPixel), [30, 30, 30, 255]);
  await page.evaluate(() => kineticaTerminalDemo.write("\u001b[6 q"));
  await painted();
  assert.deepEqual(await page.evaluate(() => terminalGpuPixel), [238, 238, 238, 255]);
  await page.evaluate(() => kineticaTerminalDemo.write("\u001b[1 q"));
  await page.waitForFunction(() => terminalGpuPixel[0] === 30, null, { timeout: 2000 });
  await page.waitForFunction(() => terminalGpuPixel[0] === 238, null, { timeout: 2000 });
  await page.evaluate(() => kineticaTerminalDemo.fontSize(14));
  await page.evaluate(() => kineticaTerminalDemo.write("\u001b[2J\u001b[H\u001b[1;36mKinetica terminal\u001b[0m\r\nBrowser integration passed.\r\n世界 é 😀"));
  await painted();
  await page.screenshot({ path: process.env.TERMINAL_SCREENSHOT ?? "/private/tmp/kinetica-terminal-browser.png" });
  const notificationsBeforeDispose = await page.evaluate(() => notificationCalls.length);
  await page.evaluate(() => { kineticaTerminalDemo.dispose(); kineticaTerminalDemo.write("\x1b]9;Detached\x07\x1b]9;4;1;50\x07"); });
  assert.equal(await page.evaluate(() => notificationCalls.length), notificationsBeforeDispose);
  assert.equal(await page.locator("#app > *").count(), 0);
  await page.waitForTimeout(650);
  assert.deepEqual(errors, []);
  await page.goto(`${url}?renderer=software`);
  await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 80);
  assert.equal(await page.locator("[data-terminal]").getAttribute("data-terminal-renderer"), "canvas2d");
  await page.evaluate(() => kineticaTerminalDemo.fontFamily("Courier New"));
  await painted();
  assert.match(await page.evaluate(() => document.querySelector("canvas").getContext("2d").font), /Courier New/);
  await page.evaluate(() => kineticaTerminalDemo.fontFamily(null));
  await painted();
  await page.evaluate(() => kineticaTerminalDemo.write("\u001b[2J\u001b[H\u001b[44m \r\n \u001b[H"));
  await painted();
  await page.evaluate(() => kineticaTerminalDemo.write("\u001b]4;4;#123456\u0007\u001b]11;#654321\u0007"));
  await painted();
  assert.deepEqual(await page.evaluate(() => [...document.querySelector("canvas").getContext("2d").getImageData(2, 52, 1, 1).data]), [18, 52, 86, 255]);
  await page.locator("canvas").click();
  await page.evaluate(() => kineticaTerminalDemo.write("\u001b[?25l\u001b[H\u001b[41m X\u001b[?2026h\u001b[H\u001b[42m Y"));
  await painted();
  assert.deepEqual(await page.evaluate(() => [...document.querySelector("canvas").getContext("2d").getImageData(2, 2, 1, 1).data]), [205, 49, 49, 255]);
  await page.evaluate(() => kineticaTerminalDemo.write("\u001b[?2026l"));
  await painted();
  assert.deepEqual(await page.evaluate(() => [...document.querySelector("canvas").getContext("2d").getImageData(2, 2, 1, 1).data]), [13, 188, 121, 255]);
  await page.evaluate(() => kineticaTerminalDemo.dispose());
  await page.addInitScript(() => {
    const getContext = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function(type, ...args) { return type === "webgl2" ? null : getContext.call(this, type, ...args); };
  });
  await page.goto(url);
  await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 80);
  assert.equal(await page.locator("[data-terminal]").getAttribute("data-terminal-renderer"), "canvas2d");
  await page.locator("canvas").click();
  await page.keyboard.type("help"); await page.keyboard.press("Enter");
  assert.match(await page.evaluate(() => kineticaTerminalDemo.screen()), /Commands: help/);
  // Capture actual host input through the common asynchronous byte transport. Physical
  // keypad identity must survive the DOM mapping without duplicating textarea input.
  await page.evaluate(() => {
    globalThis.keypadBytes = [];
    kineticaTerminalDemo.connectTransport({
      write(bytes) { keypadBytes.push(...bytes); return bytes.length; },
      resize() {}, consumed() {}, rejected() { throw new Error("Keypad input rejected"); },
      failed(message) { throw new Error(message); },
    });
  });
  const keyEvent = (key, code, modifiers = {}) => input.evaluate((element, options) => {
    const event = new KeyboardEvent("keydown", { ...options, bubbles: true, cancelable: true });
    element.dispatchEvent(event); return event.defaultPrevented;
  }, { key, code, location: code.startsWith("Numpad") ? 3 : 0, ...modifiers });
  // OSC 52 uses the actual browser clipboard, in this isolated browser context.
  await page.context().grantPermissions(["clipboard-read", "clipboard-write"]);
  const clipboardText = "clipboard 世界\n" + "large ".repeat(20000);
  const clipboardEncoded = Buffer.from(clipboardText).toString("base64");
  await page.evaluate(encoded => {
    keypadBytes.length = 0;
    kineticaTerminalDemo.clipboardPolicy("allow");
    kineticaTerminalDemo.write(`\x1b]52;c;${encoded}\x07\x1b]52;c;?\x1b\\`);
  }, clipboardEncoded);
  const clipboardReply = `\x1b]52;c;${clipboardEncoded}\x1b\\`;
  await page.waitForFunction(length => keypadBytes.length === length, Buffer.byteLength(clipboardReply));
  assert.equal(await page.evaluate(() => navigator.clipboard.readText()), clipboardText);
  assert.equal(Buffer.from(await page.evaluate(() => keypadBytes)).toString(), clipboardReply);
  await page.evaluate(() => {
    keypadBytes.length = 0;
    kineticaTerminalDemo.clipboardPolicy("deny");
    kineticaTerminalDemo.write("\x1b]52;c;ZXZpbA==\x07\x1b]52;c;?\x07");
  });
  await page.waitForFunction(() => keypadBytes.length > 0);
  assert.equal(Buffer.from(await page.evaluate(() => keypadBytes)).toString(), "\x1b]52;c;\x07");
  assert.equal(await page.evaluate(() => navigator.clipboard.readText()), clipboardText);
  await page.evaluate(() => {
    keypadBytes.length = 0;
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: {
      readText: () => Promise.reject(new DOMException("Denied", "NotAllowedError")),
      writeText: () => Promise.reject(new DOMException("Denied", "NotAllowedError")),
    } });
    kineticaTerminalDemo.clipboardPolicy("allow");
    kineticaTerminalDemo.write("\x1b]52;c;YQ==\x07\x1b]52;c;?\x07");
  });
  await page.waitForFunction(() => keypadBytes.length > 0);
  assert.equal(Buffer.from(await page.evaluate(() => keypadBytes)).toString(), "\x1b]52;c;\x07");
  await page.evaluate(() => { delete navigator.clipboard; });
  await page.evaluate(() => { keypadBytes.length = 0; kineticaTerminalDemo.clipboardPolicy("write"); });
  // The previous renderer was disposed; the locator resolves the current textarea.
  await page.keyboard.type("2");
  await page.keyboard.press("NumpadEnter");
  await page.evaluate(() => kineticaTerminalDemo.write("\u001b=\u001b[?1035l"));
  for (const [key, code, mods] of [
    ["Enter", "Enter"], ["Enter", "NumpadEnter"], ["1", "Numpad1"],
    ["1", "Numpad1", { shiftKey: true, altKey: true, ctrlKey: true }],
    [",", "NumpadDecimal"], ["=", "NumpadEqual"], [",", "NumpadComma"],
    ["End", "Numpad1"],
  ]) assert.equal(await keyEvent(key, code, mods), true);
  await page.evaluate(() => kineticaTerminalDemo.write("\u001b[?1h"));
  assert.equal(await keyEvent("End", "Numpad1"), true);
  assert.equal(await keyEvent("Clear", "Numpad5"), true);
  assert.equal(await keyEvent("ArrowUp", "ArrowUp"), true);
  // IME owns keypad events while composing, just as it owns ordinary keys.
  await input.evaluate(element => element.dispatchEvent(new CompositionEvent("compositionstart")));
  assert.equal(await keyEvent("Enter", "NumpadEnter", { isComposing: true }), false);
  await input.evaluate(element => element.dispatchEvent(new CompositionEvent("compositionend", { data: "日本" })));
  await page.evaluate(() => kineticaTerminalDemo.write("\u001b[?1035h"));
  assert.equal(await keyEvent("2", "Numpad2", { ctrlKey: true }), true);
  assert.equal(await keyEvent(",", "NumpadDecimal"), true);
  const expectedKeypad = "2\r\r\u001bOM\u001bOq\u001bO8q\u001bOn\u001bOX\u001bOl\u001b[F\u001bOF\u001bOE\u001bOA日本2,";
  await page.waitForFunction(length => keypadBytes.length === length && kineticaTerminalDemo.transportState().pendingInputBytes === 0,
    Buffer.byteLength(expectedKeypad));
  assert.equal(await page.evaluate(() => new TextDecoder().decode(new Uint8Array(keypadBytes))), expectedKeypad);
  // Kitty negotiation and actual DOM press/repeat/release, including IME ownership.
  await page.evaluate(() => { keypadBytes.length = 0; kineticaTerminalDemo.write("\u001b[>3u"); });
  await page.keyboard.press("Enter");
  await page.keyboard.press("Shift+Enter");
  await page.keyboard.press("Control+Enter");
  await page.keyboard.down("ArrowUp");
  await page.keyboard.down("ArrowUp");
  await page.keyboard.up("ArrowUp");
  const expectedKitty = "\r\u001b[13;2u\u001b[13;5u\u001b[1;1:1A\u001b[1;1:2A\u001b[1;1:3A";
  await page.waitForFunction(n => keypadBytes.length === n, Buffer.byteLength(expectedKitty));
  assert.equal(await page.evaluate(() => new TextDecoder().decode(new Uint8Array(keypadBytes))), expectedKitty);
  await page.evaluate(() => { keypadBytes.length = 0; kineticaTerminalDemo.write("\u001b[=31u"); });
  await page.keyboard.press("a");
  await input.evaluate(element => {
    element.dispatchEvent(new CompositionEvent("compositionstart"));
    element.dispatchEvent(new KeyboardEvent("keydown", { key: "Enter", code: "Enter", isComposing: true }));
    element.dispatchEvent(new CompositionEvent("compositionend", { data: "日本" }));
    element.value = "日本";
    element.dispatchEvent(new InputEvent("input", { data: "日本", inputType: "insertCompositionText" }));
  });
  const expectedKittyText = "\u001b[97;;97u\u001b[97;1:3u日本";
  await page.waitForFunction(n => keypadBytes.length === n, Buffer.byteLength(expectedKittyText));
  assert.equal(await page.evaluate(() => new TextDecoder().decode(new Uint8Array(keypadBytes))), expectedKittyText);
  assert.equal(await keyEvent("v", "KeyV", { metaKey: true }), false, "Local paste must remain available in Kitty mode");
  await page.evaluate(() => { keypadBytes.length = 0; });
  await page.keyboard.press("c");
  assert.equal(await keyEvent("C", "KeyC", { ctrlKey: true, shiftKey: true }), true,
    "Ctrl+Shift+C without a selection reaches the application");
  const expectedShortcut = "\u001b[99;;99u\u001b[99;1:3u\u001b[99:67;6u";
  await page.waitForFunction(n => keypadBytes.length === n, Buffer.byteLength(expectedShortcut));
  assert.equal(await page.evaluate(() => new TextDecoder().decode(new Uint8Array(keypadBytes))), expectedShortcut);
  await page.evaluate(() => { kineticaTerminalDemo.write("\u001b[<u"); keypadBytes.length = 0; });
  await page.evaluate(() => kineticaTerminalDemo.write("\u001b[>4;2m"));
  await page.keyboard.press("Shift+Enter");
  await page.keyboard.press("Control+c");
  await page.keyboard.press("Shift+Tab");
  const expectedModifyOther = "\u001b[27;2;13~\u001b[27;5;99~\u001b[27;2;9~";
  await page.waitForFunction(n => keypadBytes.length === n, Buffer.byteLength(expectedModifyOther));
  assert.equal(await page.evaluate(() => new TextDecoder().decode(new Uint8Array(keypadBytes))), expectedModifyOther);
  await page.evaluate(() => { kineticaTerminalDemo.write("\u001b[>4n"); keypadBytes.length = 0; });
  await page.evaluate(() => {
    kineticaTerminalDemo.write("\u001b[?996n\u001b[?2031h");
    kineticaTerminalDemo.configureTheme(0xffffff);
    kineticaTerminalDemo.write("\u001b]11;#000000\u0007\u001b[?996n\u001b[?2031l");
    kineticaTerminalDemo.configureTheme(0x1e1e1e);
  });
  const expectedTheme = "\u001b[?997;1n\u001b[?997;2n\u001b[?997;2n";
  await page.waitForFunction(n => keypadBytes.length === n, Buffer.byteLength(expectedTheme));
  assert.equal(await page.evaluate(() => new TextDecoder().decode(new Uint8Array(keypadBytes))), expectedTheme);
  await page.evaluate(() => { keypadBytes.length = 0; });
  const expectedSizeReport = size => page.evaluate(size => {
    const context = document.createElement("canvas").getContext("2d");
    context.font = `${size}px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace`;
    const width = Math.floor(context.measureText("M").width * devicePixelRatio);
    const height = Math.floor(Math.ceil(size * 1.3) * devicePixelRatio);
    const columns = kineticaTerminalDemo.columns(), rows = kineticaTerminalDemo.rows();
    return `\x1b[48;${rows};${columns};${rows * height};${columns * width}t`;
  }, size);
  await page.evaluate(() => kineticaTerminalDemo.write("\x1b[?2048h"));
  let expectedSize = await expectedSizeReport(14);
  await page.waitForFunction(n => keypadBytes.length === n, Buffer.byteLength(expectedSize));
  assert.equal(await page.evaluate(() => new TextDecoder().decode(new Uint8Array(keypadBytes.splice(0)))), expectedSize);
  await page.evaluate(() => kineticaTerminalDemo.fontSize(18));
  await page.waitForFunction(() => keypadBytes.length > 0);
  expectedSize = await expectedSizeReport(18);
  assert.equal(await page.evaluate(() => new TextDecoder().decode(new Uint8Array(keypadBytes.splice(0)))), expectedSize);
  await page.evaluate(() => { kineticaTerminalDemo.write("\x1b[?2048l"); kineticaTerminalDemo.fontSize(14); });
  // Mouse reports cross the same bounded stream as Unicode keyboard input. Real pointer
  // events cover capture, chorded buttons and the raw-byte coordinate boundary at column 95.
  await page.setViewportSize({ width: 1200, height: 720 });
  await page.waitForFunction(() => kineticaTerminalDemo.columns() > 100);
  const geometry = await page.evaluate(() => {
    const rect = document.querySelector("canvas").getBoundingClientRect();
    const context = document.createElement("canvas").getContext("2d");
    context.font = "14px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";
    return { left: rect.left, top: rect.top, width: context.measureText("M").width, height: Math.ceil(14 * 1.3) };
  });
  const moveCell = (x, y, fraction = 0.5) => page.mouse.move(geometry.left + (x + fraction) * geometry.width,
    geometry.top + (y + fraction) * geometry.height);
  const readMouse = async () => {
    await page.waitForFunction(() => kineticaTerminalDemo.transportState().pendingInputBytes === 0);
    return page.evaluate(() => keypadBytes.splice(0).map(value => value & 255));
  };
  const resetMouse = async controls => {
    await readMouse();
    await page.evaluate(controls => { kineticaTerminalDemo.reset(); kineticaTerminalDemo.write(controls); }, controls);
  };
  await resetMouse("\x1b[?1000h");
  await moveCell(95, 1); await page.mouse.down(); await page.mouse.up();
  assert.deepEqual(await readMouse(), [27, 91, 77, 32, 128, 34, 27, 91, 77, 35, 128, 34]);
  await resetMouse("\x1b[?9h");
  await page.keyboard.down("Alt"); await page.keyboard.down("Control");
  await moveCell(2, 1); await page.mouse.down(); await moveCell(3, 1); await page.mouse.up();
  await page.keyboard.up("Control"); await page.keyboard.up("Alt");
  assert.deepEqual(await readMouse(), [27, 91, 77, 32, 35, 34]);
  await resetMouse("\x1b[?1003h\x1b[?1006h\x1b[?1004h");
  await moveCell(1, 1); await moveCell(1, 1, 0.6);
  await page.mouse.down({ button: "right" }); await moveCell(2, 1); await page.mouse.up({ button: "right" });
  await page.mouse.down(); await page.mouse.down({ button: "middle" }); await moveCell(3, 1);
  await page.mouse.up({ button: "middle" }); await page.mouse.up();
  assert.equal(Buffer.from(await readMouse()).toString(),
    "\x1b[<35;2;2M\x1b[<2;2;2M\x1b[<34;3;2M\x1b[<2;3;2m" +
    "\x1b[<0;3;2M\x1b[<1;3;2M\x1b[<32;4;2M\x1b[<1;4;2m\x1b[<0;4;2m");
  // A locally selected gesture remains local if Shift is released before the mouse button.
  await page.keyboard.down("Shift"); await moveCell(1, 1); await page.mouse.down();
  await page.keyboard.up("Shift"); await moveCell(4, 1); await page.mouse.up();
  assert.deepEqual(await readMouse(), []);
  await page.locator("[data-terminal]").evaluate(element => {
    const rect = element.getBoundingClientRect();
    const options = { clientX: rect.left + 1, clientY: rect.top + 1, bubbles: true, cancelable: true };
    for (const [deltaX, deltaY] of [[0, 0], [-1, 0], [1, 0], [0, -1], [0, 1]])
      element.dispatchEvent(new WheelEvent("wheel", { ...options, deltaX, deltaY }));
  });
  assert.equal(Buffer.from(await readMouse()).toString(), "\x1b[<66;1;1M\x1b[<67;1;1M\x1b[<64;1;1M\x1b[<65;1;1M");
  // Pointer cancellation releases a previously reported press once; detaching the widget
  // must leave no event handlers writing to its still application-owned session.
  await moveCell(1, 1); await readMouse(); await page.mouse.down();
  await page.locator("[data-terminal]").dispatchEvent("pointercancel", { pointerId: 1 });
  await page.mouse.up();
  assert.equal(Buffer.from(await readMouse()).toString(), "\x1b[<0;2;2M\x1b[<0;2;2m");
  // Full-screen editors such as nano often do not enable mouse reporting. Wheel
  // input then uses cursor keys, with mode 1007 and Shift retaining local control.
  const wheel = (deltaY, shiftKey = false, deltaX = 0) => page.locator("[data-terminal]").evaluate((element, options) => {
    const rect = element.getBoundingClientRect();
    element.dispatchEvent(new WheelEvent("wheel", {
      ...options, clientX: rect.left + 1, clientY: rect.top + 1, bubbles: true, cancelable: true,
    }));
  }, { deltaY, shiftKey, deltaX });
  await resetMouse("\x1b[?1049h");
  await wheel(-1); await wheel(1); await wheel(0, false, 1); await wheel(0);
  assert.equal(Buffer.from(await readMouse()).toString(), "\x1b[A".repeat(3) + "\x1b[B".repeat(3));
  await page.evaluate(() => kineticaTerminalDemo.write("\x1b[?1h"));
  await wheel(-1); await wheel(1);
  assert.equal(Buffer.from(await readMouse()).toString(), "\x1bOA".repeat(3) + "\x1bOB".repeat(3));
  await page.evaluate(() => kineticaTerminalDemo.write("\x1b[?1007l"));
  await wheel(-1); await wheel(1);
  assert.deepEqual(await readMouse(), []);
  await page.evaluate(() => kineticaTerminalDemo.write("\x1b[?1007h"));
  await wheel(-1, true); await wheel(1, true);
  assert.deepEqual(await readMouse(), []);
  await page.evaluate(() => kineticaTerminalDemo.write("\x1b[?1000;1006h"));
  await wheel(-1); await wheel(1);
  assert.equal(Buffer.from(await readMouse()).toString(), "\x1b[<64;1;1M\x1b[<65;1;1M");
  await resetMouse("");
  await wheel(-1); await wheel(1);
  assert.deepEqual(await readMouse(), []);
  await page.evaluate(() => kineticaTerminalDemo.dispose());
  assert.deepEqual(errors, []);
  console.log("Browser terminal: WebGL/Canvas atomic frames, hold timeout, cursor shapes/blink, dynamic palette, glyph cache, context recovery, font resize, keypad/IME and Kitty input, OSC 52 clipboard, notification permissions/routing, progress states/timeout, binary mouse reports, alternate-screen wheel scrolling, pointer capture/chords, history, and disposal passed");
} finally { await browser.close(); }
