// Compare native Canvas and shaped WebGL support; --require-parity fails on a gap.
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';

const root=new URL('../',import.meta.url),directory='kinetica-terminal/test-upstream/color-fonts/';
const manifest=JSON.parse(await readFile(new URL(directory+'sbix-media-manifest.json',root)));
const bytes=await readFile(new URL(directory+'sbix-media.ttf',root));
assert.equal(createHash('sha256').update(bytes).digest('hex'),manifest.fontSha256);
const {chromium}=await import(process.env.PLAYWRIGHT_IMPORT??'playwright');
const browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE});
try {
 for(const ratio of [1,1.25,2]) {
 const page=await browser.newPage({viewport:{width:900,height:400},deviceScaleFactor:ratio});
 const errors=[];page.on('pageerror',error=>errors.push(error.message));
 await page.goto(`${process.env.KINETICA_BROWSER_BASE_URL??'http://127.0.0.1:4173'}/samples/browser-terminal/web/index.html?renderer=gpu&fonts=system`);
 await page.waitForFunction(()=>globalThis.kineticaTerminalDemo);
 const result=await page.evaluate(async({directory,manifest})=>{
  const check=(condition,message)=>{if(!condition)throw new Error(message);};
  const wait=()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
  const counts=pixels=>{
   let gray=0,blue=0,green=0;
   for(let i=0;i<pixels.length;i+=4)if(pixels[i+3]>128){
    const r=pixels[i],g=pixels[i+1],b=pixels[i+2];
    if(g>200&&r<40&&b<40)green++;
    if(b>200&&r<60&&g<100)blue++;
    if((Math.abs(r-96)<2||Math.abs(r-176)<2)&&Math.max(r,g,b)-Math.min(r,g,b)<=1)gray++;
   }
   return {gray,blue,green};
  };
  const source=new Uint8Array(await(await fetch('/'+directory+'sbix-media.ttf')).arrayBuffer());
  const canvas=document.createElement('canvas');canvas.width=100;canvas.height=40;
  const ctx=canvas.getContext('2d',{willReadFrequently:true});
  // Decode each original JPEG independently of CoreText/sbix and validate its pixels.
  for(const strike of manifest.strikes){
   const image=strike.images.A,encoded=Uint8Array.from(atob(image.base64),value=>value.charCodeAt(0));
   const bitmap=await createImageBitmap(new Blob([encoded],{type:'image/jpeg'}));
   try {
    check(bitmap.width===image.width&&bitmap.height===image.height,'JPEG dimensions');
    ctx.clearRect(0,0,100,40);ctx.drawImage(bitmap,0,0);
    const rgba=ctx.getImageData(0,0,image.width,image.height).data;
    check(rgba.every((value,i)=>value===(i%4===3?255:image.rgb[i%4])),'Original JPEG pixel values');
   } finally {bitmap.close();}
  }
  const font=new FontFace('KineticaSbixMediaProbe',source);await font.load();document.fonts.add(font);
  const demo=globalThis.kineticaTerminalDemo,initialFonts=document.fonts.size;
  try {
   ctx.clearRect(0,0,100,40);ctx.font='20px KineticaSbixMediaProbe';ctx.fillStyle='#00ff00';ctx.fillText('ABCD',0,25);
   const nativeCanvas=counts(ctx.getImageData(0,0,100,40).data);
   await demo.shapingFonts('/kinetica-terminal/web/harfbuzz/terminal-harfbuzz.mjs',[{family:'SbixMedia',data:source}]);
   const host=document.querySelector('[data-terminal]');
   const gl=host.querySelector('canvas').getContext('webgl2'),draw=gl.drawArraysInstanced;
   let width=1,height=1,pixels=null;
   gl.drawArraysInstanced=function(...args){draw.apply(gl,args);pixels=new Uint8Array(width*height*4);gl.readPixels(0,gl.drawingBufferHeight-height,width,height,gl.RGBA,gl.UNSIGNED_BYTE,pixels);};
   const cases=[];
   try {
    for(const size of [20,30]) {
     demo.fontSize(size);demo.reset();demo.write('\x1b[?25l');await wait();
     check(host.dataset.terminalRenderer==='webgl2'&&host.dataset.terminalShaping==='true','Shaped WebGL must be active');
     const cell=demo.cellMetrics(),cw=cell.width*devicePixelRatio,ch=cell.height*devicePixelRatio;
     width=Math.ceil(cw*4);height=Math.ceil(ch);
     const paint=async color=>{pixels=null;demo.write('\x1b[H\x1b[38;2;'+color+'mABCD');await wait();check(pixels!==null,'Actual GPU draw');return pixels.slice();};
     const green=await paint('0;255;0'),webgl=counts(green);
     const parity=(nativeCanvas.gray<=20||webgl.gray>20)&&(nativeCanvas.blue<=20||webgl.blue>20);
     let worst=null;
     if(nativeCanvas.gray>20&&nativeCanvas.blue>20&&parity){
      const red=await paint('255;0;0');check(green.every((value,i)=>value===red[i]),'SGR must not tint intrinsic artwork');
      const reference=document.createElement('canvas');reference.width=width;reference.height=height;
      const c=reference.getContext('2d',{willReadFrequently:true});c.fillStyle='#1e1e1e';c.fillRect(0,0,width,height);
      c.font=(size*devicePixelRatio)+'px KineticaSbixMediaProbe';c.fillStyle='#00ff00';c.textBaseline='alphabetic';c.fontKerning='none';
      const rounded=value=>value%1===0.5?Math.round(value/2)*2:Math.round(value);
      const baseline=Math.round((ch+size*devicePixelRatio*0.6)/2);
      for(const [column,character] of [...'ABCD'].entries())c.fillText(character,rounded(cw*column),baseline);
      const expected=c.getImageData(0,0,width,height).data;worst=0;
      for(let y=0;y<height;y++)for(let x=0;x<width;x++)for(let channel=0;channel<3;channel++)
       worst=Math.max(worst,Math.abs(green[((height-y-1)*width+x)*4+channel]-expected[(y*width+x)*4+channel]));
      check(worst<=3,`Native glyph pixels differ at ${size}px / DPR ${devicePixelRatio}: ${worst}`);
      reference.width=0;reference.height=0;
     }
     cases.push({size,webgl,parity,worst});
    }
    // Simulate a platform codec fallback and intrinsic white artwork separately.
    const originalFill=CanvasRenderingContext2D.prototype.fillText;
    try {
     for(const [size,intrinsic] of [[21,false],[22,true]]){
      CanvasRenderingContext2D.prototype.fillText=function(text,x,y,...rest){
       if(this.font.includes('-glyph-')){if(intrinsic)this.fillStyle='white';this.fillRect(x+1,y-14,10,14);}
       else originalFill.call(this,text,x,y,...rest);
      };
      demo.fontSize(size);demo.reset();demo.write('\x1b[?25l');await wait();
      const cell=demo.cellMetrics();width=Math.ceil(cell.width*devicePixelRatio);height=Math.ceil(cell.height*devicePixelRatio);
      demo.write('\x1b[H\x1b[38;2;0;255;0mA');await wait();
      if(intrinsic)check(pixels.some((v,i)=>i%4===0&&v>240&&pixels[i+1]>240&&pixels[i+2]>240),'Intrinsic white remains white');
      else check(counts(pixels).green>20,'Unsupported codec outline stays tintable');
     }
    } finally {CanvasRenderingContext2D.prototype.fillText=originalFill;}
    // A substitutes to blue B, which has no cmap entry. A second ccmp pass on B
    // would select gray D. Only direct rasterization of the shaped ID stays blue.
    const shaped=new Uint8Array(await(await fetch('/'+directory+'sbix-media-shaped.ttf')).arrayBuffer());
    await demo.shapingFonts('/kinetica-terminal/web/harfbuzz/terminal-harfbuzz.mjs',[{family:'SbixShaped',data:shaped}]);
    demo.fontSize(20);demo.reset();demo.write('\x1b[?25l');await wait();
    const cell=demo.cellMetrics();width=Math.ceil(cell.width*devicePixelRatio);height=Math.ceil(cell.height*devicePixelRatio);
    demo.write('\x1b[H\x1b[38;2;0;255;0mA');await wait();
    const shapedGlyph=counts(pixels);
    check(shapedGlyph.blue>20&&shapedGlyph.gray===0&&shapedGlyph.green===0,'Unmapped substituted glyph paints without a second shaping pass');
    // Replacing the font set must remove both original and private CSS faces.
    check(document.fonts.size===initialFonts+2,'Font replacement retains exactly two owned CSS faces');
    const originalLoad=FontFace.prototype.load;
    try {
     FontFace.prototype.load=function(){return this.family.includes('-glyph-')?Promise.reject(new Error('Injected native font failure')):originalLoad.call(this);};
     let failed=false;
     try{await demo.shapingFonts('/kinetica-terminal/web/harfbuzz/terminal-harfbuzz.mjs',[{family:'Rejected',data:source}]);}catch{failed=true;}
     check(failed&&document.fonts.size===initialFonts+2,'Failed private face load preserves the active set without leaks');
    } finally {FontFace.prototype.load=originalLoad;}
    let unblock;
    try {
     FontFace.prototype.load=function(){const ready=originalLoad.call(this);return this.family.includes('-glyph-')?ready.then(face=>new Promise(resolve=>{unblock=()=>resolve(face);})):ready;};
     const pending=demo.shapingFonts('/kinetica-terminal/web/harfbuzz/terminal-harfbuzz.mjs',[{family:'Pending',data:source}]).then(()=>true,()=>false);
     for(let attempt=0;attempt<100&&!unblock;attempt++)await wait();
     check(typeof unblock==='function','Private font load reaches the cancellation gate');
     demo.dispose();unblock();check(await pending===false,'A late font load cannot revive a disposed terminal');
     check(document.fonts.size===initialFonts,'Disposal removes private glyph faces, including a pending set');
    } finally {FontFace.prototype.load=originalLoad;unblock?.();}
    return {ratio:devicePixelRatio,nativeCanvas,cases,shapedGlyph,parity:cases.every(value=>value.parity)};
   } finally {gl.drawArraysInstanced=draw;}
  } finally {demo.dispose();document.fonts.delete(font);canvas.width=0;canvas.height=0;}
 },{directory,manifest});
 assert.deepEqual(errors,[]);
 console.log(JSON.stringify({browser:await browser.version(),...result},null,2));
 if(process.argv.includes('--require-native'))assert.ok(result.nativeCanvas.gray>20&&result.nativeCanvas.blue>20,'Native Canvas must support both fixture formats');
 if(process.argv.includes('--require-parity'))assert.ok(result.parity,'Shaped WebGL loses sbix artwork supported by native Canvas');
 await page.close();
 }
} finally {await browser.close();}
