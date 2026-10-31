import assert from 'node:assert/strict';
const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? 'playwright');
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const base = process.env.KINETICA_BROWSER_BASE_URL ?? 'http://127.0.0.1:4173';
try {
  for (const renderer of ['software', 'gpu']) for (const scale of [1, 1.25, 2]) for (const fonts of ['system', 'bundled']) {
    const page = await browser.newPage({ viewport: { width: 620, height: 300 }, deviceScaleFactor: scale });
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    try {
      await page.goto(`${base}/samples/browser-terminal/web/index.html?renderer=${renderer}${fonts === 'system' ? '&fonts=system' : ''}`);
      await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 20);
      await page.evaluate(() => document.fonts.ready);
      await page.evaluate(renderer => {
        const canvas = document.querySelector('canvas');
        if (renderer === 'gpu') {
          const gl = canvas.getContext('webgl2'), draw = gl.drawArraysInstanced.bind(gl);
          gl.drawArraysInstanced = (...args) => {
            draw(...args);
            globalThis.preeditPixels = new Uint8Array(canvas.width * canvas.height * 4);
            gl.readPixels(0, 0, canvas.width, canvas.height, gl.RGBA, gl.UNSIGNED_BYTE, preeditPixels);
          };
        } else globalThis.capturePreeditPixels = () => canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
        globalThis.preeditHash = async () => {
          const pixels = renderer === 'gpu' ? preeditPixels : capturePreeditPixels();
          return [...new Uint8Array(await crypto.subtle.digest('SHA-256', pixels))].map(v => v.toString(16).padStart(2, '0')).join('');
        };
      }, renderer);
      const frame = () => page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      const hash = async () => { await frame(); return page.evaluate(() => preeditHash()); };
      const input = page.getByRole('textbox', { name: 'Terminal input' }); await input.focus();
      const composition = async (type, data = '') => {
        await input.evaluate((element, { type, data }) => element.dispatchEvent(new CompositionEvent(type, { data, bubbles: true })), { type, data });
        await frame();
      };
      await page.evaluate(() => kineticaTerminalDemo.write('\x1b[?25l\x1b]10;#ffffff\x07\x1b]11;#000000\x07\x1b[48;2;240;20;30m' +
        ' '.repeat(kineticaTerminalDemo.columns()) + `\x1b[0m\x1b[1;${kineticaTerminalDemo.columns()}H`));
      const original = await hash();
      await composition('compositionstart'); await composition('compositionupdate', '　');
      const wide = await hash(); assert.notEqual(wide, original, 'Wide preedit must replace cells and draw underline');
      await composition('compositionupdate', 'prefix　');
      assert.equal(await hash(), wide, 'Right-edge placement retains the suffix and shifts left by one cell');
      await composition('compositionupdate', '\u0301　\u200d');
      assert.equal(await hash(), wide, 'Zero-width scalars do not change rendering');
      await composition('compositionupdate', '日'); assert.notEqual(await hash(), wide, 'Preedit glyph must be drawn');
      await composition('compositionupdate', '😀'); assert.notEqual(await hash(), wide, 'Supplementary-plane preedit glyph must be drawn');
      await composition('compositionend'); assert.equal(await hash(), original, 'Clearing restores all pixels');

      await composition('compositionstart'); await composition('compositionupdate', '　'); await hash();
      await page.evaluate(() => kineticaTerminalDemo.write('\x1b[?2026h\x1b[H'));
      await composition('compositionupdate', 'A'); assert.equal(await hash(), wide, 'Hold retains preedit and cursor together');
      await page.evaluate(() => kineticaTerminalDemo.write('\x1b[?2026l'));
      assert.notEqual(await hash(), wide, 'Release presents the current preedit');
      await composition('compositionend'); assert.equal(await hash(), original);
      assert.deepEqual(errors, []);
      console.log(`${renderer} ${fonts} DPR ${scale}: wide-cell preedit, suffix placement, zero-width filtering, glyphs, clearing and held frames passed`);
    } finally { await page.close(); }
  }
} finally { await browser.close(); }
