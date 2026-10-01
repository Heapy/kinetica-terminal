// Run against the release browser-terminal sample. This measures parser/buffer throughput,
// excluding the final paint; it is not a renderer or end-to-end input-latency benchmark.
const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? "playwright");
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
try {
  const page = await browser.newPage({ viewport: { width: 1100, height: 720 } });
  await page.goto(`${process.env.KINETICA_BROWSER_BASE_URL ?? "http://127.0.0.1:4173"}/samples/browser-terminal/web/index.html`);
  await page.waitForFunction(() => globalThis.kineticaTerminalDemo?.columns() > 80);
  const results = await page.evaluate(async () => {
    const workloads = {
      ascii: { line: "The quick brown fox jumps over the lazy dog. 0123456789\r\n", graphemes: false },
      colors: { line: "\u001b[31merror\u001b[0m \u001b[38;2;90;180;240mcolored output\u001b[0m\r\n", graphemes: false },
      unicode: { line: "世界 λ é 😀 terminal output\r\n", graphemes: false },
      graphemes: { line: "👨‍👩‍👧‍👦 🇺🇸 👍🏽 #️⃣ é क्ष\r\n", graphemes: true },
    };
    const results = [];
    for (const [name, { line, graphemes }] of Object.entries(workloads)) {
      kineticaTerminalDemo.write(graphemes ? "\u001b[?2027h" : "\u001b[?2027l");
      const chunk = line.repeat(1024);
      const bytes = new TextEncoder().encode(chunk).byteLength;
      for (let i = 0; i < 10; i++) kineticaTerminalDemo.write(chunk);
      const durations = [];
      for (let sample = 0; sample < 5; sample++) {
        const start = performance.now();
        for (let i = 0; i < 32; i++) kineticaTerminalDemo.write(chunk);
        durations.push(performance.now() - start);
        await new Promise(requestAnimationFrame);
      }
      durations.sort((a, b) => a - b);
      results.push({ workload: name, bytes: bytes * 32, medianMs: durations[2],
        utf8EquivalentMiBPerSecond: bytes * 32 / 1048576 / (durations[2] / 1000),
        input: "decoded String", graphemeClustering: graphemes,
        columns: kineticaTerminalDemo.columns(), rows: kineticaTerminalDemo.rows(), history: kineticaTerminalDemo.history() });
    }
    return results;
  });
  console.log(JSON.stringify({ engine: "Kinetica Kotlin/JS", scope: "parser + buffer + invalidation; paint excluded", results }, null, 2));
} finally { await browser.close(); }
