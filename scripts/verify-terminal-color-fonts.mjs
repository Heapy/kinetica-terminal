import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {createTerminalHarfBuzz} from '../kinetica-terminal/web/harfbuzz/terminal-harfbuzz.mjs';

const root=new URL('../',import.meta.url),directory='kinetica-terminal/test-upstream/color-fonts/';
const manifest=JSON.parse(await readFile(new URL(directory+'manifest.json',root)));
const fontBytes=await readFile(new URL(directory+'colrv0.ttf',root));
assert.equal(createHash('sha256').update(fontBytes).digest('hex'),manifest.fontSha256);
const runtime=await createTerminalHarfBuzz({wasmBytes:await readFile(new URL('kinetica-terminal/web/harfbuzz/harfbuzz.wasm',root))});
const font=runtime.createFont(fontBytes,20);
assert.equal(font.hasColorLayers,true);
for(const [character,layers] of Object.entries(manifest.layers)) {
 const glyph=font.nominalGlyph(character.codePointAt(0));
 assert.deepEqual(font.colorLayers(glyph),layers.map(([name,index])=>({glyph:manifest.glyphOrder.indexOf(name),color:index===65535?null:manifest.palette[index]})));
 const result=font.colorLayers(glyph);result[0].glyph=0;if(result[0].color)result[0].color.fill(0);
 assert.notEqual(font.colorLayers(glyph)[0].glyph,0,'Color layer results must not alias retained state');
}
assert.equal(font.colorLayers(font.nominalGlyph(70)),null);
assert.throws(()=>font.colorLayers(-1));
font.dispose();assert.throws(()=>font.colorLayers(4));runtime.dispose();

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
    const bytes=new Uint8Array(await(await fetch('/'+directory+'colrv0.ttf')).arrayBuffer());
    await demo.shapingFonts('/kinetica-terminal/web/harfbuzz/terminal-harfbuzz.mjs',[{family:'Fixture',data:bytes}]);
    demo.fontSize(20);demo.reset();demo.write('\x1b[?25l');await wait();
    check(host.dataset.terminalRenderer==='webgl2'&&host.dataset.terminalShaping==='true','Use the actual shaped GPU surface');
    const cell=demo.cellMetrics(),cw=cell.width*devicePixelRatio,ch=cell.height*devicePixelRatio;
    const canvas=host.querySelector('canvas'),gl=canvas.getContext('webgl2');
    const width=Math.ceil(cw*8),height=Math.ceil(ch),draw=gl.drawArraysInstanced;let pixels=null;
    gl.drawArraysInstanced=function(...args){draw.apply(gl,args);pixels=new Uint8Array(width*height*4);gl.readPixels(0,gl.drawingBufferHeight-height,width,height,gl.RGBA,gl.UNSIGNED_BYTE,pixels);};
    const rounded=value=>value%1===0.5?Math.round(value/2)*2:Math.round(value);
    const palette=[0x2255ee,0xee3322],characters='AABCDEFD';
    const paint=async (swap=false)=>{
     demo.reset();demo.write('\x1b[?25l'+[...characters].map((c,i)=>{const color=palette[(i+(swap?1:0))%2];return `\x1b[38;2;${color>>16};${color>>8&255};${color&255}m${c}`;}).join(''));
     await wait();check(pixels!==null,'Read back actual GPU draw');return pixels.slice();
    };
    const reference=document.createElement('canvas');reference.width=width;reference.height=height;
    const ctx=reference.getContext('2d',{willReadFrequently:true});
    const physicalSize=20*devicePixelRatio,scale=physicalSize/manifest.unitsPerEm;
    const ascender=Math.round(manifest.ascender*scale*64)/64,descender=Math.round(manifest.descender*scale*64)/64;
    const baseline=Math.round((ch+ascender+descender)/2);
    const cssFamily=[...document.fonts].find(face=>face.family.startsWith('kinetica-terminal-')).family;
    let worst=0;
    const compare=(actual,swap,colorOnly=false)=>{
     ctx.setTransform(1,0,0,1,0,0);ctx.fillStyle='#1e1e1e';ctx.fillRect(0,0,width,height);
     for(let column=0;column<characters.length;column++){
      const color=palette[(column+(swap?1:0))%2];
      const layers=manifest.layers[characters[column]]??[['letter',65535]];
      ctx.save();ctx.translate(rounded(column*cw),baseline);ctx.scale(scale,-scale);
      for(const [name,index] of layers){
       const rgba=index===65535?[color>>16,color>>8&255,color&255,255]:manifest.palette[index];
       const [x0,y0,x1,y1]=manifest.rectangles[name];ctx.fillStyle=`rgba(${rgba[0]},${rgba[1]},${rgba[2]},${rgba[3]/255})`;
       ctx.beginPath();ctx.moveTo(x0,y0);ctx.lineTo(x0,y1);ctx.lineTo(x1,y1);ctx.lineTo(x1,y0);ctx.closePath();ctx.fill();
      }
      ctx.restore();
     }
     const expected=ctx.getImageData(0,0,width,height).data;
     let mismatch=null;
     for(let y=0;y<height;y++)for(let x=0;x<width;x++)for(let c=0;c<3;c++){
      // Native Canvas may hint monochrome F differently from our outline path.
      if(colorOnly&&x>=rounded(6*cw)&&x<rounded(7*cw))continue;
      const a=actual[((height-y-1)*width+x)*4+c],e=expected[(y*width+x)*4+c],delta=Math.abs(a-e);
      if(delta>worst){worst=delta;mismatch={x,y,c,actual:a,expected:e};}
     }
     check(worst<=3,`COLR pixels differ at DPR ${devicePixelRatio}: ${worst}, ${JSON.stringify(mismatch)}`);
    };
    try {
     const first=await paint();compare(first,false);
     // Ghostty's web_canvas backend delegates to fillText. Validate this same
     // native-browser path against the independently authored layer geometry.
     ctx.setTransform(1,0,0,1,0,0);ctx.fillStyle='#1e1e1e';ctx.fillRect(0,0,width,height);
     ctx.font=`${physicalSize}px "${cssFamily}"`;ctx.textBaseline='alphabetic';
     for(let column=0;column<characters.length;column++){
      ctx.fillStyle='#'+palette[column%2].toString(16).padStart(6,'0');
      ctx.fillText(characters[column],rounded(column*cw),baseline);
     }
     const native=ctx.getImageData(0,0,width,height).data,nativeFlipped=new Uint8Array(native.length);
     for(let y=0;y<height;y++)nativeFlipped.set(native.subarray(y*width*4,(y+1)*width*4),(height-y-1)*width*4);
     compare(nativeFlipped,false,true);
     compare(await paint(true),true); // Reuse glyph IDs with different SGR foregrounds.
     compare(await paint(false),false);
     // Reset the primary metrics/atlas, then restore and compare again.
     demo.fontSize(24);await wait();demo.fontSize(20);await wait();compare(await paint(),false);
     host.style.display='none';await wait();host.style.display='';await wait();await wait();compare(await paint(),false);
     demo.dispose();return {ratio:devicePixelRatio,worst};
    } finally {gl.drawArraysInstanced=draw;}
   },{manifest,directory});
   assert.deepEqual(errors,[]);console.log('COLRv0 WebGL:',result);
  } finally {await page.close();}
 }
} finally {await browser.close();}
