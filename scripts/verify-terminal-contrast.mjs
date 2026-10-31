import assert from 'node:assert/strict';
const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? 'playwright');
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
const base = process.env.KINETICA_BROWSER_BASE_URL ?? 'http://127.0.0.1:4173';
try {
  for (const renderer of ['gpu', 'software']) for (const scale of [1, 1.25, 2]) for (const fonts of ['bundled', 'system']) {
    const page = await browser.newPage({ viewport: { width: 500, height: 250 }, deviceScaleFactor: scale });
    const errors = []; page.on('pageerror', e => errors.push(e.message));
    try {
      await page.goto(`${base}/samples/browser-terminal/web/index.html?renderer=${renderer}${fonts === 'system' ? '&fonts=system' : ''}`);
      await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 20);
      await page.evaluate(() => document.fonts.ready);
      await page.locator('[data-terminal]').click({ position: { x: 2, y: 2 } });
      const result = await page.evaluate(async ({ renderer, fonts }) => {
        const api = kineticaTerminalDemo, canvas = document.querySelector('canvas');
        const wait = () => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
        let pixels;
        let capture;
        if (renderer === 'gpu') {
          const gl = canvas.getContext('webgl2'), draw = gl.drawArraysInstanced.bind(gl);
          gl.drawArraysInstanced = (...args) => {
            draw(...args); pixels = new Uint8Array(gl.drawingBufferWidth * gl.drawingBufferHeight * 4);
            gl.readPixels(0, 0, gl.drawingBufferWidth, gl.drawingBufferHeight, gl.RGBA, gl.UNSIGNED_BYTE, pixels);
          };
          capture = () => pixels.slice();
        } else capture = () => canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
        const paint = async text => { api.write(text); await wait(); return capture(); };
        const compare = (actual, expected, label) => {
          let worst = 0, index = -1;
          for (let i = 0; i < actual.length; i++) if (Math.abs(actual[i] - expected[i]) > worst) { worst = Math.abs(actual[i] - expected[i]); index = i; }
          if (worst > 2) throw new Error(`${label}: pixel error ${worst} at ${index}, actual=${actual[index]}, expected=${expected[index]}`);
        };
        let checked = 0;
        for (const bg of [0, 0xffffff]) for (const image of [false, true]) for (const faint of [0, 0.5, 1]) {
          api.reset(); api.minimumContrast(7); api.faintOpacity(faint);
          await paint(`\x1b[?25l\x1b]11;#${bg.toString(16).padStart(6,'0')}\x07` + (image ? '\x1b_Ga=T,q=2,i=44,f=24,s=1,v=1,c=4,r=4,C=1,z=-1;FNIk\x1b\\' : ''));
          const fg = bg ? '240;240;240' : '16;16;16', corrected = bg ? '0;0;0' : '255;255;255';
          const suffix = `\x1b[0;38;2;${fg};2m█─😀\x1b[4:3;53;9m  `;
          const actual = await paint(`\x1b[H\x1b[0;38;2;${fg}mA\r\n\x1b[2mB\r\n${suffix}`);
          const b = !bg || faint !== 0 ? `\x1b[22;38;2;${corrected}mB` : `\x1b[2;38;2;${fg}mB`;
          const expected = await paint(`\x1b[H\x1b[0;38;2;${corrected}mA\r\n${b}\r\n${suffix}`);
          compare(actual, expected, `bg=${bg}, image=${image}, faint=${faint}`);
          document.querySelector('[data-terminal]').style.display = 'none'; await wait();
          document.querySelector('[data-terminal]').style.display = ''; await wait(); await wait();
          compare(capture(), actual, 'hide/restore'); checked++;
        }
        const input = document.querySelector('[data-terminal] textarea');
        input.blur(); input.focus(); await wait();
        for (const text of ['A', '界', '█', '😀']) {
          api.reset(); api.minimumContrast(1); api.faintOpacity(0);
          const normal = await paint(`\x1b[2 q\x1b[?25h\x1b[4;53;9;58;2;255;0;0m${text}\x1b[H`);
          compare(await paint(`\x1b[2m${text}\x1b[H`), normal, `Opaque block cursor ${text}`);
        }
        // The decoration sprites use the grayscale shader even over excluded graphic text.
        api.reset(); api.minimumContrast(7); api.faintOpacity(0.5);
        const decorated = await paint('\x1b[?25l\x1b]11;#000000\x07\x1b[2;4:3;53;9;38;2;16;16;16;58;2;16;16;16m  ');
        compare(await paint('\x1b[H\x1b[22;38;2;255;255;255;58;2;255;255;255m  '), decorated, 'Decoration contrast');
        // An update on a different row must not erase or repaint the retained first row.
        const beforeRetained = capture(); await paint('\x1b[3;1Hrow three');
        const afterRetained = capture(), rowHeight = Math.ceil(api.cellMetrics().height * devicePixelRatio);
        for (let y = 0; y < rowHeight; y++) {
          const row = renderer === 'gpu' ? canvas.height - 1 - y : y;
          const start = row * canvas.width * 4;
          for (let i = start; i < start + canvas.width * 4; i++) if (beforeRetained[i] !== afterRetained[i]) throw new Error('Clean row was changed');
        }
        // Actual monochrome and intrinsic gray COLRv0 artwork in the same font/run.
        api.reset(); api.faintOpacity(1); api.minimumContrast(1.000001);
        const bytes = new Uint8Array(await (await fetch('/kinetica-terminal/test-upstream/color-fonts/colrv0.ttf')).arrayBuffer());
        if (fonts === 'system') {
          const face = await new FontFace('ContrastProbe', bytes).load(); document.fonts.add(face);
          api.fontFamily('ContrastProbe'); api.fontSize(20);
          const raw = await paint('\x1b[?25l\x1b]11;#000000\x07\x1b[38;2;16;16;16mBF');
          api.minimumContrast(7); await wait(); const fixed = capture();
          const cellWidth = api.cellMetrics().width * devicePixelRatio;
          let changed = 0;
          for (let i = 0; i < raw.length; i++) {
            const x = Math.floor(i / 4) % canvas.width;
            if (x < Math.floor(cellWidth) && raw[i] !== fixed[i]) throw new Error('System-font gray COLR artwork was tinted');
            if (x >= Math.ceil(cellWidth) && raw[i] !== fixed[i]) changed++;
          }
          if (changed < 10) throw new Error('System-font monochrome glyph was not corrected');
          api.fontFamily(null); api.reset(); api.minimumContrast(1.000001);
        }
        await api.shapingFonts('/kinetica-terminal/web/harfbuzz/terminal-harfbuzz.mjs', [{ family: 'Fixture', data: bytes }]);
        api.fontSize(20);
        const before = await paint('\x1b[?25l\x1b]11;#000000\x07\x1b[38;2;16;16;16mABCDEF');
        api.minimumContrast(7); await wait(); const after = capture();
        // F is monochrome and must change; A..E are intrinsic color, including gray B
        // and currentColor-only D, and must keep exactly the original artwork.
        const cw = api.cellMetrics().width * devicePixelRatio;
        let changed = 0;
        for (let i = 0; i < before.length; i++) {
          const x = Math.floor(i / 4) % canvas.width;
          if (x < Math.floor(5*cw) && before[i] !== after[i]) throw new Error(`Color artwork tinted at ${x}`);
          if (x >= Math.ceil(5*cw) && before[i] !== after[i]) changed++;
        }
        if (changed < 10) throw new Error('Monochrome fixture must visibly change');
        api.minimumContrast(1); api.faintOpacity(0);
        const faintColor = await paint('\x1b[H\x1b[2mABCDEF');
        for (let i = 0; i < before.length; i++) {
          const x = Math.floor(i / 4) % canvas.width;
          if (x < Math.floor(5*cw) && before[i] !== faintColor[i]) throw new Error('Faint changed intrinsic COLR alpha');
          if (x >= Math.ceil(5*cw) && x < Math.floor(6*cw) && i%4 !== 3 && faintColor[i] !== 0) throw new Error('Zero-opacity text must disappear');
        }
        api.faintOpacity(1); await paint('\x1b[H\x1b[22mABCDEF');
        api.minimumContrast(1); await wait(); api.minimumContrast(7); await wait();
        compare(capture(), after, 'disable/re-enable');
        return { checked, changed };
      }, { renderer, fonts });
      assert.deepEqual(errors, []); console.log(renderer, 'DPR', scale, fonts, result);
    } finally { await page.close(); }
  }
} finally { await browser.close(); }
