import assert from "node:assert/strict";

const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const url = `${process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173"}/samples/browser-terminal/web/index.html`;
const apc = header => `\x1b_G${header}\x1b\\`;
const image = (id, rgb, extra = "") => apc(`a=T,q=2,i=${id},f=24,s=1,v=1,c=4,r=4,C=1${extra};${Buffer.from(rgb).toString("base64")}`);
const red = [240, 20, 30, 255], blue = [10, 20, 200, 255], green = [20, 210, 40, 255];
try {
  for (const renderer of ["gpu", "software"]) {
    const page = await browser.newPage({ viewport: { width: 1100, height: 720 }, deviceScaleFactor: 2 });
    const errors = [];
    page.on("pageerror", error => errors.push(String(error)));
    await page.goto(`${url}?renderer=${renderer}`);
    await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 80);
    await page.evaluate(renderer => {
      globalThis.graphicsReplies = [];
      kineticaTerminalDemo.connectTransport({ write(bytes) { graphicsReplies.push(...bytes); return bytes.length; }, resize() {}, consumed() {}, rejected() { throw Error("Rejected input"); }, failed(message) { throw Error(message); } });
      globalThis.graphicsCanvas = document.querySelector("canvas");
      globalThis.graphicsSampleRow = 2;
      globalThis.graphicsResources = new Set();
      if (renderer === "gpu") {
        const gl = graphicsCanvas.getContext("webgl2");
        const create = gl.createTexture.bind(gl), remove = gl.deleteTexture.bind(gl);
        gl.createTexture = () => { const texture = create(); graphicsResources.add(texture); return texture; };
        gl.deleteTexture = texture => { graphicsResources.delete(texture); remove(texture); };
        graphicsCanvas.addEventListener("webglcontextlost", () => graphicsResources.clear());
        const draw = gl.drawArraysInstanced.bind(gl);
        gl.drawArraysInstanced = (...args) => {
          draw(...args);
          if (globalThis.graphicsDeferReadback && --globalThis.graphicsDrawsUntilReadback !== 0) return;
          const pixels = new Uint8Array(gl.drawingBufferWidth * 4);
          gl.readPixels(0, gl.drawingBufferHeight - 1 - graphicsSampleRow, gl.drawingBufferWidth, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
          globalThis.graphicsPixels = [...pixels];
        };
      } else {
        const create = document.createElement.bind(document);
        document.createElement = (...args) => {
          const element = create(...args);
          if (args[0] === "canvas") graphicsResources.add(element);
          return element;
        };
      }
    }, renderer);
    const painted = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const write = async value => { await page.evaluate(value => kineticaTerminalDemo.write(value), value); await painted(); };
    const reset = async () => { await page.evaluate(() => kineticaTerminalDemo.reset()); await write("\x1b[?25l\x1b]11;#0a14c8\x07"); };
    const pixel = async (x = 2) => page.evaluate(({ renderer, x }) => renderer === "gpu" ? graphicsPixels.slice(x * 4, x * 4 + 4) : [...graphicsCanvas.getContext("2d").getImageData(x, graphicsSampleRow, 1, 1).data], { renderer, x });
    const metric = async key => +(await page.locator("[data-terminal]").getAttribute(`data-terminal-image-${key}`));
    const expectPixel = async (expected, message) => assert.deepEqual(await pixel(), expected, `${renderer}: ${message}`);
    await reset();
    await write(image(1, red.slice(0, 3), ",z=-2147483648"));
    await expectPixel(red, "image below default background remains visible");
    await write("\x1b[H\x1b[48;2;10;20;200m \x1b[0m");
    await expectPixel(blue, "explicit background covers the lowest image layer");
    await write("\x1b[H" + image(2, green.slice(0, 3), ",z=-1"));
    await expectPixel(green, "middle image layer covers cell background");
    await write("\x1b[H\x1b[38;2;240;20;30m█\x1b[0m");
    await expectPixel(red, "geometric block glyph covers middle image layer");
    await write("\x1b[H" + image(3, green.slice(0, 3)));
    await expectPixel(green, "top image layer covers block glyph");
    const uploads = await metric("uploads");
    await write("\x1b[5;1Hx");
    assert.equal(await metric("uploads"), uploads, `${renderer}: unchanged images reuse uploaded tiles`);
    await write(apc("a=d,d=A,q=2"));
    await expectPixel(red, "deletion restores text");
    assert.equal(await metric("bytes"), 0);
    assert.equal(await metric("tiles"), 0);

    await reset();
    await write(apc(`a=T,q=2,i=1,f=32,s=1,v=1,c=4,r=4,C=1;${Buffer.from([240, 20, 30, 128]).toString("base64")}`));
    const blended = await pixel();
    [125, 20, 115, 255].forEach((value, i) => assert(Math.abs(blended[i] - value) <= 1, `${renderer}: premultiplied alpha ${blended}`));
    await reset();
    await write(apc(`a=T,q=2,i=1,f=24,s=2,v=1,x=1,w=1,c=4,r=4,C=1;${Buffer.from([...red.slice(0, 3), ...green.slice(0, 3)]).toString("base64")}`));
    await expectPixel(green, "source crop");
    await reset();
    await write(image(1, red.slice(0, 3)));
    await write("\x1b[?2026h" + image(1, green.slice(0, 3)));
    await expectPixel(red, "synchronized output retains old pixels after ID replacement");
    if (renderer === "gpu") {
      await page.evaluate(() => {
        const extension = graphicsCanvas.getContext("webgl2").getExtension("WEBGL_lose_context");
        graphicsCanvas.addEventListener("webglcontextrestored", () => globalThis.graphicsRestored = true, { once: true });
        graphicsCanvas.addEventListener("webglcontextlost", () => setTimeout(() => extension.restoreContext(), 20), { once: true });
        extension.loseContext();
      });
      await page.waitForFunction(() => globalThis.graphicsRestored, null, { polling: 10 });
      await painted();
      await expectPixel(red, "context recovery preserves the held image generation");
    }
    await write("\x1b[?2026l");
    await expectPixel(green, "release presents replacement pixels");

    await reset();
    await write(image(1, red.slice(0, 3)));
    await write(apc(`a=f,i=1,q=2,f=32,s=1,v=1,m=1;${Buffer.from(green.slice(0, 1)).toString("base64")}`));
    await write(apc(`a=T,i=99;${Buffer.from(green.slice(1)).toString("base64")}`));
    await expectPixel(red, "appending a frame retains the displayed root");
    await write(apc("a=a,i=1,c=2"));
    await expectPixel(green, "client-selected animation frame");
    await write("\x1b[?2026h" + apc(`a=f,i=1,r=2,q=2,f=32,s=1,v=1;${Buffer.from(red).toString("base64")}`));
    await expectPixel(green, "frame edits preserve held pixels");
    await write("\x1b[?2026l");
    await expectPixel(red, "frame edit becomes visible after hold");
    await write(apc(`a=f,i=1,r=1,q=2,f=32,s=1,v=1;${Buffer.from(green).toString("base64")}`));
    await expectPixel(red, "editing an undisplayed frame leaves the selected frame intact");
    await write(apc("a=c,i=1,r=1,c=2,q=2"));
    await expectPixel(green, "frame composition refreshes displayed pixels");
    await write(apc("a=d,d=f,i=1,r=1"));
    await expectPixel(green, "root promotion retains placements");
    await write(apc("a=d,d=F,i=1,r=1"));
    await expectPixel(blue, "deleting the final frame removes the image");
    assert.equal(await metric("bytes"), 0);

    // Playback must redraw without any subsequent PTY bytes, then become idle at its loop limit.
    await reset();
    await write(image(1, red.slice(0, 3)));
    await write(apc(`a=f,i=1,q=2,f=32,s=1,v=1,z=120;${Buffer.from(green).toString("base64")}`));
    await write(apc("a=a,i=1,r=1,z=120,s=3,v=3"));
    const waitPixel = expected => page.waitForFunction(({renderer, expected}) => {
      const actual = renderer === "gpu" ? graphicsPixels.slice(8, 12) : [...graphicsCanvas.getContext("2d").getImageData(2, graphicsSampleRow, 1, 1).data];
      return actual.every((value, i) => value === expected[i]);
    }, {renderer, expected});
    await waitPixel(green); await waitPixel(red); await waitPixel(green);
    await page.waitForTimeout(180);
    const finiteUploads = await metric("uploads");
    await page.waitForTimeout(250);
    assert.equal(await metric("uploads"), finiteUploads, `${renderer}: finite animation must stop redrawing`);
    await expectPixel(green, "finite playback parks on its final frame");
    await write(apc("a=a,i=1,s=3,v=1"));
    await page.evaluate(() => { graphicsReplies.length = 0; });
    await write("\x1b[?2033h");
    const expectVisibility = async visible => {
      await page.waitForFunction(visible => String.fromCharCode(...graphicsReplies).endsWith(`\x1b[?999;${visible ? 1 : 2}n`), visible);
    };
    await expectVisibility(true);
    for (const property of ["display", "visibility"]) {
      await page.evaluate(property => { document.querySelector("[data-terminal]").parentElement.style[property] = property === "display" ? "none" : "hidden"; }, property);
      await expectVisibility(false);
      await painted();
      const hiddenUploads = await metric("uploads");
      await page.waitForTimeout(300);
      assert.equal(await metric("uploads"), hiddenUploads, `${renderer}: hidden ancestor (${property}) must pause playback`);
      await page.evaluate(property => { document.querySelector("[data-terminal]").parentElement.style[property] = ""; }, property);
      await page.waitForFunction(before => +document.querySelector("[data-terminal]").getAttribute("data-terminal-image-uploads") > before, hiddenUploads);
      await expectVisibility(true);
    }
    await page.evaluate(() => {
      globalThis.detachedTerminal = document.querySelector("[data-terminal]");
      globalThis.terminalParent = detachedTerminal.parentElement;
      detachedTerminal.remove();
    });
    await expectVisibility(false);
    await page.evaluate(() => { terminalParent.append(detachedTerminal); });
    await expectVisibility(true);
    await page.evaluate(() => {
      Object.defineProperty(document,"visibilityState",{ configurable:true,value:"hidden" });
      document.dispatchEvent(new Event("visibilitychange"));
    });
    await expectVisibility(false);
    await page.evaluate(() => { delete document.visibilityState; document.dispatchEvent(new Event("visibilitychange")); });
    await expectVisibility(true);
    assert.equal(await page.evaluate(() => String.fromCharCode(...graphicsReplies)),
      [1,2,1,2,1,2,1,2,1].map(value => `\x1b[?999;${value}n`).join(""), `${renderer}: visibility changes are reported once`);
    await write("\x1b[?2033l");
    await write(apc("a=a,i=1,s=1"));

    await reset();
    await write(image(1, red.slice(0, 3)));
    await page.evaluate(() => kineticaTerminalDemo.write(`\x1b[${kineticaTerminalDemo.rows()};1H\n`));
    await painted();
    await expectPixel(red, "partially scrolled image stays visible");
    await page.evaluate(() => document.querySelector("[data-terminal]").dispatchEvent(new WheelEvent("wheel", { deltaY: -1, bubbles: true, cancelable: true })));
    await painted();
    assert.equal(await page.evaluate(() => kineticaTerminalDemo.scrollOffset()), 1);
    await expectPixel(red, "scrollback restores the original image origin");
    await write("\x1b[?1049h");
    await expectPixel(blue, "alternate screen owns a separate image store");
    assert.equal(await metric("bytes"), 0);
    await write("\x1b[?1049l");
    await expectPixel(red, "return to primary screen restores its images");

    // A scaled image crossing multiple source-tile boundaries must not show background seams.
    await reset();
    const rgb = Buffer.alloc(2046 * 3);
    for (let i = 0; i < 2046; i++) rgb.set(red.slice(0, 3), i * 3);
    const payload = rgb.toString("base64");
    await write(apc(`a=T,q=2,i=9,f=24,s=2046,v=1,c=50,r=2,C=1,m=1;${payload.slice(0, 4096)}`) + apc(`m=0;${payload.slice(4096)}`));
    const row = await page.evaluate(renderer => renderer === "gpu" ? graphicsPixels.slice(0, 700 * 4) : [...graphicsCanvas.getContext("2d").getImageData(0, 2, 700, 1).data], renderer);
    for (let x = 2; x < 700; x++) assert.deepEqual(row.slice(x * 4, x * 4 + 4), red, `${renderer}: tiled image pixel ${x}`);
    assert.equal(await metric("tiles"), 3);
    assert((await metric("bytes")) < 2046 * 4 + 32);
    await write(apc("a=d,d=A,q=2"));
    assert.equal(await metric("bytes"), 0);
    await reset();
    await write("\x1b[16t");
    const cell = await page.evaluate(() => {
      const reply = String.fromCharCode(...graphicsReplies);
      const matches = [...reply.matchAll(/\x1b\[6;(\d+);(\d+)t/g)];
      return [+matches.at(-1)[2], +matches.at(-1)[1]];
    });
    const placeholderPixels = Buffer.alloc(cell[0] * 2 * cell[1] * 3);
    for (let y = 0; y < cell[1]; y++) for (let x = 0; x < cell[0] * 2; x++)
      placeholderPixels.set((x < cell[0] ? red : green).slice(0,3), (y * cell[0] * 2 + x) * 3);
    const virtual = apc(`a=T,q=2,i=1,p=7,U=1,z=20,f=24,s=${cell[0]*2},v=${cell[1]},c=2,r=1;${placeholderPixels.toString("base64")}`);
    const placeholder = String.fromCodePoint(0x10eeee);
    await write(virtual + "\x1b[H\x1b[38;2;0;0;1m" + placeholder + "\u0305\u0305" + placeholder);
    await expectPixel(red, "Unicode placeholder first image cell");
    assert.deepEqual(await pixel(cell[0] + 2), green, `${renderer}: placeholder inherits next image column`);
    await write("\x1b[H ");
    await expectPixel(blue, "erasing a placeholder removes its image fragment");
    assert.deepEqual(await pixel(cell[0] + 2), red, `${renderer}: a new placeholder run starts at image column zero`);
    await write("\x1b[H" + placeholder + "\u0305\u0305");
    assert.deepEqual(await pixel(cell[0] + 2), green);
    await write("\x1b[?2026h\x1b[2J");
    await expectPixel(red, "held placeholder cells preserve their image fragments");
    await write("\x1b[?2026l");
    await expectPixel(blue, "released placeholder deletion");
    assert.equal(await metric("bytes"), 0);
    await reset();
    const tinyImage = apc(`a=T,q=2,i=1,p=7,U=1,f=24,s=1,v=1,c=4,r=3;${Buffer.from(red.slice(0,3)).toString("base64")}`);
    const tinyRows = [0x305,0x30d,0x30e].map(row => placeholder + String.fromCodePoint(row,0x305) + placeholder.repeat(3)).join("\r\n");
    await page.evaluate(row => graphicsSampleRow = row, Math.round((cell[1] * 3 - cell[0] * 4) / 2) + 2);
    await write(tinyImage + "\x1b[H\x1b[38;2;0;0;1m" + tinyRows);
    await expectPixel(red, "rounded zero source span retains the first image fragment");
    await page.evaluate(row => graphicsSampleRow = row, cell[1] * 2 + 2);
    await write("\x1b[5;1Hx");
    await expectPixel(red, "rounded edge source span repeats the last texel");
    await page.evaluate(() => graphicsSampleRow = 2);
    if (renderer === "gpu") {
      await reset();
      // One source image, twenty distinct overlapping crop tiles. All fit in decoded storage,
      // but their GPU representations exceed 64 MiB in a single frame. Do not read pixels per
      // draw here: that would implicitly synchronize the GPU and hide retained-resource bugs.
      await page.evaluate(() => {
        const gl = graphicsCanvas.getContext("webgl2");
        const allocate = gl.texImage2D.bind(gl), remove = gl.deleteTexture.bind(gl);
        const draw = gl.drawArraysInstanced.bind(gl), finish = gl.finish.bind(gl);
        const resources = new Map();
        globalThis.graphicsBudget = { peak: 0, drains: 0 };
        globalThis.graphicsDeferReadback = true;
        globalThis.graphicsDrawsUntilReadback = 20;
        gl.texImage2D = (...args) => {
          allocate(...args);
          resources.set(gl.getParameter(gl.TEXTURE_BINDING_2D), { bytes: args[3] * args[4] * 4, pending: false, deleted: false });
          graphicsBudget.peak = Math.max(graphicsBudget.peak, [...resources.values()].reduce((sum, value) => sum + value.bytes, 0));
        };
        gl.drawArraysInstanced = (...args) => {
          const resource = resources.get(gl.getParameter(gl.TEXTURE_BINDING_2D));
          if (resource) resource.pending = true;
          draw(...args);
        };
        gl.deleteTexture = texture => {
          const resource = resources.get(texture);
          if (resource?.pending) resource.deleted = true;
          else resources.delete(texture);
          remove(texture);
        };
        gl.finish = () => {
          finish(); graphicsBudget.drains++;
          for (const [texture, resource] of resources) {
            resource.pending = false;
            if (resource.deleted) resources.delete(texture);
          }
        };
        globalThis.restoreGraphicsBudgetHooks = () => {
          gl.texImage2D = allocate; gl.deleteTexture = remove;
          gl.drawArraysInstanced = draw; gl.finish = finish;
          globalThis.graphicsDeferReadback = false;
        };
      });
      const data = Buffer.alloc(1022 * 1022 * 3);
      for (let i = 0; i < data.length; i += 3) data.set(red.slice(0, 3), i);
      const encoded = data.toString("base64");
      let scene = "";
      for (let offset = 0; offset < encoded.length; offset += 4096) {
        const first = offset === 0 ? "a=t,q=2,i=90,f=24,s=1022,v=1022," : "";
        scene += apc(`${first}m=${offset + 4096 < encoded.length ? 1 : 0};${encoded.slice(offset, offset + 4096)}`);
      }
      for (let i = 0; i < 20; i++) scene += `\x1b[1;${i * 3 + 1}H` + apc(`a=p,q=2,i=90,p=${i + 1},x=${i},w=${1022 - i},c=2,r=1,C=1`);
      await write(scene);
      assert.equal(await page.evaluate(() => graphicsDrawsUntilReadback), 0);
      const budget = await page.evaluate(() => graphicsBudget);
      assert.ok(budget.drains > 0, "Oversized frame must retire GPU commands before reusing budget");
      assert.ok(budget.peak <= 64 * 1024 * 1024, `All live and command-retained tiles: ${budget.peak}`);
      console.log(`GPU image budget: peak ${budget.peak} bytes including unfinished commands, ${budget.drains} completion barriers`);
      // Renderer origins retain fractional font metrics; protocol cell widths are integers.
      // Count the twenty separate solid stripes instead of accumulating rounded cell widths.
      const pixels = await page.evaluate(() => graphicsPixels);
      const spans = []; let span = 0;
      for (let x = 0; x < pixels.length; x += 4) {
        if (red.every((value, channel) => pixels[x + channel] === value)) span++;
        else if (span) { spans.push(span); span = 0; }
      }
      if (span) spans.push(span);
      assert.equal(spans.length, 20, "Every crop survives the budget boundary");
      assert.ok(spans.every(width => width >= cell[0]), "All stripes preserve their visible area");
      await page.evaluate(() => restoreGraphicsBudgetHooks());
      await reset();
    }
    assert.equal(await page.locator("[data-terminal]").getAttribute("data-terminal-rendering-error"), null);
    assert.deepEqual(errors, []);
    await write("\x1b[H" + image(10, red.slice(0, 3)));
    await page.evaluate(() => kineticaTerminalDemo.dispose());
    assert.equal(await page.evaluate(renderer => renderer === "gpu" ? graphicsResources.size : [...graphicsResources].filter(canvas => canvas.width !== 0 || canvas.height !== 0).length, renderer), 0, `${renderer}: dispose releases image resources`);
    await page.close();
    console.log(`${renderer}: Kitty graphics pixels, layers, alpha, crop, Unicode placeholders, rounded spans, hold, tile reuse and cleanup passed`);
  }
} finally { await browser.close(); }
