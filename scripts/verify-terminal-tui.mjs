// Live applications behind a real PTY and credit-limited WebSocket. Test-only loopback peer.
import assert from "node:assert/strict";
import { once } from "node:events";
import { createRequire } from "node:module";
import { randomBytes, createHash } from "node:crypto";
import { spawn, execFile } from "node:child_process";
import { promisify } from "node:util";
import { createInterface } from "node:readline";
import { mkdtemp, writeFile, readFile, mkdir, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { resolve, join } from "node:path";

const run = promisify(execFile);
const require = createRequire(import.meta.url);
const playwrightPath = process.env.PLAYWRIGHT_IMPORT ?? require.resolve("playwright");
const { chromium } = await import(playwrightPath);
const { wsServer: WebSocketServer } = createRequire(playwrightPath)("playwright-core/lib/utilsBundle");
const baseUrl = process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173";
const tmux = process.env.KINETICA_TMUX ?? "/opt/homebrew/bin/tmux";
const nvim = process.env.KINETICA_NVIM ?? "nvim";
const shellQuote = value => "'" + value.replaceAll("'", "'\\''") + "'";
const versions = {};
for (const [name, executable, args] of [["vim", "/usr/bin/vim", ["--version"]], ["nvim", nvim, ["--version"]], ["less", "/usr/bin/less", ["--version"]], ["tmux", tmux, ["-V"]]])
  versions[name] = (await run(executable, args)).stdout.split("\n")[0];
const token = randomBytes(32).toString("hex");
const server = new WebSocketServer({ host: "127.0.0.1", port: 0, maxPayload: 64 * 1024,
  verifyClient: ({ origin, req }) => origin === new URL(baseUrl).origin && req.url === `/${token}` });
await once(server, "listening");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const page = await browser.newPage({ viewport: { width: 1200, height: 800 } });
const errors = [];
page.on("pageerror", error => errors.push(String(error)));
const reportDir = resolve("build/reports/terminal-tui");
await mkdir(reportDir, { recursive: true });
const records = [];
let current;
server.on("connection", socket => {
  const state = current;
  if (!state || state.socket) { socket.close(1008); return; }
  state.socket = socket;
  state.child = spawn("python3", ["scripts/terminal-pty-fixture.py", state.directory, "80", "24"], { stdio: ["pipe", "pipe", "pipe"] });
  state.exited = once(state.child, "exit");
  state.child.stderr.on("data", data => { state.stderr = (state.stderr + data).slice(-16384); });
  const sendControl = value => {
    if (!state.child.stdin.destroyed) state.child.stdin.write(JSON.stringify(value) + "\n");
  };
  state.child.stdin.on("error", error => { if (!state.stopping) errors.push(String(error)); });
  const lines = createInterface({ input: state.child.stdout });
  lines.on("line", line => {
    try {
      const message = JSON.parse(line);
      if (message.output) {
        const data = Buffer.from(message.output, "base64");
        state.bytes += data.length;
        assert(state.bytes <= 8 * 1024 * 1024, "Recording limit exceeded");
        state.events.push({ bytes: data.toString("hex") });
        state.sent += data.length;
        assert(state.sent - state.consumed <= 1024 * 1024);
        state.lastOutput = Date.now();
        if (socket.readyState === 1) socket.send(data);
      } else if (message.inputCredit) {
        state.inputCredit += message.inputCredit;
        assert(state.inputCredit <= 64 * 1024);
        if (socket.readyState === 1) socket.send(JSON.stringify({ inputCredit: message.inputCredit }));
      } else if (message.eof) {
        state.exitCode = message.exit;
        if (socket.readyState === 1) socket.send(JSON.stringify({ eof: true }));
      }
    } catch (error) { errors.push(String(error)); socket.close(1011); }
  });
  socket.on("message", (data, binary) => {
    try {
      if (binary) {
        assert(data.length <= state.inputCredit, "Uncredited PTY input");
        state.inputCredit -= data.length;
        sendControl({ input: data.toString("base64") });
      } else {
        const message = JSON.parse(data.toString());
        if (message.ready) {
          assert(!state.ready); state.ready = true;
          sendControl({ credit: 1024 * 1024 });
        } else if (message.consumed) {
          assert(Number.isInteger(message.consumed) && message.consumed > 0 && message.consumed <= state.sent - state.consumed);
          state.consumed += message.consumed; sendControl({ credit: message.consumed });
        } else if (message.resize) {
          state.events.push({ resize: message.resize }); sendControl({ resize: message.resize });
        } else throw Error("Unknown fixture control");
      }
    } catch (error) { errors.push(String(error)); socket.close(1011); }
  });
  socket.on("close", () => { state.stopping = true; state.child.stdin.end(); });
});

async function until(condition, label) {
  const deadline = Date.now() + 15000;
  while (Date.now() < deadline && !errors.length) {
    if (await condition()) return;
    await new Promise(resolve => setTimeout(resolve, 20));
  }
  throw Error(`${label}\n${JSON.stringify(errors)}\n${await page.evaluate(() => kineticaTerminalDemo.screen())}`);
}
const state = () => page.evaluate(() => ({ text: kineticaTerminalDemo.screen(), alternate: kineticaTerminalDemo.alternateScreen(),
  row: kineticaTerminalDemo.cursorRow(), column: kineticaTerminalDemo.cursorColumn(), mouse: kineticaTerminalDemo.mouseTracking() }));
const send = text => page.evaluate(text => kineticaTerminalDemo.sendInput(text), text);
const key = name => page.keyboard.press(name);
const paste = text => page.locator("textarea").evaluate((element, text) => {
  const data = new DataTransfer(); data.setData("text/plain", text);
  element.dispatchEvent(new ClipboardEvent("paste", { clipboardData: data, bubbles: true, cancelable: true }));
}, text);
async function waitScreen(label, predicate) { await until(async () => predicate(await state()), label); }
const prompt = screen => !screen.alternate && screen.text.split("\n")[screen.row].startsWith("KINETICA>");
async function resize(columns, rows) {
  await page.evaluate(({ columns, rows }) => {
    const context = document.createElement("canvas").getContext("2d");
    context.font = "14px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";
    const root = document.querySelector("[data-terminal]");
    root.style.flex = "none"; root.style.width = `${Math.ceil(columns * context.measureText("M").width)}px`;
    root.style.height = `${rows * Math.ceil(14 * 1.3)}px`;
  }, { columns, rows });
  await page.waitForFunction(({ columns, rows }) => kineticaTerminalDemo.columns() === columns && kineticaTerminalDemo.rows() === rows, { columns, rows });
}
async function snapshot(label) {
  await until(() => current.sent === current.consumed && Date.now() - current.lastOutput > 80, "PTY settles");
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  current.snapshots.push({ label, event: current.events.length, ...await state() });
  await page.screenshot({ path: join(reportDir, `${current.name}-${label}.png`) });
}
async function open(name, files) {
  current = { name, directory: await mkdtemp(join(tmpdir(), "kinetica-tui-")), bytes: 0, sent: 0, consumed: 0, inputCredit: 0,
    lastOutput: 0, events: [], snapshots: [], stderr: "", stopping: false };
  for (const [name, content] of Object.entries(files)) await writeFile(join(current.directory, name), content);
  await page.goto(`${baseUrl}/samples/browser-terminal/web/index.html?renderer=gpu`);
  await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 0);
  await resize(80, 24);
  await page.locator("textarea").focus();
  await page.evaluate(async url => {
    const socket = new WebSocket(url); socket.binaryType = "arraybuffer";
    let inputCredit = 0;
    const api = kineticaTerminalDemo;
    await new Promise((resolve, reject) => {
      socket.onerror = () => reject(Error("PTY WebSocket failed"));
      socket.onopen = resolve;
    });
    socket.onmessage = event => {
      if (typeof event.data === "string") {
        const message = JSON.parse(event.data);
        if (message.inputCredit) inputCredit += message.inputCredit;
        if (message.eof) api.finishOutput();
      } else if (!api.receiveBytes(new Int8Array(event.data))) throw Error("Uncredited PTY output");
    };
    api.connectTransport({
      write(bytes) {
        if (socket.readyState !== WebSocket.OPEN) return 0;
        const count = Math.min(bytes.length, inputCredit, 16384, Math.max(0, 65536 - socket.bufferedAmount));
        if (count) { socket.send(bytes.subarray(0, count)); inputCredit -= count; }
        return count;
      },
      resize(columns, rows) { socket.send(JSON.stringify({ resize: [columns, rows] })); },
      consumed(bytes) { socket.send(JSON.stringify({ consumed: bytes })); },
      rejected() { throw Error("TUI input rejected"); }, failed(message) { throw Error(message); },
    });
    globalThis.closeTuiSocket = () => { api.disposeTransport(); socket.close(); };
    socket.send(JSON.stringify({ ready: true }));
  }, `ws://127.0.0.1:${server.address().port}/${token}`);
  await waitScreen("initial shell prompt", prompt);
}
async function close() {
  const closing = current;
  if (!closing) return;
  try {
    await run(tmux, ["-S", join(closing.directory, "tmux.sock"), "kill-server"]).catch(() => {});
    await page.evaluate(() => globalThis.closeTuiSocket?.());
    if (closing.child) {
      closing.child.stdin.end();
      await Promise.race([closing.exited, new Promise((_, reject) => setTimeout(() => reject(Error("PTY fixture did not close")), 5000).unref())]);
    }
    assert.equal(closing.stderr, "");
    const recording = { name: closing.name, columns: 80, rows: 24, versions, events: closing.events,
      snapshots: closing.snapshots, paneResize: closing.paneResize };
    const serialized = JSON.stringify(recording);
    await writeFile(join(reportDir, `${closing.name}.json`), serialized + "\n");
    records.push({ name: closing.name, bytes: closing.bytes, checkpoints: closing.snapshots.length,
      sha256: createHash("sha256").update(serialized).digest("hex") });
  } finally {
    closing.child?.kill("SIGTERM");
    await rm(closing.directory, { recursive: true, force: true });
    current = null;
  }
}

try {
  await open("shell", {});
  await send("this command must not run"); await key("Control+u");
  await paste("printf '%s%s\\n' SHELL_ 'OK界😀'"); await key("Enter");
  await waitScreen("Unicode shell command", s => s.text.includes("SHELL_OK界😀") && prompt(s));
  await send("/bin/sh -c 'printf \"SLEEP_%s\\n\" READY; exec /bin/sleep 60'\r");
  await waitScreen("foreground command ready", s => s.text.includes("SLEEP_READY") && !prompt(s));
  await key("Control+c"); await waitScreen("interrupt", prompt);
  await resize(100, 32); await send("printf 'SIZE:%s\\n' \"$(stty size)\"\r");
  await waitScreen("shell resize", s => s.text.includes("SIZE:32 100") && prompt(s));
  await snapshot("resized"); await close();

  const initial = Array.from({ length: 120 }, (_, i) => `ROW ${String(i + 1).padStart(3, "0")}`).join("\n") + "\n";
  await open("vim", { "edit.txt": initial });
  await send("/usr/bin/vim -Nu NONE -n -i NONE --noplugin -N -c 'set noshowmode noruler laststatus=2 statusline=KINETICA_VIM mouse=a ttymouse=sgr' edit.txt\r");
  await waitScreen("Vim ready", s => s.alternate && s.text.split("\n")[0] === "ROW 001" && s.mouse !== 0);
  await send("gg0i"); await paste("EDIT界😀\n"); await key("Escape"); await send(":w\r");
  await until(async () => await readFile(join(current.directory, "edit.txt"), "utf8") === `EDIT界😀\n${initial}`, "Vim saved Unicode paste");
  await send("40Gzt"); await waitScreen("Vim scroll", s => s.text.split("\n")[0] === "ROW 039");
  const point = await page.evaluate(() => {
    const rect = document.querySelector("canvas").getBoundingClientRect();
    const context = document.createElement("canvas").getContext("2d"); context.font = "14px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";
    return { x: rect.left + 2.5 * context.measureText("M").width, y: rect.top + 2.5 * Math.ceil(14 * 1.3) };
  });
  await page.mouse.click(point.x, point.y); await send("0iMOUSE:"); await key("Escape"); await send(":w\r");
  await until(async () => (await readFile(join(current.directory, "edit.txt"), "utf8")).includes("\nMOUSE:ROW 041\n"), "Vim mouse targets correct row");
  await resize(100, 32); await key("Control+l");
  await waitScreen("Vim resize", s => s.text.split("\n")[30].includes("KINETICA_VIM"));
  await snapshot("edited"); await send(":q\r"); await waitScreen("Vim restores shell", prompt);
  assert.equal((await state()).mouse, 0); await snapshot("shell"); await close();

  await open("nvim", { "edit.txt": initial });
  await send(`${shellQuote(nvim)} -u NONE -n -i NONE --noplugin -c 'set noshowmode noruler laststatus=2 statusline=KINETICA_NVIM mouse=a' edit.txt\r`);
  await waitScreen("Neovim ready", s => s.alternate && s.text.split("\n")[0] === "ROW 001" && s.mouse !== 0 && s.text.includes("KINETICA_NVIM"));
  await snapshot("ready");
  await send("gg0i"); await paste("EDIT界😀é\n"); await key("Escape"); await send(":w\r");
  await until(async () => await readFile(join(current.directory, "edit.txt"), "utf8") === `EDIT界😀é\n${initial}`, "Neovim saves Unicode paste");
  await send("40Gzt"); await waitScreen("Neovim scroll", s => s.text.split("\n")[0] === "ROW 039");
  await page.mouse.click(point.x, point.y); await send("0iMOUSE:"); await key("Escape"); await send(":w\r");
  await until(async () => (await readFile(join(current.directory, "edit.txt"), "utf8")).includes("\nMOUSE:ROW 041\n"), "Neovim mouse targets correct row");
  await snapshot("edited");
  await page.evaluate(() => {
    const gl = document.querySelector("canvas").getContext("webgl2");
    const draw = gl.drawArraysInstanced.bind(gl);
    gl.drawArraysInstanced = (...args) => {
      draw(...args);
      const pixel = new Uint8Array(4);
      gl.readPixels(0, gl.drawingBufferHeight - 17, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixel);
      globalThis.nvimUndercurlPixel = [...pixel];
    };
  });
  await send(":set termguicolors | highlight KineticaProbe gui=undercurl guisp=#12ee34 | call matchadd('KineticaProbe', 'ROW 039')\r");
  await page.waitForFunction(() => JSON.stringify(globalThis.nvimUndercurlPixel) === "[18,238,52,255]");
  await snapshot("highlighted");
  await send("/ROW 100\r"); await waitScreen("Neovim search", s => s.text.split("\n")[s.row] === "ROW 100");
  await resize(100, 32); await key("Control+l");
  await waitScreen("Neovim resize", s => s.text.split("\n")[30].includes("KINETICA_NVIM"));
  const nvimRow = (await state()).row;
  await key("ArrowUp"); await waitScreen("Neovim arrow navigation", s => s.row === nvimRow - 1 && s.text.split("\n")[s.row] === "ROW 099");
  await snapshot("resized");
  await send(":q\r"); await waitScreen("Neovim restores shell", prompt);
  assert.equal((await state()).mouse, 0);
  assert.equal(await page.evaluate(() => kineticaTerminalDemo.synchronizedOutput()), false);
  await send("printf '%s%s\\n' AFTER_ NVIM\r"); await waitScreen("shell after Neovim", s => s.text.includes("AFTER_NVIM") && prompt(s));
  await snapshot("shell"); await close();

  await open("less", { "pages.txt": Array.from({ length: 180 }, (_, i) => `LINE ${String(i + 1).padStart(3, "0")} — 界`).join("\n") + "\n" });
  await send("/usr/bin/less -R -P KINETICA_LESS pages.txt\r");
  await waitScreen("less ready", s => s.alternate && s.text.split("\n")[0] === "LINE 001 — 界");
  await send("/LINE 100\r"); await waitScreen("less search", s => s.text.includes("LINE 100 — 界") && !s.text.includes("LINE 001 — 界"));
  await key("PageDown"); await waitScreen("less page down", s => s.text.includes("LINE 130 — 界"));
  await resize(100, 32); await send("g");
  await waitScreen("less resize", s => s.text.split("\n")[0] === "LINE 001 — 界" && s.text.split("\n")[31].includes("KINETICA_LESS"));
  await snapshot("resized"); await send("q"); await waitScreen("less restores shell", prompt); await close();

  await open("tmux", { "tmux.conf": [
    "set -g default-command \"/bin/zsh -d -f -i\"",
    "set -g status-left '[kinetica] '", "set -g status-right ''",
    "set -g automatic-rename off", "set -g set-titles off", "set -g allow-rename off",
  ].join("\n") + "\n" });
  await send(`${shellQuote(tmux)} -S "$PWD/tmux.sock" -f tmux.conf new-session -s kinetica /bin/zsh -d -f -i\r`);
  await waitScreen("tmux ready", s => s.alternate && s.text.includes("kinetica") && s.text.includes("KINETICA>"));
  await send("printf '%s%s\\n' PANE_ ONE\r"); await waitScreen("first pane", s => s.text.includes("PANE_ONE"));
  await key("Control+b"); await send("%");
  await send("printf '%s%s\\n' PANE_ TWO\r"); await waitScreen("split pane", s => s.text.includes("PANE_ONE") && s.text.includes("PANE_TWO"));
  await resize(100, 32);
  // Outer drawing precedes the inner PTY's ioctl: tmux 3.7c rate-limits pane resizes
  // with a 250ms timer (server-client.c:server_client_check_pane_resize).
  // Check the kernel size, not a sleep or only tmux's already-updated layout model.
  await waitScreen("tmux redraw acknowledges resize", s => s.text.split("\n")[31].includes("[kinetica]"));
  const resizeStarted = Date.now();
  const paneSizes = [];
  await until(async () => {
    const { stdout } = await run(tmux, ["-S", join(current.directory, "tmux.sock"), "display-message", "-p",
      "#{pane_width} #{pane_height} #{pane_tty}"]);
    const [columns, rows, tty] = stdout.trim().split(" ");
    const kernelSize = (await run("/bin/stty", ["-f", tty, "size"])).stdout.trim();
    paneSizes.push({ elapsed: Date.now() - resizeStarted, columns, rows, kernelSize });
    return columns === "49" && rows === "31" && kernelSize === "31 49";
  }, "tmux inner PTY applies resize");
  current.paneResize = paneSizes;
  await send("printf 'SIZE:%s\\n' \"$(stty size)\"\r");
  await waitScreen("tmux resize reaches active pane", s => /SIZE:31 (49|50)/.test(s.text));
  await snapshot("split");
  await key("Control+b"); await send("d"); await waitScreen("tmux detach restores shell", prompt);
  await send(`${shellQuote(tmux)} -S "$PWD/tmux.sock" kill-server\r`);
  await send("printf '%s%s\\n' AFTER_ TMUX\r"); await waitScreen("shell after tmux", s => s.text.includes("AFTER_TMUX") && prompt(s));
  await snapshot("shell"); await close();
  assert.deepEqual(errors, []);
  const report = { scope: "live zsh/Vim/Neovim/less/tmux via browser WebGL, real PTY and bounded WebSocket", versions, records, productionReady: false };
  await writeFile(join(reportDir, "report.json"), JSON.stringify(report, null, 2) + "\n");
  console.log(JSON.stringify(report, null, 2));
} finally {
  try { await close(); } finally {
    await browser.close();
    for (const socket of server.clients) socket.terminate();
    await new Promise(resolve => server.close(resolve));
  }
}
