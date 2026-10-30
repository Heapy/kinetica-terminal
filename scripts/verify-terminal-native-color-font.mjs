import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createTerminalHarfBuzz, prepareTerminalNativeColorFont} from '../kinetica-terminal/web/harfbuzz/terminal-harfbuzz.mjs';

const directory=new URL('../kinetica-terminal/test-upstream/color-fonts/',import.meta.url);
const source=new Uint8Array(await readFile(new URL('sbix-media.ttf',directory))),saved=source.slice();
const prepared=prepareTerminalNativeColorFont(source);
assert.deepEqual(source,saved,'Source font is never modified');
assert.deepEqual([...prepared.glyphs.entries()].filter(([,value])=>value).map(([id])=>id),[4,5,6,7]);
const tables=data=>{
 const view=new DataView(data.buffer,data.byteOffset,data.byteLength),result=new Map();
 for(let i=0;i<view.getUint16(4);i++){
  const record=12+i*16,tag=String.fromCharCode(...data.subarray(record,record+4));
  result.set(tag,{record,offset:view.getUint32(record+8),length:view.getUint32(record+12)});
 }
 return result;
};
const before=tables(source),after=tables(prepared.bytes);
let sum=0;const out=new DataView(prepared.bytes.buffer);
for(let i=0;i<prepared.bytes.length;i+=4)sum=(sum+out.getUint32(i))>>>0;
assert.equal(sum,0xb1b0afba,'Complete SFNT checksum');
for(const [tag,table] of before){
 if(['head','cmap','GSUB','GPOS','GDEF','kern','kerx','morx','mort','DSIG'].includes(tag))continue;
 const other=after.get(tag);assert.ok(other);
 assert.deepEqual(prepared.bytes.subarray(other.offset,other.offset+other.length),source.subarray(table.offset,table.offset+table.length),tag);
}
const runtime=await createTerminalHarfBuzz({wasmBytes:await readFile(new URL('../kinetica-terminal/web/harfbuzz/harfbuzz.wasm',import.meta.url))});
try {
 const original=runtime.createFont(source),mapped=runtime.createFont(prepared.bytes);
 for(let glyph=1;glyph<original.glyphCount;glyph++){
  const scalar=0xf0000+glyph;
  assert.equal(mapped.nominalGlyph(scalar),glyph);
  assert.equal(mapped.shape(String.fromCodePoint(scalar))[0].glyph,glyph);
  assert.deepEqual(mapped.outline(glyph),original.outline(glyph));
 }
 const shaped=runtime.createFont(await readFile(new URL('sbix-media-shaped.ttf',directory)));
 assert.equal(shaped.nominalGlyph(66),null,'The blue glyph has no Unicode mapping');
 assert.equal(shaped.shape('A')[0].glyph,5,'Original ccmp selects the blue glyph');
 const selected=runtime.createFont(prepareTerminalNativeColorFont(await readFile(new URL('sbix-media-shaped.ttf',directory))).bytes);
 assert.equal(selected.shape(String.fromCodePoint(0xf0005))[0].glyph,5,'Private face does not apply ccmp a second time');
} finally {runtime.dispose();}
for(const name of ['colrv0','sbix'])assert.equal(prepareTerminalNativeColorFont(await readFile(new URL(name+'.ttf',directory))),null);
for(const name of ['svg','svg-mixed','colrv1']){
 const bytes=new Uint8Array(await readFile(new URL(name+'.ttf',directory))),p=prepareTerminalNativeColorFont(bytes),map=tables(bytes);
 assert.deepEqual([...p.glyphs.entries()].filter(([,flag])=>flag),
  (name==='svg'?[4,6]:[4,5,6,7,8]).map(glyph=>[glyph,2]),name+' vector foreground metadata');
 const end=Math.max(...[...map.values()].map(table=>table.offset+table.length));
 for(let length=0;length<end;length++)assert.throws(()=>prepareTerminalNativeColorFont(bytes.subarray(0,length)),RangeError);
 const mutate=body=>{const data=bytes.slice();body(new DataView(data.buffer));return data;};
 const view=new DataView(bytes.buffer);
 if(name==='colrv1'){
  const offset=map.get('COLR').offset,list=offset+view.getUint32(offset+14);
  for(const [where,value]of [[offset+14,0xffffffff],[list,0xffffffff],[list+6,0xffffffff],[list+6,0]])
   assert.throws(()=>prepareTerminalNativeColorFont(mutate(v=>v.setUint32(where,value))),RangeError);
  assert.throws(()=>prepareTerminalNativeColorFont(mutate(v=>v.setUint16(list+10,4))),RangeError,'Unsorted/duplicate COLRv1 glyphs');
 }else{
  const offset=map.get('SVG ').offset,list=offset+view.getUint32(offset+2);
  for(const [where,value]of [[offset+2,0xffffffff],[offset+2,0],[list+6,0xffffffff],[list+10,0]])
   assert.throws(()=>prepareTerminalNativeColorFont(mutate(v=>v.setUint32(where,value))),RangeError);
  assert.throws(()=>prepareTerminalNativeColorFont(mutate(v=>v.setUint16(list+4,65535))),RangeError,'SVG range beyond maxp');
 }
}
const lastByte=Math.max(...[...before.values()].map(table=>table.offset+table.length));
for(let length=0;length<lastByte;length++)assert.throws(()=>prepareTerminalNativeColorFont(source.subarray(0,length)),RangeError);
const mutate=body=>{const data=source.slice(),view=new DataView(data.buffer);body(view,data);return data;};
const sbix=before.get('sbix').offset,view=new DataView(source.buffer),strike=sbix+view.getUint32(sbix+8);
const glyphStart=id=>strike+view.getUint32(strike+4+id*4);
assert.throws(()=>prepareTerminalNativeColorFont(mutate(v=>v.setUint32(sbix+4,0xffffffff))),RangeError);
assert.throws(()=>prepareTerminalNativeColorFont(mutate(v=>v.setUint32(sbix+8,0xffffffff))),RangeError);
assert.throws(()=>prepareTerminalNativeColorFont(mutate(v=>v.setUint32(strike+4+4*4,0xffffffff))),RangeError);
assert.throws(()=>prepareTerminalNativeColorFont(mutate(v=>v.setUint16(glyphStart(6)+8,65535))),RangeError);
// The same cyclic dupe in both strikes must not be classified as an image.
const cycle=mutate(v=>{
 for(let s=0;s<v.getUint32(sbix+4);s++){
  const start=sbix+v.getUint32(sbix+8+s*4),glyph=start+v.getUint32(start+4+6*4);v.setUint16(glyph+8,6);
 }
});
assert.equal(prepareTerminalNativeColorFont(cycle).glyphs[6],0);
const reversed=mutate(v=>{const a=v.getUint32(sbix+8),b=v.getUint32(sbix+12);v.setUint32(sbix+8,b);v.setUint32(sbix+12,a);});
assert.deepEqual(prepareTerminalNativeColorFont(reversed).glyphs,prepared.glyphs,'Strike directory order is irrelevant');
// Even when a font contains layout tables, the private rasterization face must not.
for(const tag of ['GSUB','GPOS','GDEF','kern','kerx','morx','mort','DSIG']){
 const input=mutate((_,data)=>data.set([...tag].map(c=>c.charCodeAt(0)),before.get('post').record));
 assert.equal(tables(prepareTerminalNativeColorFont(input).bytes).has(tag),false);
}
// Synthetic table-reader boundary: the final possible 16-bit font ID must use
// plane-16 PUA, not the FFFFE/FFFFF noncharacters. No font rendering is claimed
// for this synthetic maxp/sbix pair; the real glyph data remains the tiny fixture.
const maxp=source.slice(before.get('maxp').offset,before.get('maxp').offset+before.get('maxp').length);
new DataView(maxp.buffer).setUint16(4,65535);
const offsetsEnd=4+65536*4,largeSbix=new Uint8Array(12+offsetsEnd+8),lv=new DataView(largeSbix.buffer);
lv.setUint16(0,1);lv.setUint16(2,1);lv.setUint32(4,1);lv.setUint32(8,12);lv.setUint16(12,20);lv.setUint16(14,72);
for(let glyph=0;glyph<=65535;glyph++)lv.setUint32(16+glyph*4,offsetsEnd+(glyph===65535?8:0));
lv.setUint32(12+offsetsEnd+4,0x74696666);
const boundaryTables=[...before].map(([tag,table])=>[tag,tag==='maxp'?maxp:tag==='sbix'?largeSbix:source.slice(table.offset,table.offset+table.length)]);
const boundary=new Uint8Array(12+boundaryTables.length*16+boundaryTables.reduce((sum,[,data])=>sum+((data.length+3)&~3),0));
boundary.set(source.subarray(0,12));const bv=new DataView(boundary.buffer);let location=12+boundaryTables.length*16;
for(const [i,[tag,data]]of boundaryTables.entries()){
 const record=12+i*16;boundary.set([...tag].map(c=>c.charCodeAt(0)),record);
 bv.setUint32(record+8,location);bv.setUint32(record+12,data.length);boundary.set(data,location);location+=(data.length+3)&~3;
}
const last=prepareTerminalNativeColorFont(boundary),cm=tables(last.bytes).get('cmap'),cv=new DataView(last.bytes.buffer);
assert.equal(last.glyphs[65534],1);assert.equal(cv.getUint32(cm.offset+24),2);
assert.equal(cv.getUint32(cm.offset+32),0xffffd);assert.equal(cv.getUint32(cm.offset+40),0x100000);
assert.equal(cv.getUint32(cm.offset+44),0x100000);assert.equal(cv.getUint32(cm.offset+48),65534);
const independent=prepared.bytes.slice();source.fill(0);
assert.deepEqual(prepared.bytes,independent);assert.equal(prepared.glyphs[4],1,'Independent retained metadata');
console.log('Native glyph font: glyph IDs, outlines, checksum, copied tables, truncations, malformed offsets, dupes and layout removal verified');
