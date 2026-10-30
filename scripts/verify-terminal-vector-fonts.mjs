import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';

const directory='kinetica-terminal/test-upstream/color-fonts/',root=new URL('../',import.meta.url);
const manifest=JSON.parse(await readFile(new URL(directory+'vector-manifest.json',root)));
for(const [name,hash] of [['colrv1',manifest.colrv1Sha256],['svg-mixed',manifest.svgMixedSha256]])
 assert.equal(createHash('sha256').update(await readFile(new URL(directory+name+'.ttf',root))).digest('hex'),hash);

async function verify({directory}) {
 const check=(condition,message)=>{if(!condition)throw new Error(message);};
 const wait=()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve)));
 const demo=globalThis.kineticaTerminalDemo,host=document.querySelector('[data-terminal]'),owned=document.fonts.size;
 const results=[];
 for(const name of ['colrv1','svg-mixed']) {
  const bytes=new Uint8Array(await(await fetch('/'+directory+name+'.ttf')).arrayBuffer());
  const alias='VectorReference'+name,font=new FontFace(alias,bytes);await font.load();document.fonts.add(font);
  try {
   await demo.shapingFonts('/kinetica-terminal/web/harfbuzz/terminal-harfbuzz.mjs',[{family:name,data:bytes}]);
   const gl=host.querySelector('canvas').getContext('webgl2'),draw=gl.drawArraysInstanced;
   let width=1,height=1,pixels=null;
   gl.drawArraysInstanced=function(...args){draw.apply(gl,args);pixels=new Uint8Array(width*height*4);gl.readPixels(0,gl.drawingBufferHeight-height,width,height,gl.RGBA,gl.UNSIGNED_BYTE,pixels);};
   try {
    for(const size of [20,30]) {
     demo.fontSize(size);demo.reset();demo.write('\x1b[?25l');await wait();
     check(host.dataset.terminalRenderer==='webgl2'&&host.dataset.terminalShaping==='true','Actual shaped WebGL is required');
     const cell=demo.cellMetrics(),cw=cell.width*devicePixelRatio,ch=cell.height*devicePixelRatio;
     // A–E are vector paints. F remains ordinary HarfBuzz outline text, whose
     // hinting intentionally differs from native Canvas and is tested separately.
     width=Math.ceil(cw*5);height=Math.ceil(ch);
     const reference=document.createElement('canvas');reference.width=width;reference.height=height;
     const ctx=reference.getContext('2d',{willReadFrequently:true});
     const captures=[];let worst=0,mismatch=null;
     for(const color of ['0;255;0','255;0;0','0;255;0']) {
      pixels=null;demo.write('\x1b[H\x1b[38;2;'+color+'mABCDEF');await wait();check(pixels!==null,'GPU output was captured');
      ctx.fillStyle='#1e1e1e';ctx.fillRect(0,0,width,height);
      ctx.font=(size*devicePixelRatio)+'px "'+alias+'"';ctx.fontKerning='none';ctx.fillStyle='rgb('+color.replaceAll(';',',')+')';
      ctx.textBaseline='alphabetic';ctx.textAlign='left';
      const round=value=>value%1===0.5?Math.round(value/2)*2:Math.round(value);
      const baseline=Math.round((ch+size*devicePixelRatio*0.6)/2);
      for(const [column,character] of [...'ABCDEF'].entries())ctx.fillText(character,round(column*cw),baseline);
      const expected=ctx.getImageData(0,0,width,height).data;
      for(let y=0;y<height;y++)for(let x=0;x<width;x++)for(let channel=0;channel<3;channel++){
       const actual=pixels[((height-y-1)*width+x)*4+channel],wanted=expected[(y*width+x)*4+channel],delta=Math.abs(actual-wanted);
       if(delta>worst){worst=delta;mismatch={x,y,channel,actual,wanted,color};}
      }
      captures.push({actual:pixels.slice(),expected});
     }
     check(worst<=3,`${name} ${size}px DPR ${devicePixelRatio}: pixel error ${worst} ${JSON.stringify(mismatch)}`);
     check(captures[0].actual.every((value,i)=>value===captures[2].actual[i]),'Changing foreground and returning preserves cached pixels');
     let fixed=0,current=0;
     for(let i=0;i<captures[0].expected.length;i+=4){
      const a=captures[0].expected,b=captures[1].expected;
      if(Math.max(a[i],a[i+1],a[i+2])<=40)continue;
      if(a[i]===b[i]&&a[i+1]===b[i+1]&&a[i+2]===b[i+2])fixed++;else current++;
     }
     results.push({name,size,ratio:devicePixelRatio,worst,fixed,current});reference.width=0;reference.height=0;
    }
   } finally {gl.drawArraysInstanced=draw;}
  } finally {document.fonts.delete(font);}
 }
 demo.dispose();check(document.fonts.size===owned,'All original/private/reference fonts are released');
 return results;
}

const base=process.env.KINETICA_BROWSER_BASE_URL??'http://127.0.0.1:4173';
const url=base+'/samples/browser-terminal/web/index.html?renderer=gpu&fonts=system';
function check(results){
 console.log(JSON.stringify(results,null,2));
 for(const [flag,name] of [['--require-colrv1','colrv1'],['--require-svg','svg-mixed']])if(process.argv.includes(flag))
  assert.ok(results.filter(result=>result.name===name).every(result=>result.fixed>20&&(name!=='colrv1'||result.current>20)),name+' must paint native vector artwork');
}
if(process.env.KINETICA_BIDI_URL){
 const socket=new WebSocket(process.env.KINETICA_BIDI_URL);
 await new Promise((resolve,reject)=>{socket.onopen=resolve;socket.onerror=reject;});
 let sequence=0;const pending=new Map();
 socket.onmessage=event=>{const reply=JSON.parse(event.data);pending.get(reply.id)?.(reply);pending.delete(reply.id);};
 const call=async(method,params={})=>{
  const id=++sequence;let timer;
  try {
   const reply=await new Promise((resolve,reject)=>{timer=setTimeout(()=>{pending.delete(id);reject(new Error('BiDi timeout: '+method));},30000);pending.set(id,resolve);socket.send(JSON.stringify({id,method,params}));});
   assert.equal(reply.type,'success',JSON.stringify(reply));return reply.result;
  } finally {clearTimeout(timer);}
 };
 let context,started=false;
 try {
  await call('session.new',{capabilities:{}});
  started=true;
  context=(await call('browsingContext.create',{type:'tab'})).context;
  for(const ratio of [1,1.25,2]){
   await call('browsingContext.setViewport',{context,viewport:{width:1000,height:500},devicePixelRatio:ratio});
   await call('browsingContext.navigate',{context,url,wait:'complete'});
   const value=await call('script.evaluate',{target:{context},awaitPromise:true,
    expression:`(${verify.toString()})(${JSON.stringify({directory})}).then(JSON.stringify)`});
   assert.equal(value.type,'success',JSON.stringify(value));const results=JSON.parse(value.result.value);
   assert.ok(results.every(result=>result.ratio===ratio),'BiDi applies the requested DPR');check(results);
  }
 } finally {
  try {if(context)await call('browsingContext.close',{context});if(started)await call('session.end');}
  finally {socket.close();}
 }
}else{
 const {chromium}=await import(process.env.PLAYWRIGHT_IMPORT??'playwright');
 const browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE});
 try {
  for(const ratio of [1,1.25,2]){
   const page=await browser.newPage({viewport:{width:1000,height:500},deviceScaleFactor:ratio});
   try {await page.goto(url);await page.waitForFunction(()=>globalThis.kineticaTerminalDemo);check(await page.evaluate(verify,{directory}));}
   finally {await page.close();}
  }
 }finally{await browser.close();}
}
