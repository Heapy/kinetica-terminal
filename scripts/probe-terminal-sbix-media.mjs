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
 const page=await browser.newPage({viewport:{width:900,height:400},deviceScaleFactor:1});
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
  const demo=globalThis.kineticaTerminalDemo;
  try {
   ctx.clearRect(0,0,100,40);ctx.font='20px KineticaSbixMediaProbe';ctx.fillStyle='#00ff00';ctx.fillText('ABCD',0,25);
   const nativeCanvas=counts(ctx.getImageData(0,0,100,40).data);
   await demo.shapingFonts('/kinetica-terminal/web/harfbuzz/terminal-harfbuzz.mjs',[{family:'SbixMedia',data:source}]);
   demo.fontSize(20);demo.reset();demo.write('\x1b[?25l');await wait();
   const host=document.querySelector('[data-terminal]');
   check(host.dataset.terminalRenderer==='webgl2'&&host.dataset.terminalShaping==='true','Shaped WebGL must be active');
   const gl=host.querySelector('canvas').getContext('webgl2'),draw=gl.drawArraysInstanced;
   const cell=demo.cellMetrics(),width=Math.ceil(cell.width*4),height=Math.ceil(cell.height);
   let pixels=null;
   gl.drawArraysInstanced=function(...args){draw.apply(gl,args);pixels=new Uint8Array(width*height*4);gl.readPixels(0,gl.drawingBufferHeight-height,width,height,gl.RGBA,gl.UNSIGNED_BYTE,pixels);};
   try {
    demo.write('\x1b[H\x1b[38;2;0;255;0mABCD');await wait();check(pixels!==null,'Actual GPU draw');
    const webgl=counts(pixels);
    return {nativeCanvas,webgl,parity:(nativeCanvas.gray<=20||webgl.gray>20)&&(nativeCanvas.blue<=20||webgl.blue>20)};
   } finally {gl.drawArraysInstanced=draw;}
  } finally {demo.dispose();document.fonts.delete(font);canvas.width=0;canvas.height=0;}
 },{directory,manifest});
 assert.deepEqual(errors,[]);
 console.log(JSON.stringify({browser:await browser.version(),...result},null,2));
 if(process.argv.includes('--require-parity'))assert.ok(result.parity,'Shaped WebGL loses sbix artwork supported by native Canvas');
} finally {await browser.close();}
