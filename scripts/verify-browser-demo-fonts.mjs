import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';

const fontRoot=new URL('../samples/browser-terminal/web/fonts/',import.meta.url);
const manifest=JSON.parse(await readFile(new URL('manifest.json',fontRoot)));
for(const source of manifest.fonts){
 const bytes=await readFile(new URL(source.file,fontRoot));
 assert.equal(bytes.length,source.bytes);assert.equal(createHash('sha256').update(bytes).digest('hex'),source.sha256);
 assert.equal('sha256-'+createHash('sha256').update(bytes).digest('base64'),source.integrity);
}
for(const [name,expected] of Object.entries(manifest.licenses))assert.equal(createHash('sha256').update(await readFile(new URL(name,fontRoot))).digest('hex'),expected);
const runtime=await readFile(new URL('../kinetica-terminal/web/harfbuzz/terminal-harfbuzz.mjs',import.meta.url),'utf8');
const probe='shape(text, features = []) {';
assert(runtime.includes(probe));
const instrumented=runtime.replace(probe,probe+'\n(globalThis.demoFontRuns ??= []).push({text, bytes:length});');
const {chromium}=await import(process.env.PLAYWRIGHT_IMPORT??'playwright');
const browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE});
const base=process.env.KINETICA_BROWSER_BASE_URL??'http://127.0.0.1:4173';
const url=`${base}/samples/browser-terminal/web/index.html`;
try{
 const page=await browser.newPage({viewport:{width:1100,height:600},deviceScaleFactor:2});
 const errors=[],requests=[];page.on('pageerror',error=>errors.push(error.message));page.on('request',request=>requests.push(request.url()));
 await page.route('**/terminal-harfbuzz.mjs',route=>route.fulfill({contentType:'text/javascript',body:instrumented}));
 await page.goto(url);await page.waitForFunction(()=>globalThis.kineticaTerminalDemo);
 const paint=()=>page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
 await paint();
 const total=manifest.fonts.reduce((sum,font)=>sum+font.bytes,0);
 const initial=await page.evaluate(()=>({mode:kineticaTerminalDemo.fontMode,error:kineticaTerminalDemo.fontError,
  data:{...document.querySelector('[data-terminal]').dataset},faces:document.fonts.size,metrics:kineticaTerminalDemo.cellMetrics()}));
 assert.equal(initial.mode,'bundled');assert.equal(initial.error,undefined);assert.equal(initial.data.terminalShaping,'true');
 assert.equal(initial.data.terminalRenderer,'webgl2');assert.equal(+initial.data.terminalFonts,7);assert.equal(+initial.data.terminalFontBytes,total);
 assert(+initial.data.terminalFontMemory<=128*1024*1024);assert.equal(initial.faces,7);
 assert(requests.every(request=>new URL(request).origin===new URL(base).origin),'Font startup must be entirely self-hosted');
 assert.equal(requests.filter(request=>/\.(ttf|otf)$/.test(new URL(request).pathname)).length,7);
 await page.evaluate(()=>{demoFontRuns.length=0;kineticaTerminalDemo.reset();kineticaTerminalDemo.write('\x1b[?25lR\x1b[1mB\x1b[3mQ\x1b[22mI\x1b[0m\r\n世界日本한국\r\n😀');});await paint();
 const runs=await page.evaluate(()=>demoFontRuns);
 for(const [text,style] of [['R',0],['B',1],['I',4],['Q',5]])assert(runs.some(run=>run.text.includes(text)&&run.bytes===manifest.fonts.find(font=>font.family==='JetBrains Mono'&&font.style===style).bytes),`Use the actual ${style} face`);
 assert(runs.some(run=>run.text.includes('世界日本한국')&&run.bytes===manifest.fonts.find(font=>font.family==='Noto Sans Mono CJK SC').bytes),'CJK must reach its fallback face without replacement');
 assert(runs.some(run=>run.text.includes('😀')&&run.bytes===manifest.fonts.find(font=>font.emoji).bytes),'Emoji must reach its color face');
 // Real pointer selection must reshape the active default font run.
 await page.evaluate(()=>{kineticaTerminalDemo.reset();kineticaTerminalDemo.write('\x1b[?25la===>b');});await paint();
 const geometry=await page.evaluate(()=>{const rect=document.querySelector('canvas').getBoundingClientRect();return{x:rect.x,y:rect.y,...kineticaTerminalDemo.cellMetrics()};});
 await page.mouse.move(geometry.x+2.25*geometry.width,geometry.y+0.5*geometry.height);await page.mouse.down();
 await page.mouse.move(geometry.x+4.75*geometry.width,geometry.y+0.5*geometry.height,{steps:5});await page.mouse.up();await paint();
 await page.evaluate(()=>{demoFontRuns.length=0;kineticaTerminalDemo.configureTheme(0x1e1e1f);});await paint();
 const selected=await page.evaluate(()=>demoFontRuns);
 assert(selected.length>0);assert(!selected.some(run=>run.text==='a===>b'),'Selection must split the ligature run');
 await page.mouse.click(geometry.x+0.25*geometry.width,geometry.y+0.5*geometry.height);
 await page.evaluate(()=>{demoFontRuns.length=0;kineticaTerminalDemo.configureTheme(0x1e1e1e);});await paint();
 assert((await page.evaluate(()=>demoFontRuns)).some(run=>run.text==='a===>b'),'Clearing selection must restore the complete run');
 // The entire long run reaches HarfBuzz, while its atlas remains glyph-sized.
 await page.setViewportSize({width:5000,height:300});
 await page.evaluate(()=>{demoFontRuns.length=0;kineticaTerminalDemo.fontSize(15);kineticaTerminalDemo.reset();kineticaTerminalDemo.write('\x1b[?25l'+'='.repeat(513));});await paint();
 assert((await page.evaluate(()=>demoFontRuns)).some(run=>run.text==='='.repeat(513)),'Do not split shaping context to fit atlas tiles');
 assert(+(await page.locator('[data-terminal]').getAttribute('data-terminal-glyphs'))<32,'A long ligature run must reuse a bounded number of atlas entries');
 assert.equal(await page.evaluate(()=>document.querySelector('canvas').getContext('webgl2').getError()),0);
 await page.evaluate(()=>kineticaTerminalDemo.dispose());assert.equal(await page.evaluate(()=>document.fonts.size),0);
 assert.deepEqual(errors,[]);await page.close();
 // Opt-out and network/integrity failures keep an immediately usable system-font terminal.
 for(const mode of ['system','missing','corrupt']){
  const page=await browser.newPage();const errors=[],requests=[];page.on('pageerror',error=>errors.push(error.message));page.on('request',request=>requests.push(request.url()));
  if(mode==='missing')await page.route('**/fonts/JetBrainsMonoNerdFont-Regular.ttf*',route=>route.fulfill({status:404,body:'missing'}));
  if(mode==='corrupt')await page.route('**/fonts/JetBrainsMonoNerdFont-Regular.ttf*',route=>route.fulfill({status:200,contentType:'font/ttf',body:'corrupt'}));
  await page.goto(url+(mode==='system'?'?fonts=system':''));await page.waitForFunction(()=>globalThis.kineticaTerminalDemo);
  const result=await page.evaluate(()=>{kineticaTerminalDemo.reset();kineticaTerminalDemo.write('USABLE');return{mode:kineticaTerminalDemo.fontMode,error:kineticaTerminalDemo.fontError,screen:kineticaTerminalDemo.screen(),faces:document.fonts.size};});
  assert.equal(result.mode,'system');assert(result.screen.includes('USABLE'));assert.equal(result.faces,0);
  if(mode==='system'){assert.equal(result.error,undefined);assert(!requests.some(url=>/\.(ttf|otf|wasm)$/.test(new URL(url).pathname)));}
  else {assert(result.error);assert.equal(await page.locator('#font-status').textContent(),'Fonts unavailable; using system fonts.');}
  assert.deepEqual(errors,[]);await page.close();
 }
 console.log(`Browser default fonts: 7 pinned faces / ${total} bytes; real styles, CJK, emoji, selection, 513-character shaping, disposal, opt-out, missing-file and SRI failure passed`);
}finally{await browser.close();}
