// Execute the shipped browser code with deterministic DOM/WebSocket/time adapters.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
class Element {
  constructor(dataset = {}) { this.dataset = dataset; this.events = {}; this.style = {}; this.value = '40'; this.classList = { toggle() {} }; }
  addEventListener(type, fn) { (this.events[type] ||= []).push(fn); }
  fire(type, e = {}) { for (const fn of this.events[type] || []) fn(e); }
  setPointerCapture() {}
  getBoundingClientRect() { return { left: 0, top: 0, width: 230, height: 230 }; }
}
const ids = {};
for (const match of fs.readFileSync('web/index.html', 'utf8').matchAll(/id="([^"]+)"/g)) ids[match[1]] = new Element();
const buttons = [new Element({linear:'1',angular:'0'}), new Element({linear:'0',angular:'1'})];
const document = new Element();
document.hidden = false;
document.getElementById = id => { assert.ok(ids[id], `missing element ${id}`); return ids[id]; };
document.querySelectorAll = () => buttons;
const window = new Element();
let now = 0, timer, reconnect;
class WebSocket {
  static OPEN = 1;
  constructor(url) { assert.equal(url, 'ws://192.168.4.1/ws'); WebSocket.last = this; this.readyState = 0; this.bufferedAmount = 0; this.messages = []; }
  send(data) { this.messages.push(JSON.parse(data)); }
  close() { this.readyState = 3; this.onclose(); }
  open() { this.readyState = 1; this.onopen(); }
  state() { this.onmessage({data: JSON.stringify({v:1,type:'state',linear:0,angular:0,simulated:true,stm32_connected:false,ack:1,reason:'stop',uptime_ms:10,free_heap:123,timeout_ms:500})}); }
}
vm.runInNewContext(fs.readFileSync('web/app.js','utf8'), {document,window,WebSocket,location:{host:'192.168.4.1'},performance:{now:()=>now},setInterval:fn=>timer=fn,setTimeout:fn=>reconnect=fn});
let ws = WebSocket.last; ws.open(); assert.equal(ws.messages.at(-1).type,'stop'); ws.state();
buttons[0].fire('pointerdown',{pointerId:1}); assert.equal(ws.messages.at(-1).linear,.4);
timer(); assert.equal(ws.messages.at(-1).type,'drive');
document.fire('pointercancel',{pointerId:1}); assert.equal(ws.messages.at(-1).type,'stop');
timer(); assert.equal(ws.messages.at(-1).type,'ping');
ids.joystick.fire('pointerdown',{pointerId:2,clientX:193,clientY:37});
let cmd=ws.messages.at(-1); assert.ok(cmd.linear > 0 && cmd.angular < 0 && Math.hypot(cmd.linear,cmd.angular) <= .400001);
document.fire('lostpointercapture',{pointerId:2}); assert.equal(ws.messages.at(-1).type,'stop');
buttons[0].fire('pointerdown',{pointerId:3}); window.fire('blur'); assert.equal(ws.messages.at(-1).type,'stop');
buttons[0].fire('pointerdown',{pointerId:4}); document.hidden=true; document.fire('visibilitychange'); assert.equal(ws.messages.at(-1).type,'stop'); document.hidden=false;
buttons[0].fire('keydown',{key:' ',repeat:false,preventDefault(){}}); assert.equal(ws.messages.at(-1).type,'drive'); buttons[0].fire('keyup',{key:' '}); assert.equal(ws.messages.at(-1).type,'stop');
buttons[0].fire('keydown',{key:' ',repeat:false,preventDefault(){}}); buttons[0].fire('blur'); assert.equal(ws.messages.at(-1).type,'stop'); timer(); assert.equal(ws.messages.at(-1).type,'ping');
buttons[0].fire('pointerdown',{pointerId:5}); ids.stop.fire('pointerdown'); assert.equal(ws.messages.at(-1).type,'stop');
now=1300; timer(); assert.equal(ws.readyState,3); assert.equal(ids.motion.textContent,'状态未知');
reconnect(); ws=WebSocket.last; ws.open(); assert.equal(ws.messages[0].seq,1); assert.equal(ws.messages[0].type,'stop'); ws.state();timer();assert.equal(ws.messages.at(-1).type,'ping');
ws.onmessage({data:'broken JSON'});assert.equal(ws.readyState,3);
console.log('PASS: browser control, normalized joystick, pointer/keyboard release, blur/hidden stop, heartbeat, stale connection, reconnect stop, malformed telemetry');
