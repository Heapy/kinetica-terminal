// Real PTY -> bounded WebSocket -> common stream -> WebGL; input goes through actual DOM events.
// Submission/rAF timing is deliberately NOT reported as browser display presentation.
import assert from "node:assert/strict";
import { once } from "node:events";
import { createRequire } from "node:module";
import { randomBytes } from "node:crypto";
import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { mkdtemp, writeFile, mkdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve, join } from "node:path";

const require = createRequire(import.meta.url);
const playwrightPath = process.env.PLAYWRIGHT_IMPORT ?? require.resolve("playwright");
const { chromium } = await import(playwrightPath);
const { wsServer: WebSocketServer } = createRequire(playwrightPath)("playwright-core/lib/utilsBundle");
const baseUrl = process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173";
const token = randomBytes(32).toString("hex");
const directory = await mkdtemp(join(tmpdir(), "kinetica-render-"));
const reportDir = resolve("build/reports/terminal-renderer");
await mkdir(reportDir, { recursive: true });
const errors = [];
const peer = { receivedBytes: 0, consumed: 0, maxOutstanding: 0, inputCredit: 0, stopping: false, stderr: "" };
const server = new WebSocketServer({ host: "127.0.0.1", port: 0, maxPayload: 65536,
  verifyClient: ({ origin, req }) => origin === new URL(baseUrl).origin && req.url === `/${token}` });
await once(server, "listening");
server.on("connection", socket => {
  assert(!peer.child);
  peer.socket = socket;
  peer.child = spawn("python3", ["scripts/terminal-pty-fixture.py", directory, "120", "32"], { stdio: ["pipe", "pipe", "pipe"] });
  peer.exited = once(peer.child, "exit");
  peer.child.stderr.on("data", data => { peer.stderr = (peer.stderr + data).slice(-16384); });
  peer.child.stdin.on("error", error => { if (!peer.stopping) errors.push(String(error)); });
  const send = message => { if (!peer.child.stdin.destroyed) peer.child.stdin.write(JSON.stringify(message) + "\n"); };
  createInterface({ input: peer.child.stdout }).on("line", line => {
    try {
      const message = JSON.parse(line);
      if (message.output) {
        const data = Buffer.from(message.output, "base64");
        peer.receivedBytes += data.length;
        const outstanding = peer.receivedBytes - peer.consumed;
        assert(outstanding <= 1048576); peer.maxOutstanding = Math.max(peer.maxOutstanding, outstanding);
        if (socket.readyState === 1) socket.send(data);
      } else if (message.inputCredit) {
        peer.inputCredit += message.inputCredit; assert(peer.inputCredit <= 65536);
        if (socket.readyState === 1) socket.send(JSON.stringify({ inputCredit: message.inputCredit }));
      } else if (message.eof) {
        peer.exitCode = message.exit;
        if (socket.readyState === 1) socket.send(JSON.stringify({ eof: true }));
      }
    } catch (error) { errors.push(String(error)); socket.close(1011); }
  });
  socket.on("message", (data, binary) => {
    try {
      if (binary) {
        assert(data.length <= peer.inputCredit); peer.inputCredit -= data.length;
        send({ input: data.toString("base64") });
      } else {
        const message = JSON.parse(data.toString());
        if (message.ready) { assert(!peer.ready); peer.ready = true; send({ credit: 1048576 }); }
        else if (message.consumed) {
          assert(Number.isInteger(message.consumed) && message.consumed > 0 && message.consumed <= peer.receivedBytes - peer.consumed);
          peer.consumed += message.consumed; send({ credit: message.consumed });
        } else if (message.resize) send({ resize: message.resize });
        else throw Error("Unknown benchmark control");
      }
    } catch (error) { errors.push(String(error)); socket.close(1011); }
  });
  socket.on("close", () => { peer.stopping = true; peer.child.stdin.end(); });
});
const angle = process.env.KINETICA_ANGLE ?? (process.platform === "darwin" ? "metal" : "default");
let browser;
try {
  browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE,
    args: angle === "default" ? [] : [`--use-angle=${angle}`] });
  const page = await browser.newPage({ viewport: { width: 1200, height: 800 } });
  page.on("pageerror", error => errors.push(String(error)));
  await page.goto(`${baseUrl}/samples/browser-terminal/web/index.html?renderer=gpu&profile=1`);
  await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 0);
  await page.evaluate(() => {
    const root = document.querySelector("[data-terminal]");
    const context = document.createElement("canvas").getContext("2d");
    context.font = "14px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";
    root.style.flex = "none"; root.style.width = `${Math.ceil(context.measureText("M").width * 120)}px`;
    root.style.height = `${Math.ceil(14 * 1.3) * 32}px`;
  });
  await page.waitForFunction(() => kineticaTerminalDemo.columns() === 120 && kineticaTerminalDemo.rows() === 32);
  await page.locator("textarea").focus();
  const device = await page.evaluate(() => {
    const gl = document.querySelector("canvas").getContext("webgl2");
    const info = gl.getExtension("WEBGL_debug_renderer_info");
    return { renderer: info ? gl.getParameter(info.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER),
      gpuTimer: !!gl.getExtension("EXT_disjoint_timer_query_webgl2"), ratio: devicePixelRatio, userAgent: navigator.userAgent };
  });
  await page.evaluate(async url => {
    const api = kineticaTerminalDemo;
    const socket = new WebSocket(url); socket.binaryType = "arraybuffer";
    let inputCredit = 0;
    await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = () => reject(Error("WebSocket failed")); });
    socket.onmessage = event => {
      if (typeof event.data === "string") {
        const message = JSON.parse(event.data);
        if (message.inputCredit) inputCredit += message.inputCredit;
        if (message.eof) api.finishOutput();
      } else if (!api.receiveBytes(new Int8Array(event.data))) throw Error("PTY output credit exceeded");
    };
    api.connectTransport({
      write(bytes) {
        const count = Math.min(bytes.length, inputCredit, 16384, Math.max(0, 65536 - socket.bufferedAmount));
        if (count && socket.readyState === 1) { socket.send(bytes.subarray(0, count)); inputCredit -= count; return count; }
        return 0;
      },
      resize(columns, rows) { socket.send(JSON.stringify({ resize: [columns, rows] })); },
      consumed(bytes) { socket.send(JSON.stringify({ consumed: bytes })); },
      rejected() { throw Error("Input rejected"); }, failed(message) { throw Error(message); },
    });
    globalThis.closeBenchmark = () => { api.disposeTransport(); socket.close(); };
    socket.send(JSON.stringify({ ready: true }));
  }, `ws://127.0.0.1:${server.address().port}/${token}`);
  await page.waitForFunction(() => kineticaTerminalDemo.screen().includes("KINETICA>"));
  const script = resolve("scripts/terminal-render-load.py");
  const quote = value => "'" + value.replaceAll("'", "'\\''") + "'";
  await page.evaluate(command => kineticaTerminalDemo.sendInput(command), `exec /usr/bin/python3 ${quote(script)} --seconds 10 --mib-per-second 4\r`);
  await page.waitForFunction(() => kineticaTerminalDemo.screen().startsWith("READY"));
  await page.evaluate(() => {
    const frames = new Map(), inputs = new Map(), latency = new Map();
    const start = performance.now();
    const sample = globalThis.renderBenchmark = { frames, inputs, latency, start, nextInput: null, maxQueue: 0 };
    document.querySelector("textarea").addEventListener("beforeinput", () => {
      if (sample.nextInput != null && !inputs.has(sample.nextInput)) inputs.set(sample.nextInput, performance.now());
    });
    kineticaTerminalDemo.setRenderObserver({
      started(id, visibleText) {
        const now = performance.now();
        if (now < start + 2000) return;
        if (frames.size >= 20000) throw Error("Frame sample limit exceeded");
        const marker = /^INPUT:([0-9]{6})/.exec(visibleText());
        frames.set(id, { id, startedAt: now, input: marker ? Number(marker[1]) : null, measured: now < start + 8000 });
      },
      submitted(value) {
        const frame = frames.get(value.id); if (!frame) return;
        Object.assign(frame, value, { submittedAt: performance.now() });
        if (inputs.has(frame.input) && !latency.has(frame.input)) latency.set(frame.input, frame.submittedAt - inputs.get(frame.input));
        sample.maxQueue = Math.max(sample.maxQueue, kineticaTerminalDemo.transportState().queuedOutputBytes);
      },
      gpu(id, milliseconds) { const frame = frames.get(id); if (frame) frame.gpuMillis = milliseconds; },
    });
  });
  await page.waitForFunction(() => performance.now() >= renderBenchmark.start + 2000);
  let input = 0;
  while (await page.evaluate(() => performance.now() < renderBenchmark.start + 8000)) {
    input++;
    await page.evaluate(id => { renderBenchmark.nextInput = id; }, input);
    await page.keyboard.insertText(`M${String(input).padStart(6, "0")}`);
    await page.keyboard.press("Enter");
    await page.waitForTimeout(125);
  }
  await page.waitForFunction(() => kineticaTerminalDemo.transportState().outputFinished, null, { timeout: 20000 });
  await page.waitForFunction(() => renderBenchmark.latency.size === renderBenchmark.inputs.size);
  await page.waitForFunction(() => [...renderBenchmark.frames.values()].filter(f => f.measured && f.submittedAt).every(f => "gpuMillis" in f), null, { timeout: 6000 });
  const data = await page.evaluate(() => ({ frames: [...renderBenchmark.frames.values()], inputs: [...renderBenchmark.inputs],
    latency: [...renderBenchmark.latency.values()], maxQueue: renderBenchmark.maxQueue, transport: kineticaTerminalDemo.transportState() }));
  assert.deepEqual(errors, []); assert.equal(peer.exitCode, 0); assert.equal(peer.stderr, "");
  assert(data.inputs.length >= 20); assert.equal(data.latency.length, input);
  assert.equal(data.transport.queuedOutputBytes, 0); assert.equal(data.transport.pendingInputBytes, 0);
  const frames = data.frames.filter(f => f.measured && f.submittedAt);
  assert(frames.length > 30);
  const distribution = values => {
    assert(values.length && values.every(v => Number.isFinite(v) && v >= 0)); values.sort((a, b) => a - b);
    const p = fraction => values[Math.min(values.length - 1, Math.ceil(values.length * fraction) - 1)];
    return { count: values.length, min: values[0], p50: p(.5), p95: p(.95), p99: p(.99), max: values.at(-1) };
  };
  const gpu = frames.map(f => f.gpuMillis).filter(value => value != null);
  if (device.gpuTimer) assert(gpu.length > 30);
  const stages = Object.fromEntries(["acquireMillis", "layoutMillis", "atlasMillis", "batchMillis", "submitMillis", "cpuMillis"].map(key => [key, distribution(frames.map(f => f[key]))]));
  const report = { engine: "Kotlin/JS WebGL2", angle, ...device, columns: 120, rows: 32,
    producerMiBPerSecond: 4, producerSeconds: 10, warmupSeconds: 2, measureSeconds: 6,
    scope: "real PTY/WebSocket; DOM beforeinput to echo-frame submission; GPU durations exclude compositor/presentation",
    presentedFrames: null, inputToPresentedMillis: null,
    receivedBytes: peer.receivedBytes, maxOutstandingOutputBytes: peer.maxOutstanding, maxObservedQueuedOutputBytes: data.maxQueue,
    framesSubmitted: frames.length, gpuSamples: gpu.length, inputSamples: data.inputs.length,
    submissionsPerSecond: (frames.length - 1) * 1000 / (frames.at(-1).submittedAt - frames[0].submittedAt), ...stages,
    gpuMillis: gpu.length ? distribution(gpu) : null,
    submittedIntervalMillis: distribution(frames.slice(1).map((f, i) => f.submittedAt - frames[i].submittedAt)),
    inputToSubmittedMillis: distribution(data.latency), frames: data.frames, inputs: data.inputs };
  await page.screenshot({ path: join(reportDir, "browser.png") });
  await writeFile(join(reportDir, "browser.json"), JSON.stringify(report, null, 2) + "\n");
  const { frames: _, inputs: __, ...summary } = report; console.log(JSON.stringify(summary, null, 2));
  await page.evaluate(() => { closeBenchmark(); kineticaTerminalDemo.dispose(); });
} finally {
  await browser?.close();
  peer.stopping = true; peer.child?.stdin.end(); peer.socket?.terminate();
  if (peer.exited) {
    try { await Promise.race([peer.exited, new Promise((_, reject) => setTimeout(() => reject(Error("PTY cleanup timeout")), 5000).unref())]); }
    finally { peer.child.kill("SIGTERM"); }
  }
  await new Promise(resolve => server.close(resolve));
  await rm(directory, { recursive: true, force: true });
}
