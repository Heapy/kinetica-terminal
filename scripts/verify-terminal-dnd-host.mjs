import assert from "node:assert/strict";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const url = `${process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173"}/samples/browser-terminal/web/index.html`;
const temporary = await mkdtemp(join(tmpdir(), "kinetica-dnd-"));
const files = [join(temporary, "one.bin"), join(temporary, "two.bin")];
const large = join(temporary, "large.bin");
await writeFile(files[0], Buffer.from([0,255,128,65]));
await writeFile(files[1], Buffer.from([1,2,3]));
await writeFile(large, Buffer.alloc(17 * 1024 * 1024));
try {
  for (const renderer of ["gpu", "software"]) {
    const page = await browser.newPage({ viewport: { width: 1100, height: 720 }, deviceScaleFactor: 2 });
    const errors = [];
    page.on("pageerror", error => errors.push(String(error)));
    await page.goto(`${url}?renderer=${renderer}`);
    await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 0);
    await page.evaluate(() => {
      globalThis.wire = []; globalThis.messages = []; globalThis.events = []; globalThis.partial = "";
      globalThis.sourceEffect = "copyMove"; globalThis.writable = true;
      globalThis.reads = 0; globalThis.aborts = 0; globalThis.deferReads = false; globalThis.deferred = [];
      globalThis.originalRead = FileReader.prototype.readAsArrayBuffer;
      FileReader.prototype.readAsArrayBuffer = function(blob) {
        reads++;
        if (!deferReads) return originalRead.call(this, blob);
        const reader = this, load = this.onload;
        Object.defineProperty(reader, "readyState", { configurable: true, get: () => 1 });
        reader.abort = () => { aborts++; };
        deferred.push({ reader, load, blob });
      };
      globalThis.dndBridge = {
        write(bytes) {
          if (!writable) return 0;
          for (const b of bytes) { wire.push(b); partial += String.fromCharCode(b); }
          const pattern = /\x1b\]72;([^\x07\x1b]*)(?:\x07|\x1b\\)/g;
          let match, end = 0;
          while ((match = pattern.exec(partial))) { messages.push(match[1]); end = pattern.lastIndex; }
          partial = partial.slice(end);
          return bytes.length;
        }, resize() {}, consumed() {}, rejected() { throw Error("DnD input rejected"); }, failed(message) { throw Error(message); },
      };
      kineticaTerminalDemo.connectTransport(dndBridge);
      const source = document.createElement("div"); source.id = "drag-source"; source.draggable = true;
      source.textContent = "Drag test";
      source.style.cssText = "position:fixed;left:4px;top:4px;width:100px;height:32px;background:orange;z-index:1000";
      document.body.append(source);
      source.addEventListener("dragstart", event => {
        event.dataTransfer.effectAllowed = sourceEffect;
        event.dataTransfer.setData("text/plain", "hello é😀");
        event.dataTransfer.setData("text/html", "<b>hello</b>");
        event.dataTransfer.setData("application/x-kinetica-test", "opaque\u0000payload");
        events.push({ type: "start", trusted: event.isTrusted });
      });
      const target = document.querySelector("[data-terminal]");
      for (const type of ["dragenter", "dragover", "dragleave", "drop"]) target.addEventListener(type, event => {
        events.push({ type, trusted: event.isTrusted, x: event.clientX, y: event.clientY, effect: event.dataTransfer?.dropEffect, items: Array.from(event.dataTransfer?.items ?? [], item => ({kind:item.kind,type:item.type})) });
      });
    });
    async function idle() {
      await page.waitForFunction(() => {
        const state = kineticaTerminalDemo.transportState();
        return !state.paused && state.queuedOutputBytes === 0 && state.pendingInputBytes === 0;
      });
    }
    async function write(value) { await page.evaluate(value => kineticaTerminalDemo.write(value), value); await idle(); }
    async function clear() { await idle(); await page.evaluate(() => { wire.length = 0; messages.length = 0; partial = ""; events.length = 0; }); }
    const targetBox = await page.locator("[data-terminal] canvas").boundingBox();
    const x = targetBox.x + 120, y = targetBox.y + 65;
    async function start() {
      await page.mouse.move(40, 18); await page.mouse.down();
      await page.mouse.move(115, 35, { steps: 8 });
      await page.mouse.move(x, y, { steps: 12 }); await page.mouse.move(x + 2, y + 2);
      await idle();
      assert.ok((await page.evaluate(() => events)).some(e => e.type === "dragover" && e.trusted), `${renderer}: real dragover`);
    }
    async function move() { await page.mouse.move(x + 3, y + 3); await page.mouse.move(x + 4, y + 4); await idle(); }
    async function drop() { await page.mouse.up(); await idle(); }
    const cdp = await page.context().newCDPSession(page);
    let fileDrag;
    async function startFiles(paths = files) {
      fileDrag = { items: [], files: paths, dragOperationsMask: 1 };
      await cdp.send("Input.dispatchDragEvent", { type: "dragEnter", x, y, data: fileDrag });
      await cdp.send("Input.dispatchDragEvent", { type: "dragOver", x, y, data: fileDrag });
      await idle();
      const observed = await page.evaluate(() => events.filter(e => e.type === "dragover").at(-1));
      assert.equal(observed.trusted, true);
      assert.ok(observed.items.some(item => item.kind === "file"), "CDP delivers actual file items");
    }
    async function dropFiles() {
      await cdp.send("Input.dispatchDragEvent", { type: "drop", x, y, data: fileDrag }); await idle();
    }
    function header(message) { return Object.fromEntries(message.split(";")[0].split(":").map(kv => kv.split("="))); }
    const getMessages = () => page.evaluate(() => messages);

    await write("\x1b[?2004h"); await clear(); await start(); await drop();
    assert.equal(Buffer.from(await page.evaluate(() => wire)).toString(), "\x1b[200~hello é😀\x1b[201~", `${renderer}: ordinary paste fallback`);
    await write("\x1bc\x1b]72;t=a:i=12;text/plain text/html application/x-kinetica-test\x07");
    await clear(); await start();
    const movement = (await getMessages()).filter(m => header(m).t === "m").at(-1);
    const last = (await page.evaluate(() => events)).filter(e => e.type === "dragover").at(-1);
    assert.equal(+header(movement).X, Math.floor((last.x - targetBox.x) * 2));
    assert.equal(+header(movement).Y, Math.floor((last.y - targetBox.y) * 2));
    assert.ok(+header(movement).x >= 0 && +header(movement).y >= 0);
    await write("\x1b]72;t=m:o=2:m=1;text/\x07"); await move();
    assert.equal((await page.evaluate(() => events)).filter(e => e.type === "dragover").at(-1).effect, "copy");
    await write("\x1b]72;m=0;plain\x07"); await move();
    assert.equal((await page.evaluate(() => events)).filter(e => e.type === "dragover").at(-1).effect, "move");
    await drop();
    const dropped = (await getMessages()).find(m => header(m).t === "M");
    assert.ok(dropped, `${renderer}: drop message`);
    const types = dropped.slice(dropped.indexOf(";") + 1).trim().split(" ");
    assert.deepEqual(types, ["text/plain", "text/html", "application/x-kinetica-test"]);
    const content = ["hello é😀", "<b>hello</b>", "opaque\u0000payload"];
    for (let i = 0; i < types.length; i++) {
      await clear(); await write(`\x1b]72;t=r:x=${i + 1}\x07`);
      const response = await getMessages();
      assert.equal(response.length, 2);
      assert.equal(header(response[0]).i, "12");
      assert.deepEqual(Buffer.from(response[0].slice(response[0].indexOf(";") + 1), "base64"), Buffer.from(content[i]));
      assert.equal(response[1], `t=r:x=${i + 1}:i=12`);
    }
    await write("\x1b]72;t=r:o=2\x07"); await clear(); await write("\x1b]72;t=r:x=1\x07");
    assert.ok((await getMessages())[0].includes("ENOENT:no drop data available"));

    // Reject a drag, then ensure a new gesture gets fresh acceptance rather than the old rejection.
    await clear(); await start(); await write("\x1b]72;t=m:o=0\x07"); await move();
    assert.equal((await page.evaluate(() => events)).filter(e => e.type === "dragover").at(-1).effect, "none");
    await drop(); assert.ok(!(await getMessages()).some(m => header(m).t === "M"));
    await clear(); await start();
    assert.equal((await page.evaluate(() => events)).filter(e => e.type === "dragover").at(-1).effect, "copy");
    await page.mouse.move(20, 10, { steps: 8 }); await page.keyboard.press("Escape"); await page.mouse.up(); await idle();
    assert.ok((await getMessages()).some(m => header(m).x === "-1" && header(m).y === "-1"));

    await page.evaluate(() => { reads = 0; });
    await clear(); await startFiles(); assert.equal(await page.evaluate(() => reads), 0, "Files remain unread until drop");
    await dropFiles();
    await page.waitForFunction(() => messages.some(m => m.startsWith("t=M:")));
    assert.equal(await page.evaluate(() => reads), 2, JSON.stringify(await page.evaluate(() => ({messages, events}))));
    for (const [index, expected] of [[1, [0,255,128,65]], [2, [1,2,3]]]) {
      await clear(); await write(`\x1b]72;t=r:x=${index}\x07`);
      const response = (await getMessages())[0];
      assert.deepEqual([...Buffer.from(response.slice(response.indexOf(";") + 1), "base64")], expected);
    }
    await write("\x1b]72;t=r:o=1\x07");

    await page.evaluate(() => { reads = 0; });
    await clear(); await startFiles([large]); await dropFiles();
    assert.equal(await page.evaluate(() => reads), 0, "Over-budget files are rejected before FileReader");
    assert.ok(!(await getMessages()).some(m => header(m).t === "M"));

    // DOM drops must use the same bounded path when the real stream adapter cannot write.
    await page.evaluate(() => { reads = 0; });
    await clear(); await startFiles();
    await page.evaluate(() => { writable = false; messages.length = 0; });
    for (let i = 0; i < 100; i++)
      await cdp.send("Input.dispatchDragEvent", { type: "dragOver", x: x + i % 10, y, data: fileDrag });
    await cdp.send("Input.dispatchDragEvent", { type: "drop", x, y, data: fileDrag });
    await page.waitForFunction(() => reads === 2 && kineticaTerminalDemo.transportState().pendingInputBytes > 0);
    const blocked = await page.evaluate(() => kineticaTerminalDemo.transportState());
    assert.equal(blocked.paused, true); assert.ok(blocked.pendingInputBytes <= 32768);
    assert.ok(!(await getMessages()).some(m => header(m).t === "M"));
    await page.evaluate(() => { writable = true; });
    await page.waitForFunction(() => messages.some(m => m.startsWith("t=M:"))); await idle();
    assert.ok((await getMessages()).filter(m => header(m).t === "m").length <= 2, "Unsent moves coalesce");
    await clear(); await write("\x1b]72;t=r:x=1\x07");
    const resumed = (await getMessages())[0];
    assert.deepEqual([...Buffer.from(resumed.slice(resumed.indexOf(";") + 1), "base64")], [0,255,128,65]);
    await write("\x1b]72;t=r:o=1\x07");

    for (const cancel of ["unregister", "detach"]) {
      await page.evaluate(() => { deferReads = true; deferred.length = 0; aborts = 0; });
      await clear(); await startFiles(); await dropFiles();
      assert.equal(await page.evaluate(() => deferred.length), 1);
      if (cancel === "unregister") await write("\x1b]72;t=A\x07\x1b]72;t=a:i=99\x07");
      else await page.evaluate(() => {
        const root = document.querySelector("[data-terminal]"), parent = root.parentNode;
        root.remove(); parent.append(root);
      });
      await page.waitForFunction(() => aborts === 1);
      await page.evaluate(() => {
        const item = deferred[0];
        if (item.reader.onload !== null) throw Error("Reader callback retained after cancellation");
        Object.defineProperty(item.reader, "result", { value: new ArrayBuffer(4) });
        item.load(new ProgressEvent("load")); // Simulate a completion already posted before cancellation.
        deferReads = false;
      });
      await idle();
      assert.ok(!(await getMessages()).some(m => header(m).t === "M"), `${cancel}: no stale drop`);
    }
    await clear();
    await page.evaluate(() => {
      const transfer = new DataTransfer(); transfer.setData("text/plain", "untrusted");
      const root = document.querySelector("[data-terminal]");
      root.dispatchEvent(new DragEvent("dragover", { bubbles: true, dataTransfer: transfer }));
      root.dispatchEvent(new DragEvent("drop", { bubbles: true, dataTransfer: transfer }));
    });
    await idle(); assert.deepEqual(await getMessages(), []);
    await page.evaluate(() => { deferReads = true; deferred.length = 0; aborts = 0; });
    await clear(); await startFiles(); await dropFiles();
    await page.evaluate(() => kineticaTerminalDemo.dispose());
    await page.waitForFunction(() => aborts === 1);
    await page.evaluate(() => {
      const item = deferred[0];
      if (item.reader.onload !== null) throw Error("Disposed view retains file callback");
      Object.defineProperty(item.reader, "result", { value: new ArrayBuffer(4) });
      item.load(new ProgressEvent("load"));
      kineticaTerminalDemo.connectTransport(dndBridge);
    });
    await clear(); await write("\x1b]72;t=r:x=1\x07");
    assert.ok((await getMessages())[0].includes("ENOENT:no drop data available"));
    assert.deepEqual(errors, []);
    await page.close();
    console.log(`${renderer}: trusted DOM drops, MIME data, source/client feedback, files, limits and stale reads passed`);
  }
} finally { await browser.close(); await rm(temporary, { recursive: true, force: true }); }
