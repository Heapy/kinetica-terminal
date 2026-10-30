import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';

const base = process.env.KINETICA_BROWSER_BASE_URL ?? 'http://127.0.0.1:4173';
const names = ['JetBrainsMonoNoNF-Regular.ttf', 'NotoColorEmoji.ttf', 'NotoEmoji-Regular.ttf'];
const manifest = JSON.parse(await readFile(new URL('../kinetica-terminal/test-upstream/ghostty/shaping-manifest.json', import.meta.url)));
for (const name of names) {
  const bytes = await readFile(new URL(`../build/upstream/ghostty-1.4.0/src/font/res/${name}`, import.meta.url));
  assert.equal(createHash('sha256').update(bytes).digest('hex'), manifest.sources[`src/font/res/${name}`]);
}
const instrumentation = `
import {createTerminalHarfBuzz as create} from '/kinetica-terminal/web/harfbuzz/terminal-harfbuzz.mjs';
export async function createTerminalHarfBuzz() {
 const runtime = await create();
 const entry = {runtime, faces:[], calls:[]};
 (globalThis.shapingTrace ??= []).push(entry);
 const createFont = runtime.createFont.bind(runtime);
 runtime.createFont = (bytes, pixels) => {
  const font = createFont(bytes, pixels);
  const record = {font, bytes:bytes.length, pixels, disposed:false}; entry.faces.push(record);
  for (const name of ['shape','outline','colorPng']) {
   const original = font[name].bind(font);
   font[name] = (...args) => { const result = original(...args); entry.calls.push({name,bytes:bytes.length,args,result}); return result; };
  }
  const dispose = font.dispose.bind(font); font.dispose = () => {record.disposed=true; dispose();};
  return font;
 };
 return runtime;
}`;
const { chromium } = await import(process.env.PLAYWRIGHT_IMPORT ?? 'playwright');
const browser = await chromium.launch({ headless: true, executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE });
try {
 for (const ratio of [1, 1.25, 2]) {
  const page = await browser.newPage({viewport:{width:1100,height:600},deviceScaleFactor:ratio});
  const errors=[];page.on('pageerror', error=>errors.push(error.message));
  await page.route('**/terminal-shaper-instrumented.mjs', route=>route.fulfill({contentType:'text/javascript',body:instrumentation}));
  try {
   await page.goto(`${base}/samples/browser-terminal/web/index.html?renderer=gpu&fonts=system`);
   await page.waitForFunction(()=>globalThis.kineticaTerminalDemo);
   const result=await page.evaluate(async ({names})=>{
    const check=(condition,message)=>{if(!condition)throw new Error(message);};
    const wait=()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
    const demo=globalThis.kineticaTerminalDemo, root=document.querySelector('[data-terminal]');
    const originalCssFaces=document.fonts.size;
    const fonts=await Promise.all(names.map(async(name,index)=>({family:`Shaping ${index}`,emoji:index===1,
     data:new Uint8Array(await(await fetch(`/build/upstream/ghostty-1.4.0/src/font/res/${name}`)).arrayBuffer())})));
    await demo.shapingFonts('/terminal-shaper-instrumented.mjs',fonts);
    demo.reset();demo.fontSize(24);demo.write('\x1b[?25l');await wait();
    const trace=globalThis.shapingTrace.at(-1);
    check(root.dataset.terminalShaping==='true','Active renderer must use HarfBuzz');
    check(root.dataset.terminalRenderer==='webgl2','GPU checks cannot pass through Canvas fallback');
    check(trace.runtime.fontCount===3,'Own three active faces');
    check(document.fonts.size===originalCssFaces+3,'Register owned CSS faces for metrics/IME');
    const face=trace.faces.findLast(face=>face.bytes===fonts[0].data.length&&!face.disposed).font;
    const alias=[...document.fonts].find(font=>font.family.startsWith('kinetica-terminal-')).family;
    const measure=document.createElement('canvas').getContext('2d');measure.font=`24px "${alias}"`;
    const cellWidth=measure.measureText('M').width, cellHeight=Math.ceil(24*1.3);
    const gl=root.querySelector('canvas').getContext('webgl2');
    const width=Math.ceil(cellWidth*40*devicePixelRatio),height=Math.ceil(cellHeight*4*devicePixelRatio);
    let pixels=null;const draw=gl.drawArraysInstanced;
    gl.drawArraysInstanced=function(...args){draw.apply(gl,args);pixels=new Uint8Array(width*height*4);
     gl.readPixels(0,gl.drawingBufferHeight-height,width,height,gl.RGBA,gl.UNSIGNED_BYTE,pixels);};
    const snapshot=()=>{check(pixels!==null,'A frame must reach WebGL');return pixels.slice();};
    const paint=async text=>{trace.calls.length=0;pixels=null;demo.reset();demo.write('\x1b[?25l'+text);await wait();return snapshot();};
    try {
     const plain=await paint('Fgé\r\n'+'0'.repeat(40)+'\r\na===>b\r\n😀 👍🏽 ❤️ ❤︎');
     const shapes=trace.calls.filter(call=>call.name==='shape');
     check(shapes.some(call=>call.args[0].includes('a===>b')),'A complete ligature run must reach HarfBuzz');
     check(shapes.some(call=>call.bytes===fonts[1].data.length&&call.args[0].includes('❤')),'VS16 must choose the color face');
     check(shapes.some(call=>call.bytes===fonts[2].data.length&&call.args[0].includes('❤')),'VS15 must choose the text face');
     check(trace.calls.some(call=>call.name==='colorPng'&&call.result?.length>0),'Color glyphs must use actual PNG data');
     const pixel=(data,x,y,c)=>data[((height-1-y)*width+x)*4+c];
     const rounded=value=>value%1===0.5?Math.round(value/2)*2:Math.round(value);
     const row=Math.round(cellHeight*devicePixelRatio);
     let ink=0,color=0;
     for(let y=0;y<height;y++)for(let x=0;x<width;x++){
      if(pixel(plain,x,y,0)>100)ink++;
      if(y>=3*cellHeight*devicePixelRatio&&Math.max(...[0,1,2].map(c=>pixel(plain,x,y,c)))-Math.min(...[0,1,2].map(c=>pixel(plain,x,y,c)))>40)color++;
     }
     check(ink>100,'Real shaped glyphs must produce visible pixels');check(color>30,'Emoji must reach the screen in color');
     for(let column=1;column<40;column++)for(let y=0;y<Math.floor(cellHeight*devicePixelRatio);y++)for(let x=0;x<Math.floor(cellWidth*devicePixelRatio);x++)for(let c=0;c<4;c++){
      check(pixel(plain,rounded(column*cellWidth*devicePixelRatio)+x,row+y,c)===pixel(plain,x,row+y,c),`Glyph sampling changed at column ${column}, DPR ${devicePixelRatio}`);
     }
     // Independent whole-canvas outline reference catches bearings, inverted Y,
     // clipping, baseline and GPU tint/UV errors in the complete raster path.
     const reference=document.createElement('canvas');reference.width=width;reference.height=Math.ceil(cellHeight*devicePixelRatio);
     // Use the same CPU rasterizer; Chromium's GPU path uses different curve antialiasing.
     const ctx=reference.getContext('2d',{willReadFrequently:true});ctx.fillStyle='#1e1e1e';ctx.fillRect(0,0,reference.width,reference.height);
     const metrics=face.horizontalExtents(),baseline=Math.round((cellHeight*devicePixelRatio+(metrics.ascender+metrics.descender)/64)/2);
     for(const [column,text] of [...'Fgé'].entries()){
      ctx.save();ctx.beginPath();ctx.rect(rounded(column*cellWidth*devicePixelRatio),0,rounded((column+1)*cellWidth*devicePixelRatio)-rounded(column*cellWidth*devicePixelRatio),reference.height);ctx.clip();
      ctx.setTransform(1/64,0,0,-1/64,rounded(column*cellWidth*devicePixelRatio),baseline);ctx.fillStyle='#d4d4d4';ctx.beginPath();
      const outline=face.outline(face.nominalGlyph(text.codePointAt(0)));let i=0;
      while(i<outline.length){const op=outline[i++];if(op===0)ctx.moveTo(outline[i++],outline[i++]);else if(op===1)ctx.lineTo(outline[i++],outline[i++]);else if(op===2)ctx.quadraticCurveTo(outline[i++],outline[i++],outline[i++],outline[i++]);else if(op===3)ctx.bezierCurveTo(outline[i++],outline[i++],outline[i++],outline[i++],outline[i++],outline[i++]);else if(op===4)ctx.closePath();else throw new Error('Invalid reference outline');}
      ctx.fill();ctx.restore();
     }
     const expected=ctx.getImageData(0,0,reference.width,reference.height).data;
     let worst=0, mismatch=null;
     for(let y=0;y<reference.height;y++)for(let x=0;x<Math.floor(cellWidth*3*devicePixelRatio);x++)for(let c=0;c<3;c++){const delta=Math.abs(pixel(plain,x,y,c)-expected[(y*width+x)*4+c]);if(delta>worst){worst=delta;mismatch={x,y,c,actual:pixel(plain,x,y,c),expected:expected[(y*width+x)*4+c]};}}
     check(worst<=2,`Outline-to-WebGL pixel mismatch: ${worst}, ${JSON.stringify(mismatch)}`);
     await paint('a\x1b[41m===>\x1b[49mb');
     check(trace.calls.some(call=>call.name==='shape'&&call.args[0]==='a===>b'),'Background changes must preserve ligatures');
     await paint('a\x1b[1m===>\x1b[0mb');
     check(!trace.calls.some(call=>call.name==='shape'&&call.args[0]==='a===>b'),'Style changes must split shaping runs');
     await paint('a===>b\x1b[?25h\x1b[1;3H');
     check(!trace.calls.some(call=>call.name==='shape'&&call.args[0]==='a===>b'),'Visible cursor must split shaping runs');
     // Invalid replacement must retain the working fonts, pixels and CSS faces.
     let failed=false;try{await demo.shapingFonts('/terminal-shaper-instrumented.mjs',[{family:'Invalid',data:new Uint8Array(32)}]);}catch{failed=true;}
     check(failed,'Reject malformed font');check(trace.runtime.fontCount===3,'Failed replacement must retain the active faces');
     check(globalThis.shapingTrace.at(-1).runtime.memoryBytes===0,'Failed replacement must dispose the new runtime');
     check(document.fonts.size===originalCssFaces+3,'Failed replacement must not leak CSS faces');
     // Hidden surfaces release active HarfBuzz faces and recreate them on restore.
     root.style.display='none';await wait();check(trace.runtime.fontCount===0,'Hide must release shaping faces');
     root.style.display='';await wait();await wait();check(trace.runtime.fontCount===3,'Restore must recreate shaping faces');
     demo.fontFamily('Unlisted system family');await wait();check(trace.runtime.fontCount===0&&root.dataset.terminalShaping==='false','Unlisted families must return to CSS rendering');
     demo.fontFamily(null);await wait();check(trace.runtime.fontCount===3,'Returning to the default must restore shaping');
     const oldFaces=trace.faces.filter(face=>!face.disposed);demo.fontSize(28);await wait();
     check(oldFaces.every(face=>face.disposed)&&trace.runtime.fontCount===3,'Changing size must replace, not retain, faces');
     const extension=gl.getExtension('WEBGL_lose_context');extension.loseContext();await new Promise(r=>setTimeout(r,50));
     check(trace.runtime.fontCount===0,'Context loss must release shaping faces');extension.restoreContext();await new Promise(r=>setTimeout(r,100));await wait();
     check(trace.runtime.fontCount===3&&root.dataset.terminalShaping==='true','Context restoration must restore shaping');
     demo.clearShapingFonts();await wait();check(trace.runtime.memoryBytes===0,'Clearing fonts must release WASM memory');
     check(document.fonts.size===originalCssFaces,'Clearing fonts must unregister owned CSS faces');
     check(root.dataset.terminalShaping==='false','Clearing fonts must restore CSS rendering');
     // A superseded async request cannot install fonts after clear.
     const stale=demo.shapingFonts('/terminal-shaper-instrumented.mjs',fonts);demo.clearShapingFonts();
     failed=false;try{await stale;}catch{failed=true;}check(failed,'Superseded load must reject');
     check(globalThis.shapingTrace.at(-1).runtime.memoryBytes===0&&document.fonts.size===originalCssFaces,'Superseded load must release all resources');
     await demo.shapingFonts('/terminal-shaper-instrumented.mjs',fonts);await wait();const owned=globalThis.shapingTrace.at(-1);
     const unfinished=demo.shapingFonts('/terminal-shaper-instrumented.mjs',fonts);
     demo.dispose();check(owned.runtime.memoryBytes===0&&document.fonts.size===originalCssFaces,'View disposal must release font resources');
     failed=false;try{await unfinished;}catch{failed=true;}check(failed,'Disposal must supersede unfinished font loads');
     check(globalThis.shapingTrace.every(entry=>entry.runtime.memoryBytes===0)&&document.fonts.size===originalCssFaces,'No disposed or superseded view may retain font resources');
     return {ratio:devicePixelRatio,ink,color,worst};
    } finally {gl.drawArraysInstanced=draw;}
   },{names});
   assert.deepEqual(errors,[]);console.log('WebGL shaping:',result);
  } finally {await page.close();}
 }
} finally {await browser.close();}
