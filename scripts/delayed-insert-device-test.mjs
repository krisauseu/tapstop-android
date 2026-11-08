// Run only against the explicitly prepared disposable about:blank Chrome tab. No STT/API/network.
// Usage: node scripts/delayed-insert-device-test.mjs SERIAL LOCAL_CDP_PORT DISPOSABLE_BLANK_TAB_ID
import {execFileSync, spawn} from 'node:child_process';
const [serial, port, tabId] = process.argv.slice(2);
if (!serial || !tabId || !/^\d+$/.test(port || '')) throw Error('Explicit SERIAL and forwarded LOCAL_CDP_PORT required');
const rawAdb = (...args) => execFileSync('adb', ['-s', serial, ...args], {encoding: 'utf8'}).trim();
const inventory = () => {
  const all = execFileSync('adb', ['devices', '-l'], {encoding: 'utf8'});
  if (!all.split('\n').some(line => line.startsWith(serial + ' ') || line.startsWith(serial + '\t'))) throw Error('Selected device unavailable');
  const model = rawAdb('shell', 'getprop', 'ro.product.model');
  if (!['SM-S928B', 'YLE-W09'].includes(model) || rawAdb('shell', 'getprop', 'ro.serialno') !== serial) throw Error('Unexpected device identity');
  return model;
};
const adb = (...args) => { inventory(); return rawAdb(...args); };
const model = inventory();
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const origin = `http://127.0.0.1:${port}`;
const created = (await (await fetch(origin + '/json')).json()).find(tab => tab.id === tabId && tab.url === 'about:blank');
if (!created) throw Error('Explicit disposable about:blank tab required; refuses existing page content');
const ws = new WebSocket(created.webSocketDebuggerUrl);
await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
let id = 0;
const pending = new Map();
ws.onmessage = event => { const message = JSON.parse(event.data); if (message.id) { pending.get(message.id)?.(message); pending.delete(message.id); } };
const call = (method, params = {}) => new Promise((resolve, reject) => {
  const n = ++id;
  pending.set(n, message => message.error ? reject(Error(message.error.message)) : resolve(message.result));
  ws.send(JSON.stringify({id: n, method, params}));
});
const evaluate = async expression => (await call('Runtime.evaluate', {expression, returnByValue: true})).result.value;
const original = adb('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services');
const service = 'de.kf.tapstop/de.kf.tapstop.TextInsertService';
if (!original.split(':').includes(service)) throw Error('TapStop accessibility must already be enabled');
const without = original.split(':').filter(x => x !== service && x !== 'null').join(':');
let success = false;
try {
  const html = '<meta name="viewport" content="width=device-width"><title>TapStop temporäre Diagnoseprobe</title><textarea id="field" style="position:fixed;top:30px;left:10px;width:85%;height:180px;font-size:24px" aria-label="Temporäre Diagnoseprobe"></textarea>';
  await call('Page.navigate', {url: 'data:text/html;charset=utf-8,' + encodeURIComponent(html)});
  await call('Page.bringToFront');
  await sleep(700);
  const focus = async () => {
    await call('Input.dispatchTouchEvent', {type: 'touchStart', touchPoints: [{x:80, y:80}]});
    await call('Input.dispatchTouchEvent', {type: 'touchEnd', touchPoints: []});
    await evaluate(`(()=>{const f=document.querySelector('#field'); f.focus(); f.setSelectionRange(f.value.length,f.value.length); return true})()`);
  };
  await focus();
  let rebound = false;
  const output = await new Promise((resolve, reject) => {
    inventory();
    const p = spawn('adb', ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'delayedDiagnostics', 'true', 'de.kf.tapstop.test/de.kf.tapstop.StatsInstrumentation']);
    let out = '';
    p.stdout.on('data', chunk => {
      out += chunk;
      if (!rebound && out.includes('READY_ACCESSIBILITY')) {
        rebound = true;
        adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', without || 'null');
        adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', original);
        // The fixture is the only field touched, after the service has rebound.
        sleep(400).then(focus).catch(reject);
      }
    });
    p.stderr.on('data', chunk => out += chunk);
    p.on('error', reject); p.on('close', () => resolve(out));
  });
  const value = await evaluate(`document.querySelector('#field').value`);
  const expected = 'Diagnoseprobe: Äpfel, Öl, süß und Straße. 😀 ';
  success = output.includes('PASS: immediate') && value === expected;
  console.log(JSON.stringify({serial, model, pass: success, editor_matches_fixture: value === expected, instrumentation: output.trim()}));
  if (!success) throw Error('Device diagnostic fixture failed; inspect metadata export');
} finally {
  // Instrumentation terminates its app process. Restore exactly the original service list.
  adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', without || 'null');
  await sleep(300);
  adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', original);
  if (success) await fetch(origin + '/json/close/' + created.id);
  ws.close();
}
