import assert from "node:assert/strict";

const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const page = await browser.newPage({ viewport: { width: 900, height: 600 } });
const errors = [];
page.on("pageerror", error => errors.push(String(error)));
const osc = value => `\x1b]5522;${value}\x07`;
const b64 = value => Buffer.from(value).toString("base64");
const read = (types, metadata = "") => osc(`type=read${metadata};${b64(types)}`);
const write = (contents, metadata = "") => osc(`type=write${metadata}`) + contents.map(([mime, bytes]) =>
  osc(`type=wdata:mime=${b64(mime)};${b64(bytes)}`)).join("") + osc("type=wdata");
const status = (operation, value) => osc(`type=${operation}:status=${value}`);
const readReply = contents => status("read", "OK") + contents.map(([mime, bytes]) => {
  const data = Buffer.from(bytes);
  let result = "";
  for (let offset = 0; offset < data.length; offset += 4096)
    result += osc(`type=read:status=DATA:mime=${b64(mime)};${b64(data.subarray(offset, offset + 4096))}`);
  return result;
}).join("") + status("read", "DONE");

async function exchange(request) {
  await page.evaluate(async request => {
    mimeReplies.length = 0;
    const bytes = new TextEncoder().encode(request);
    for (let offset = 0; offset < bytes.length; offset += 65536) {
      const part = bytes.subarray(offset, offset + 65536);
      while (!kineticaTerminalDemo.receiveBytes(part)) {
        if (kineticaTerminalDemo.transportState().disposed) throw new Error("Output refused");
        await new Promise(resolve => setTimeout(resolve, 0));
      }
    }
  }, request);
  await page.waitForFunction(() => mimeReplies.length > 0 && !kineticaTerminalDemo.transportState().paused &&
    kineticaTerminalDemo.transportState().queuedOutputBytes === 0 && kineticaTerminalDemo.transportState().pendingInputBytes === 0);
  return Buffer.from(await page.evaluate(() => mimeReplies)).toString();
}

try {
  await page.context().grantPermissions(["clipboard-read", "clipboard-write"]);
  const url = `${process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173"}/samples/browser-terminal/web/index.html`;
  await page.goto(`${url}?renderer=software`);
  await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 50);
  await page.evaluate(() => {
    globalThis.mimeReplies = [];
    kineticaTerminalDemo.connectTransport({
      write(bytes) { const size = Math.min(bytes.length, 16384); for (let i = 0; i < size; i++) mimeReplies.push(bytes[i]); return size; },
      resize() {}, consumed() {}, rejected() { throw new Error("MIME input rejected"); },
      failed(message) { throw new Error(message); },
    });
  });

  // Reads require a user paste grant or a separate host decision.
  assert.equal(await exchange(read("text/plain")), status("read", "EPERM"));
  const binary = Buffer.from(Array.from({ length: 8193 }, (_, index) => index % 251));
  const png = Buffer.from(await page.evaluate(async () => {
    const canvas = document.createElement("canvas"); canvas.width = 2; canvas.height = 2;
    const context = canvas.getContext("2d"); context.fillStyle = "red"; context.fillRect(0, 0, 2, 2);
    const blob = await new Promise(resolve => canvas.toBlob(resolve, "image/png"));
    return Array.from(new Uint8Array(await blob.arrayBuffer()));
  }));
  const formats = [["text/plain", "hello 世界\nsecond line"], ["text/html", "<b>hello 世界</b>"],
    ["image/png", png], ["application/x-kinetica-binary", binary]];
  assert.equal(await exchange(write(formats)), status("write", "DONE"));
  const native = await page.evaluate(async () => {
    const items = await navigator.clipboard.read();
    return Promise.all(items[0].types.map(async type => [type, Array.from(new Uint8Array(await (await items[0].getType(type)).arrayBuffer()))]));
  });
  const byMime = new Map(native.map(([type, bytes]) => [type.replace(/^web /, ""), Buffer.from(bytes)]));
  assert.equal(byMime.get("text/plain").toString(), formats[0][1]);
  assert.match(byMime.get("text/html").toString(), /<b>hello 世界<\/b>/);
  assert.deepEqual(byMime.get("application/x-kinetica-binary"), binary);
  assert.equal(byMime.get("image/png").subarray(1, 4).toString(), "PNG");
  // A real trusted paste must send metadata and a one-use password, without text insertion.
  await page.evaluate(() => {
    kineticaTerminalDemo.mimeClipboardPolicy("paste");
    kineticaTerminalDemo.receiveBytes(new TextEncoder().encode("\x1b[?5522h\x1b[?2004h"));
  });
  assert.equal(await exchange("\x1b[?5522$p"), "\x1b[?5522;1$y");
  await page.evaluate(() => { mimeReplies.length = 0; });
  await page.locator("textarea").focus();
  await page.keyboard.press(process.platform === "darwin" ? "Meta+v" : "Control+v");
  await page.waitForFunction(() => new TextDecoder().decode(new Uint8Array(mimeReplies)).includes("status=DONE"));
  const pasteEvent = Buffer.from(await page.evaluate(() => mimeReplies)).toString();
  const password = pasteEvent.match(/:pw=([^\x1b\x07]+)/)?.[1];
  assert.ok(password, "Trusted paste must emit a password");
  assert.equal(Buffer.from(password, "base64").length, 22);
  assert.ok(!pasteEvent.includes("\x1b[200~"));
  assert.ok(!pasteEvent.includes(b64(formats[0][1])));
  assert.ok(pasteEvent.includes(b64([...byMime.keys()].join(" ") + "\n")));
  const granted = read("application/x-kinetica-binary", `:name=${b64("Paste event")}:pw=${password}`);
  assert.equal(await exchange(granted), readReply([["application/x-kinetica-binary", binary]]));
  assert.equal(await exchange(granted), status("read", "EPERM"));
  // Script-created DOM events stay ordinary text and cannot create a clipboard grant.
  await page.evaluate(() => {
    mimeReplies.length = 0;
    const data = new DataTransfer(); data.setData("text/plain", "synthetic");
    document.querySelector("textarea").dispatchEvent(new ClipboardEvent("paste", {
      clipboardData: data, bubbles: true, cancelable: true,
    }));
  });
  await page.waitForFunction(() => mimeReplies.length > 0 && kineticaTerminalDemo.transportState().pendingInputBytes === 0);
  assert.equal(Buffer.from(await page.evaluate(() => mimeReplies)).toString(), "\x1b[200~synthetic\x1b[201~");
  assert.equal(await exchange("\x1b[?5522l\x1b[?2004l\x1b[?5522$p"), "\x1b[?5522;2$y");
  await page.evaluate(() => kineticaTerminalDemo.mimeClipboardPolicy("allow"));
  const listing = [...byMime.keys()].join(" ") + "\n";
  assert.equal(await exchange(read(". application/x-kinetica-binary text/plain")),
    readReply([[".", listing], ["application/x-kinetica-binary", binary], formats[0]]));
  // HTML and PNG may be sanitized/re-encoded by the browser. Protocol replies must contain
  // exactly the bytes its Clipboard API exposes, with no additional terminal conversion.
  assert.equal(await exchange(read("text/html image/png")), readReply([["text/html", byMime.get("text/html")], ["image/png", byMime.get("image/png")]]));
  assert.equal(await exchange(read("application/x-missing")), readReply([]));
  assert.equal(await exchange(write([["text/plain", Buffer.from([255])]])), status("write", "EINVAL"));
  assert.equal(await exchange(write([])), status("write", "ENOSYS"));
  assert.equal(await page.evaluate(() => navigator.clipboard.readText()), formats[0][1]);
  assert.equal(await exchange(read("text/plain", ":loc=primary")), status("read", "ENOSYS"));
  assert.equal(await exchange(write([["text/plain", "no"]], ":loc=primary")), status("write", "ENOSYS"));

  // A mixed write/read stream must wait for the async commit and its delivery receipt.
  const chained = write([["text/plain", "ordered"]]) + read("text/plain");
  assert.equal(await exchange(chained), status("write", "DONE") + readReply([["text/plain", "ordered"]]));
  await page.evaluate(() => kineticaTerminalDemo.mimeClipboardPolicy("deny"));
  assert.equal(await exchange(write([["text/plain", "denied"]]) + read("text/plain")), status("write", "EPERM") + status("read", "EPERM"));
  assert.equal(await page.evaluate(() => navigator.clipboard.readText()), "ordered");
  await page.evaluate(() => kineticaTerminalDemo.mimeClipboardPolicy("allow"));

  // Metadata listing must not ask any provider for data. Native wins over a web-custom
  // alias; duplicate requests are fetched once, and unrelated providers remain untouched.
  await page.evaluate(() => {
    globalThis.mimeLoads = [];
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: { read: () => Promise.resolve([
      { types: ["web text/plain", "web application/x-test"], getType: type => { mimeLoads.push(type); return Promise.resolve(new Blob(["custom"], { type: type.replace(/^web /, "") })); } },
      { types: ["text/plain", "text/html"], getType: type => { mimeLoads.push(type); return Promise.resolve(new Blob(["native"], { type })); } },
    ]) } });
  });
  assert.equal(await exchange(read(".")), readReply([[".", "text/plain application/x-test text/html\n"]]));
  assert.deepEqual(await page.evaluate(() => mimeLoads), []);
  assert.equal(await exchange(read("text/plain application/x-test text/plain")),
    readReply([["text/plain", "native"], ["application/x-test", "custom"], ["text/plain", "native"]]));
  assert.deepEqual(await page.evaluate(() => mimeLoads), ["text/plain", "web application/x-test"]);

  // Check the bound before allocating arrayBuffer(), then check the aggregate across formats.
  await page.evaluate(() => {
    globalThis.mimeBufferReads = 0;
    navigator.clipboard.read = () => Promise.resolve([{ types: ["image/png"], getType: () => Promise.resolve({
      size: 8 * 1024 * 1024 + 1, arrayBuffer() { mimeBufferReads++; throw new Error("Must not copy oversized blob"); },
    }) }]);
  });
  assert.equal(await exchange(read("image/png")), status("read", "EIO"));
  assert.equal(await page.evaluate(() => mimeBufferReads), 0);
  await page.evaluate(() => {
    navigator.clipboard.read = () => Promise.resolve([{ types: ["image/png", "text/plain"], getType: () => Promise.resolve({
      size: 5 * 1024 * 1024, arrayBuffer() { mimeBufferReads++; return Promise.resolve(new ArrayBuffer(this.size)); },
    }) }]);
  });
  assert.equal(await exchange(read("image/png text/plain")), status("read", "EIO"));
  assert.equal(await page.evaluate(() => mimeBufferReads), 1);

  // Permission, stale clipboard and native failures are all visible on the wire, including
  // synchronously thrown DOMExceptions. No rejected promise leaves the transport paused.
  for (const [name, expected] of [["NotAllowedError", "EPERM"], ["SecurityError", "EPERM"], ["NotSupportedError", "ENOSYS"],
    ["InvalidStateError", "EBUSY"], ["DataError", "EINVAL"], ["TypeError", "EINVAL"], ["UnknownError", "EIO"]]) {
    for (const synchronous of [false, true]) {
      await page.evaluate(({ name, synchronous }) => {
        const fail = () => { const error = new DOMException("test", name); if (synchronous) throw error; return Promise.reject(error); };
        navigator.clipboard.read = fail; navigator.clipboard.write = fail;
      }, { name, synchronous });
      assert.equal(await exchange(read("text/plain")), status("read", expected === "EINVAL" ? "EIO" : expected), `${name}, synchronous=${synchronous}`);
      assert.equal(await exchange(write([["text/plain", "error"]])), status("write", expected), `${name}, synchronous=${synchronous}`);
    }
  }
  for (const stage of ["getType", "arrayBuffer"]) {
    for (const synchronous of [false, true]) {
      await page.evaluate(({ stage, synchronous }) => {
        const fail = () => { const error = new DOMException("Clipboard changed", "InvalidStateError"); if (synchronous) throw error; return Promise.reject(error); };
        navigator.clipboard.read = () => Promise.resolve([{ types: ["text/plain"],
          getType: stage === "getType" ? fail : () => Promise.resolve({ size: 1, arrayBuffer: fail }),
        }]);
      }, { stage, synchronous });
      assert.equal(await exchange(read("text/plain")), status("read", "EBUSY"), `${stage}, synchronous=${synchronous}`);
    }
  }
  // Expanding one 4 MiB source to seventeen distinct browser formats must not publish
  // more than the adapter's 64 MiB budget, even though the protocol transaction is small.
  await page.evaluate(() => {
    globalThis.mimeWrites = 0;
    navigator.clipboard.write = () => { mimeWrites++; return Promise.resolve(); };
  });
  const root = "application/x-kinetica-root";
  const aliases = Array.from({ length: 16 }, (_, index) => `application/x-kinetica-alias-${index}`);
  assert.equal(await exchange(osc("type=write") + osc(`type=wdata:mime=${b64(root)};${b64(Buffer.alloc(4 * 1024 * 1024, 42))}`) +
    osc(`type=walias:mime=${b64(root)};${b64(aliases.join(" "))}`) + osc("type=wdata")), status("write", "EIO"));
  assert.equal(await page.evaluate(() => mimeWrites), 0);
  assert.equal(await exchange(write([["text/plain", "valid"], ["not-a-mime", "invalid"]])), status("write", "ENOSYS"));
  assert.equal(await page.evaluate(() => mimeWrites), 0);
  await page.evaluate(() => Object.defineProperty(navigator, "clipboard", { configurable: true, value: undefined }));
  assert.equal(await exchange(read("text/plain")), status("read", "ENOSYS"));
  assert.equal(await exchange(write([["text/plain", "missing API"]])), status("write", "ENOSYS"));

  // Cancel an outstanding read by disposing the view/transport, then resolve its browser
  // promise. No late protocol data or host action can reach a detached session.
  await page.evaluate(request => {
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: {
      read: () => new Promise(resolve => { globalThis.resolveMimeRead = resolve; }),
    } });
    mimeReplies.length = 0;
    kineticaTerminalDemo.receiveBytes(new TextEncoder().encode(request));
  }, read("text/plain"));
  await page.waitForFunction(() => typeof resolveMimeRead === "function");
  await page.evaluate(async () => {
    kineticaTerminalDemo.dispose();
    globalThis.mimeLateLoads = 0;
    resolveMimeRead([{ types: ["text/plain"], getType() { mimeLateLoads++; return Promise.resolve(new Blob(["late"], { type: "text/plain" })); } }]);
    await new Promise(resolve => setTimeout(resolve, 50));
    delete navigator.clipboard;
  });
  assert.deepEqual(await page.evaluate(() => mimeReplies), []);
  assert.equal(await page.evaluate(() => mimeLateLoads), 0);
  assert.deepEqual(errors, []);
  console.log("Browser MIME clipboard: real formats, protocol ordering, policies, lazy reads, bounds, errors and disposal passed");
} finally { await browser.close(); }
