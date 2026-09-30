import {execFileSync,spawn} from 'node:child_process';
const adb=(...args)=>execFileSync('adb',args,{encoding:'utf8'}).trim();
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const tabs=await (await fetch('http://127.0.0.1:9222/json')).json();
// Pass the ID of a disposable foreground Chrome tab from /json.
const tab=tabs.find(t=>t.id===process.argv[2]);
if(!tab) throw Error('Usage: node scripts/browser-insert-tests.mjs <disposable Chrome tab ID>');
const ws=new WebSocket(tab.webSocketDebuggerUrl);
await new Promise(r=>ws.onopen=r);
let id=0; const pending=new Map();
ws.onmessage=e=>{let m=JSON.parse(e.data); if(m.id){pending.get(m.id)?.(m);pending.delete(m.id)}};
const call=(method,params={})=>new Promise((resolve,reject)=>{const n=++id;pending.set(n,m=>m.error?reject(m.error):resolve(m.result));ws.send(JSON.stringify({id:n,method,params}))});
const evaluate=async expression=>(await call('Runtime.evaluate',{expression,returnByValue:true})).result.value;
const original=adb('shell','settings','get','secure','enabled_accessibility_services');
const service='de.kf.blitztext/de.kf.blitztext.TextInsertService';
const without=original.split(':').filter(x=>x!==service&&x!=='null').join(':');
const enabled=[without,service].filter(Boolean).join(':');
const cases=[
 ['input empty','<input id="field">','',0,0],
 ['textarea placeholder','<textarea id="field" placeholder="Ein beliebiger Hinweis"></textarea>','',0,0],
 ['append real text','<textarea id="field"></textarea>','Anfang Ende',11,11],
 ['middle cursor','<textarea id="field"></textarea>','Anfang Ende',7,7],
 ['replace selection','<input id="field">','Anfang Ende',0,6],
 ['contenteditable middle','<div id="field" contenteditable="true" role="textbox"></div>','Anfang Ende',7,7],
];
try {
 adb("shell","settings","put","secure","enabled_accessibility_services",enabled);
 await sleep(1500);
 for(const fallback of [false,true]) for(const [name,html,old,start,end] of cases){
  await call('Page.navigate',{url:'data:text/html,'+encodeURIComponent('<meta name="viewport" content="width=device-width"><style>input,textarea,div{font-size:24px;width:80%;min-height:100px}</style>'+html)});
  await sleep(800);
  await call("Input.dispatchTouchEvent",{type:"touchStart",touchPoints:[{x:40,y:40}]});
  await call("Input.dispatchTouchEvent",{type:"touchEnd",touchPoints:[]});
  await sleep(300);
  await evaluate(`(()=>{let f=document.querySelector('#field');if(f.isContentEditable){f.textContent=${JSON.stringify(old)};f.focus();let r=document.createRange();r.setStart(f.firstChild,${start});r.setEnd(f.firstChild,${end});let s=getSelection();s.removeAllRanges();s.addRange(r)}else{f.value=${JSON.stringify(old)};f.focus();f.setSelectionRange(${start},${end})}return true})()`);
  await sleep(300);
  const output=await new Promise((resolve,reject)=>{
   const p=spawn('adb',['shell','am','instrument','-w','-e','browserInput','true','-e','accessibilityOnly',String(fallback),'de.kf.blitztext.test/de.kf.blitztext.StatsInstrumentation']);
   let out='',connected=false;
   p.stdout.on('data',d=>{out+=d;if(!connected&&out.includes('READY_ACCESSIBILITY')){connected=true;adb('shell','settings','put','secure','enabled_accessibility_services',without||'null');adb('shell','settings','put','secure','enabled_accessibility_services',enabled)}});
   p.stderr.on('data',d=>out+=d); p.on('close',()=>resolve(out));p.on('error',reject);
  });
  await sleep(600);
  const value=await evaluate(`(()=>{let f=document.querySelector('#field');return {text:f.isContentEditable?f.textContent:f.value,cursor:f.isContentEditable?(()=>{let s=getSelection(),r=document.createRange();r.setStart(f,0);r.setEnd(s.anchorNode,s.anchorOffset);return r.toString().length})():f.selectionStart}})()`);
  const expected=old.slice(0,start)+'Blitzprobe '+old.slice(end);
  const pass=output.includes('PASS:')&&value.text===expected&&value.cursor===start+11;
  console.log(JSON.stringify({name,fallback,pass,value,expected,output:output.trim()}));
  if(!pass) throw Error('Browser insertion regression');
 }
} finally {adb('shell','settings','put','secure','enabled_accessibility_services',original);ws.close()}
