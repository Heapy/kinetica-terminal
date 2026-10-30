import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {createTerminalHarfBuzz} from '../kinetica-terminal/web/harfbuzz/terminal-harfbuzz.mjs';

const root=new URL('../',import.meta.url),directory='kinetica-terminal/test-upstream/color-fonts/';
const manifest=JSON.parse(await readFile(new URL(directory+'sbix-manifest.json',root)));
const bytes=await readFile(new URL(directory+'sbix.ttf',root));
assert.equal(createHash('sha256').update(bytes).digest('hex'),manifest.fontSha256);
const runtime=await createTerminalHarfBuzz({wasmBytes:await readFile(new URL('kinetica-terminal/web/harfbuzz/harfbuzz.wasm',root))});
try {
 const font=runtime.createFont(bytes);
 for(const size of [20,21,25,30,40,60]) {
  font.setSize(size);
  const strike=manifest.strikes.find(value=>value.ppem>=size)??manifest.strikes.at(-1);
  for(const character of 'ABCD') {
   const image=strike.images[character==='C'?'A':character],glyph=font.nominalGlyph(character.codePointAt(0));
   assert.deepEqual(Buffer.from(font.colorPng(glyph)),Buffer.from(image.pngBase64,'base64'),`${character} at ${size}px selects original strike and dupe bytes`);
   assert.ok(font.extents(glyph).height<0);
  }
 }
 assert.equal(font.colorPng(font.nominalGlyph(70)),null);
} finally {runtime.dispose();}

const {chromium}=await import(process.env.PLAYWRIGHT_IMPORT??'playwright');
const browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE});
try {
 for(const ratio of [1,1.25,2]) {
  const page=await browser.newPage({viewport:{width:1000,height:500},deviceScaleFactor:ratio});
  const errors=[];page.on('pageerror',error=>errors.push(error.message));
  try {
   await page.goto(`${process.env.KINETICA_BROWSER_BASE_URL??'http://127.0.0.1:4173'}/samples/browser-terminal/web/index.html?renderer=gpu&fonts=system`);
   await page.waitForFunction(()=>globalThis.kineticaTerminalDemo);
   const result=await page.evaluate(async ({manifest,directory})=>{
    const check=(condition,message)=>{if(!condition)throw new Error(message);};
    const wait=()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
    const demo=globalThis.kineticaTerminalDemo,host=document.querySelector('[data-terminal]');
    const bytes=new Uint8Array(await(await fetch('/'+directory+'sbix.ttf')).arrayBuffer());
    await demo.shapingFonts('/kinetica-terminal/web/harfbuzz/terminal-harfbuzz.mjs',[{family:'Sbix',data:bytes}]);
    const gl=host.querySelector('canvas').getContext('webgl2'),draw=gl.drawArraysInstanced;
    let width=1,height=1,pixels=null;
    gl.drawArraysInstanced=function(...args){draw.apply(gl,args);pixels=new Uint8Array(width*height*4);gl.readPixels(0,gl.drawingBufferHeight-height,width,height,gl.RGBA,gl.UNSIGNED_BYTE,pixels);};
    const rounded=value=>value%1===0.5?Math.round(value/2)*2:Math.round(value);
    const results=[];
    try {
     for(const size of [20,30]) {
      demo.fontSize(size);demo.reset();demo.write('\x1b[?25l');await wait();
      check(host.dataset.terminalRenderer==='webgl2'&&host.dataset.terminalShaping==='true','No silent Canvas fallback');
      const cell=demo.cellMetrics(),cw=cell.width*devicePixelRatio,ch=cell.height*devicePixelRatio;
      width=Math.ceil(cw*4);height=Math.ceil(ch);
      const paint=async color=>{pixels=null;demo.write('\x1b[H\x1b[38;2;'+color+'mABCD');await wait();check(pixels!==null,'Actual GPU draw');return pixels.slice();};
      const red=await paint('240;10;10'),blue=await paint('10;10;240');
      check(red.every((value,i)=>value===blue[i]),'SGR must not tint gray or colored sbix PNGs');
      const physical=size*devicePixelRatio,strike=manifest.strikes.find(value=>value.ppem>=Math.round(physical))??manifest.strikes.at(-1);
      const reference=document.createElement('canvas');reference.width=width;reference.height=height;
      const ctx=reference.getContext('2d',{willReadFrequently:true});
      const baseline=Math.round((ch+physical*0.6)/2);
      for(const [column,character] of [...'ABCD'].entries()) {
       const image=strike.images[character==='C'?'A':character],scale=physical/strike.ppem;
       const source=document.createElement('canvas');source.width=image.width;source.height=image.height;
       const sourceCtx=source.getContext('2d',{willReadFrequently:true});const data=sourceCtx.createImageData(image.width,image.height);
       data.data.set(image.rgba);sourceCtx.putImageData(data,0,0);
       const left=image.origin[0]*scale,top=baseline-(image.origin[1]+image.height)*scale;
       // Resample in glyph-local coordinates before integer placement, as a GPU
       // atlas does. Whole-row Canvas transforms can choose different subpixel samples.
       const tile=document.createElement('canvas');tile.width=Math.ceil(image.width*scale)+3;tile.height=Math.ceil(image.height*scale)+3;
       const tileCtx=tile.getContext('2d',{willReadFrequently:true});
       tileCtx.drawImage(source,1+left-Math.floor(left),1+top-Math.floor(top),image.width*scale,image.height*scale);
       ctx.drawImage(tile,rounded(column*cw)+Math.floor(left)-1,Math.floor(top)-1);
      }
      const expected=ctx.getImageData(0,0,width,height).data;let worst=0,mismatch=null;
      for(let y=0;y<height;y++)for(let x=0;x<width;x++)for(let c=0;c<3;c++){
       // Compare premultiplied source-over explicitly, including transparent pixels.
       const index=(y*width+x)*4,alpha=expected[index+3]/255;
       const a=red[((height-y-1)*width+x)*4+c],e=Math.round(expected[index+c]*alpha+30*(1-alpha)),delta=Math.abs(a-e);
       if(delta>worst){worst=delta;mismatch={x,y,c,actual:a,expected:e};}
      }
      check(worst<=3,`sbix pixels differ at ${size}px / DPR ${devicePixelRatio}: ${worst}, ${JSON.stringify(mismatch)}`);
      results.push({size,strike:strike.ppem,worst});
     }
     demo.dispose();return {ratio:devicePixelRatio,results};
    } finally {gl.drawArraysInstanced=draw;}
   },{manifest,directory});
   assert.deepEqual(errors,[]);console.log('sbix WebGL:',result);
  } finally {await page.close();}
 }
} finally {await browser.close();}
