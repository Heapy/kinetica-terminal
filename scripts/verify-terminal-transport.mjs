// Real loopback WebSocket, with explicit byte credits in both directions.
// This fixture protocol belongs to the test; TerminalByteTransport does not impose wire framing.
import assert from "node:assert/strict";
import { once } from "node:events";
import { createRequire } from "node:module";
const localRequire = createRequire(import.meta.url);
const playwrightPath = process.env.PLAYWRIGHT_IMPORT ?? localRequire.resolve("playwright");
const { chromium } = await import(playwrightPath);
const { wsServer: WebSocketServer } = createRequire(playwrightPath)("playwright-core/lib/utilsBundle");
const WINDOW = 1024 * 1024;
const INPUT_WINDOW = 64 * 1024;
const query = "\x1b]4;" + Array(1023).fill("0;?").join(";") + "\x1b\\";
const replies = Buffer.from("\x1b]4;0;rgb:0000/0000/0000\x1b\\".repeat(1023 * 512));
const source = Buffer.concat([Buffer.from(query.repeat(512) + "\r\nFLOW_OK界😀"), Buffer.from([0xf0, 0x9f])]);
const peers = new Map();
const serverErrors = [];
const server = new WebSocketServer({ host: "127.0.0.1", port: 0, maxPayload: 64 * 1024 });
await once(server, "listening");
server.on("connection", (socket, request) => {
  const scenario = request.url.slice(1);
  const state = { socket, sent: 0, consumed: 0, maxOutstanding: 0, inputs: [], inputBytes: 0, sizes: [], eof: false };
  peers.set(scenario, state);
  function produce() {
    while (state.sent < source.length && state.sent - state.consumed < WINDOW) {
      const size = Math.min(16 * 1024, source.length - state.sent, WINDOW - (state.sent - state.consumed));
      socket.send(source.subarray(state.sent, state.sent + size));
      state.sent += size;
      state.maxOutstanding = Math.max(state.maxOutstanding, state.sent - state.consumed);
    }
    if (state.consumed === source.length && !state.eof) {
      state.eof = true; socket.send(JSON.stringify({ eof: true }));
    }
  }
  socket.on("message", (data, binary) => {
    try {
      if (binary) {
        state.inputs.push(Buffer.from(data)); state.inputBytes += data.length;
        socket.send(JSON.stringify({ inputCredit: data.length }));
        return;
      }
      const message = JSON.parse(data.toString());
      if (message.ready) {
        assert.equal(message.outputWindow, WINDOW);
        if (scenario === "overflow") socket.send(Buffer.alloc(WINDOW + 1, 120));
        else produce();
      }
      if (message.resize) state.sizes.push(message.resize);
      if (message.consumed) {
        assert(Number.isInteger(message.consumed) && message.consumed > 0);
        assert(message.consumed <= state.sent - state.consumed, "credits exceeded produced output");
        state.consumed += message.consumed;
        produce();
      }
    } catch (error) { serverErrors.push(String(error)); socket.close(1011, "fixture failed"); }
  });
});
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const page = await browser.newPage({ viewport: { width: 1100, height: 720 } });
const pageErrors = [];
page.on("pageerror", error => pageErrors.push(String(error)));
const baseUrl = process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173";
async function open(scenario) {
  await page.goto(`${baseUrl}/samples/browser-terminal/web/index.html?renderer=gpu`);
  await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 80);
  await page.evaluate(async ({ url, windowSize }) => {
    const api = kineticaTerminalDemo;
    const socket = new WebSocket(url); socket.binaryType = "arraybuffer";
    const probe = globalThis.transportProbe = {
      inputCredit: 0, consumed: 0, received: 0, rejected: 0, overflows: 0, errors: [],
      frames: 0, ticks: 0, maxOutputQueue: 0, maxInputQueue: 0, maxSocketBuffered: 0,
      stopped: false, forceError: false, pendingCredits: 0, latestResize: null,
    };
    let controlTimer = null;
    let frame = null;
    const heartbeat = setInterval(() => {
      probe.ticks++;
      const state = api.transportState();
      probe.maxOutputQueue = Math.max(probe.maxOutputQueue, state.queuedOutputBytes);
      probe.maxInputQueue = Math.max(probe.maxInputQueue, state.pendingInputBytes);
      probe.maxSocketBuffered = Math.max(probe.maxSocketBuffered, socket.bufferedAmount);
    }, 5);
    const animate = () => { probe.frames++; frame = requestAnimationFrame(animate); };
    frame = requestAnimationFrame(animate);
    const stop = () => {
      if (probe.stopped) return;
      probe.stopped = true;
      clearInterval(heartbeat); cancelAnimationFrame(frame);
      if (controlTimer !== null) clearTimeout(controlTimer);
      api.disposeTransport();
    };
    function controls() {
      controlTimer = null;
      if (probe.stopped || socket.readyState !== WebSocket.OPEN) return;
      if (socket.bufferedAmount > 32 * 1024) { controlTimer = setTimeout(controls, 8); return; }
      const message = {};
      if (probe.pendingCredits) { message.consumed = probe.pendingCredits; probe.pendingCredits = 0; }
      if (probe.latestResize) { message.resize = probe.latestResize; probe.latestResize = null; }
      if (Object.keys(message).length) socket.send(JSON.stringify(message));
    }
    function scheduleControls() { if (controlTimer === null) controlTimer = setTimeout(controls, 0); }
    api.connectTransport({
      write(bytes) {
        if (probe.forceError) throw new Error("writer failure");
        if (socket.readyState !== WebSocket.OPEN) return 0;
        const count = Math.min(bytes.byteLength, probe.inputCredit, Math.max(0, 64 * 1024 - socket.bufferedAmount));
        if (!count) return 0;
        socket.send(bytes.subarray(0, count)); probe.inputCredit -= count;
        return count;
      },
      resize(columns, rows) { probe.latestResize = [columns, rows]; scheduleControls(); },
      consumed(bytes) { probe.consumed += bytes; probe.pendingCredits += bytes; scheduleControls(); },
      rejected() { probe.rejected++; },
      failed(message) { probe.errors.push(message); stop(); socket.close(4001, "transport failed"); },
    });
    socket.onmessage = event => {
      if (probe.stopped) return;
      if (typeof event.data === "string") {
        const message = JSON.parse(event.data);
        if (message.inputCredit) probe.inputCredit += message.inputCredit;
        if (message.eof) api.finishOutput();
      } else {
        const bytes = new Int8Array(event.data);
        probe.received += bytes.byteLength;
        if (!api.receiveBytes(bytes)) {
          probe.overflows++; stop(); socket.close(4009, "terminal output window exceeded");
        }
      }
    };
    socket.onclose = stop;
    await new Promise((resolve, reject) => {
      socket.onopen = () => {
        socket.send(JSON.stringify({ ready: true, outputWindow: windowSize }));
        scheduleControls(); resolve();
      };
      socket.onerror = () => reject(new Error("WebSocket connection failed"));
    });
  }, { url: `ws://127.0.0.1:${server.address().port}/${scenario}`, windowSize: WINDOW });
}
try {
  await open("flow");
  await page.waitForFunction(() => kineticaTerminalDemo.transportState().paused, null, { timeout: 15000 });
  const paused = await page.evaluate(() => ({ ...kineticaTerminalDemo.transportState(), frames: transportProbe.frames, ticks: transportProbe.ticks }));
  assert(paused.pendingInputBytes > WINDOW - INPUT_WINDOW);
  assert(paused.queuedOutputBytes > 0 && paused.queuedOutputBytes <= WINDOW);
  const prefixBytes = paused.pendingInputBytes;
  const typed = "USER界😀";
  await page.locator("textarea").evaluate((element, text) => {
    element.value = text;
    element.dispatchEvent(new InputEvent("input", { data: text, inputType: "insertText", bubbles: true }));
  }, typed);
  await page.evaluate(() => kineticaTerminalDemo.sendInput("x".repeat(2 * 1024 * 1024)));
  assert.equal(await page.evaluate(() => transportProbe.rejected), 1);
  await page.waitForFunction(before => transportProbe.frames >= before.frames + 3 && transportProbe.ticks > before.ticks, paused);
  const peer = peers.get("flow");
  assert.equal(peer.inputBytes, 0, "no application input without peer credits");
  assert(peer.sent - peer.consumed <= WINDOW);
  peer.socket.send(JSON.stringify({ inputCredit: INPUT_WINDOW }));
  await page.waitForFunction(() => {
    const state = kineticaTerminalDemo.transportState();
    return state.outputFinished && state.pendingInputBytes === 0 && !state.disposed;
  }, null, { timeout: 30000 });
  // Receiving the last input-credit acknowledgment proves the server received the final frame.
  await page.waitForFunction(windowSize => transportProbe.inputCredit === windowSize, INPUT_WINDOW);
  const expected = Buffer.concat([replies.subarray(0, prefixBytes), Buffer.from(typed), replies.subarray(prefixBytes)]);
  assert.deepEqual(Buffer.concat(peer.inputs), expected);
  assert.equal(peer.consumed, source.length);
  assert.equal(peer.maxOutstanding, WINDOW);
  assert.match(await page.evaluate(() => kineticaTerminalDemo.screen()), /FLOW_OK界😀�/);
  const result = await page.evaluate(() => ({
    ...kineticaTerminalDemo.transportState(),
    received: transportProbe.received, consumed: transportProbe.consumed,
    frames: transportProbe.frames, ticks: transportProbe.ticks,
    maxOutputQueue: transportProbe.maxOutputQueue, maxInputQueue: transportProbe.maxInputQueue,
    maxSocketBuffered: transportProbe.maxSocketBuffered, errors: transportProbe.errors,
  }));
  assert(result.maxOutputQueue <= WINDOW && result.maxInputQueue <= WINDOW);
  assert.deepEqual(result.errors, []);
  // A JavaScript transport exception must release Kotlin queues and timers, not escape as pageerror.
  await page.evaluate(() => { transportProbe.forceError = true; kineticaTerminalDemo.sendInput("fail"); });
  await page.waitForFunction(() => kineticaTerminalDemo.transportState().disposed);
  assert.deepEqual(await page.evaluate(() => transportProbe.errors), ["writer failure"]);
  assert.equal(await page.evaluate(() => kineticaTerminalDemo.transportState().pendingInputBytes), 0);
  await open("overflow");
  await page.waitForFunction(() => transportProbe.overflows === 1);
  assert.equal(await page.evaluate(() => kineticaTerminalDemo.transportState().queuedOutputBytes), 0);
  assert.equal(await page.evaluate(() => transportProbe.consumed), 0);
  assert.equal(await page.evaluate(() => kineticaTerminalDemo.screen().trim()), "");
  assert.deepEqual(serverErrors, []);
  assert.deepEqual(pageErrors, []);
  console.log(JSON.stringify({ scope: "real WebSocket byte credits, UI progress, byte-exact replies/input, EOF and failures", ...result }, null, 2));
} finally {
  await browser.close();
  for (const socket of server.clients) socket.terminate();
  await new Promise(resolve => server.close(resolve));
}
