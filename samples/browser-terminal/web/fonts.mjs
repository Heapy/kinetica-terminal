// Demo-owned font delivery. The standalone terminal accepts bytes and owns its loaded resources.
export async function configureDemoFonts(terminal) {
  const manifestUrl = new URL('./fonts/manifest.json', import.meta.url);
  const response = await fetch(manifestUrl, { cache: 'no-cache' });
  if (!response.ok) throw new Error(`Cannot load terminal font manifest (${response.status})`);
  const manifest = await response.json();
  const controller = new AbortController();
  try {
    const fonts = await Promise.all(manifest.fonts.map(async source => {
      const url = new URL(source.file, manifestUrl);
      url.searchParams.set('v', source.sha256);
      const response = await fetch(url, { integrity: source.integrity, signal: controller.signal });
      if (!response.ok) throw new Error(`Cannot load ${source.file} (${response.status})`);
      const data = new Uint8Array(await response.arrayBuffer());
      if (data.length !== source.bytes) throw new Error(`Invalid font size: ${source.file}`);
      return { ...source, data };
    }));
    await terminal.shapingFonts(new URL('../../../kinetica-terminal/web/harfbuzz/terminal-harfbuzz.mjs', import.meta.url).href, fonts);
  } catch (error) { controller.abort(); throw error; }
}
