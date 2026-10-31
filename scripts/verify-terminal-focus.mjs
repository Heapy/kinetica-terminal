import assert from 'node:assert/strict';
const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? 'playwright');
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const base = process.env.KINETICA_BROWSER_BASE_URL ?? 'http://127.0.0.1:4173';
try {
  for (const renderer of ['software', 'gpu']) {
    const context = await browser.newContext({ viewport: { width: 600, height: 320 } });
    const page = await context.newPage();
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    try {
      await page.goto(`${base}/samples/browser-terminal/web/index.html?renderer=${renderer}&fonts=system`);
      await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 20);
      await page.evaluate(renderer => {
        globalThis.focusBytes = [];
        kineticaTerminalDemo.connectTransport({
          write(bytes) { focusBytes.push(...bytes); return bytes.length; }, resize() {}, consumed() {},
          rejected() { throw Error('Focus report rejected'); }, failed(message) { throw Error(message); },
        });
        const canvas = document.querySelector('canvas');
        if (renderer === 'gpu') {
          const gl = canvas.getContext('webgl2'), draw = gl.drawArraysInstanced.bind(gl);
          gl.drawArraysInstanced = (...args) => {
            draw(...args);
            const cell = kineticaTerminalDemo.cellMetrics();
            globalThis.cursorPixel = new Uint8Array(4);
            gl.readPixels(Math.floor(cell.width/2), canvas.height - 1 - Math.floor(cell.height/2), 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, cursorPixel);
          };
          globalThis.captureCursor = () => [...cursorPixel];
        } else globalThis.captureCursor = () => {
          const cell = kineticaTerminalDemo.cellMetrics();
          return [...canvas.getContext('2d').getImageData(Math.floor(cell.width/2), Math.floor(cell.height/2), 1, 1).data];
        };
      }, renderer);
      const frame = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      const drain = async () => {
        await page.waitForFunction(() => kineticaTerminalDemo.transportState().pendingInputBytes === 0);
        return page.evaluate(() => String.fromCharCode(...focusBytes.splice(0)));
      };
      const emit = async type => { await page.evaluate(type => window.dispatchEvent(new Event(type)), type); await frame(); };
      const input = page.getByRole('textbox', { name: 'Terminal input' });
      await page.evaluate(() => kineticaTerminalDemo.write('\x1b[?1004h\x1b]11;#000000\x07\x1b]12;#abcdef\x07\x1b[2 q\x1b[?25h'));
      await input.focus(); await frame();
      assert.equal(await drain(), '\x1b[I');
      const active = await page.evaluate(() => captureCursor());
      assert.deepEqual(active, [171, 205, 239, 255], 'Active block cursor fills the cell');
      await emit('blur');
      assert.equal(await drain(), '\x1b[O');
      assert.equal(await input.evaluate(node => document.activeElement === node), true, 'Window blur retains the DOM input owner');
      assert.deepEqual(await page.evaluate(() => captureCursor()), [0, 0, 0, 255], 'Inactive cursor is hollow');
      await emit('blur'); assert.equal(await drain(), '', 'Repeated blur must not duplicate reports');
      await emit('focus');
      assert.equal(await drain(), '\x1b[I', 'Returning to the window must restore focus without another input focus event');
      assert.deepEqual(await page.evaluate(() => captureCursor()), active);
      await emit('focus'); assert.equal(await drain(), '', 'Repeated focus must not duplicate reports');

      await page.evaluate(() => { const other = document.createElement('button'); other.id = 'outside'; other.textContent = 'Other control'; document.body.append(other); other.focus(); });
      assert.equal(await drain(), '\x1b[O');
      await emit('blur'); await emit('focus');
      assert.equal(await drain(), '', 'A different control owns the returning window');
      assert.equal(await page.evaluate(() => document.activeElement.id), 'outside', 'Do not steal DOM focus');
      await input.focus(); await frame(); assert.equal(await drain(), '\x1b[I');

      await page.evaluate(() => kineticaTerminalDemo.write('\x1b[?2026hUNPRESENTED'));
      await emit('blur'); assert.equal(await drain(), '\x1b[O');
      await emit('focus'); assert.equal(await drain(), '\x1b[I');
      assert.deepEqual(await page.evaluate(() => captureCursor()), active, 'Focus must repaint the retained cursor without exposing held output');
      await page.evaluate(() => kineticaTerminalDemo.write('\x1b[?2026l\x1b[2J\x1b[H')); await frame();

      await page.evaluate(() => {
        Object.defineProperty(document, 'visibilityState', { configurable: true, value: 'hidden' });
        document.dispatchEvent(new Event('visibilitychange'));
      });
      assert.equal(await drain(), '\x1b[O', 'Hidden documents release terminal focus even without window blur');
      await emit('focus'); assert.equal(await drain(), '', 'A hidden document cannot claim returning focus');
      await page.evaluate(() => { delete document.visibilityState; document.dispatchEvent(new Event('visibilitychange')); });
      await frame(); assert.equal(await drain(), '\x1b[I');
      assert.deepEqual(await page.evaluate(() => captureCursor()), active);
      await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
      assert.equal(await drain(), '', 'Repeated visible notifications do not duplicate reports');

      // A real Chromium tab activation preserves the textarea owner as well.
      const other = await context.newPage();
      await other.goto('data:text/html,<button autofocus>Other tab</button>');
      await other.bringToFront();
      await page.bringToFront(); await frame();
      const tabReports = await drain();
      assert.ok(tabReports === '' || tabReports === '\x1b[O\x1b[I', `Tab focus transitions must balance: ${JSON.stringify(tabReports)}`);
      assert.deepEqual(await page.evaluate(() => captureCursor()), active);
      await other.close();

      // Embedded surfaces can live in a shadow root; document.activeElement is its host.
      await page.evaluate(() => {
        const terminal = document.querySelector('[data-terminal]');
        const parent = terminal.parentElement;
        const host = document.createElement('div'); host.style.cssText = 'display:flex;width:600px;height:300px';
        parent.append(host); const shadow = host.attachShadow({ mode: 'open' }); shadow.append(terminal);
        globalThis.shadowInput = terminal.querySelector('textarea'); shadowInput.focus();
      });
      await frame(); await drain();
      await emit('blur'); assert.equal(await drain(), '\x1b[O');
      await emit('focus'); assert.equal(await drain(), '\x1b[I', 'Restore the owner inside a shadow root');
      assert.equal(await page.evaluate(() => shadowInput.getRootNode().activeElement === shadowInput), true);

      await page.evaluate(() => kineticaTerminalDemo.dispose());
      await emit('blur'); await emit('focus');
      assert.equal(await page.evaluate(() => String.fromCharCode(...focusBytes.splice(0))), '', 'Disposed listeners must not emit reports');
      assert.deepEqual(errors, []);
      console.log(`${renderer}: window focus reports, cursor pixels, outside owner, tab activation, visibility, retained frame, shadow owner and disposal passed`);
    } finally { await context.close(); }
  }
} finally { await browser.close(); }
