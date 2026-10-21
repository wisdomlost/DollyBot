'use strict';
(() => {
  const $ = (id) => document.getElementById(id);
  let socket, seq = 0, ready = false, lastRx = 0, sentAt = 0;
  let linear = 0, angular = 0, active = null;
  const controls = [...document.querySelectorAll('[data-linear]'), $('speed')];
  const setReady = (value, text) => {
    ready = value;
    $('connection').textContent = text;
    $('connection').classList.toggle('online', value);
    controls.forEach((el) => { el.disabled = !value; });
  };
  function send(type, values = {}) {
    if (!socket || socket.readyState !== WebSocket.OPEN) return false;
    if (socket.bufferedAmount > 1024) { socket.close(); return false; }
    if (seq >= 4294967295) { socket.close(); return false; }
    socket.send(JSON.stringify({ v: 1, type, seq: ++seq, ...values }));
    sentAt = performance.now();
    return true;
  }
  function reset() {
    linear = angular = 0;
    active = null;
    $('knob').style.transform = 'translate(0px, 0px)';
  }
  function stop() { reset(); send('stop'); }
  function connect() {
    reset();
    setReady(false, '连接中');
    socket = new WebSocket(`ws://${location.host}/ws`);
    socket.onopen = () => { seq = 0; lastRx = performance.now(); send('stop'); };
    socket.onmessage = (event) => {
      try {
        const s = JSON.parse(event.data);
        if (s.v !== 1 || s.type !== 'state' || !Number.isFinite(s.linear) || !Number.isFinite(s.angular)) throw new Error('bad state');
        lastRx = performance.now();
        setReady(true, '设备已连接');
        $('linear').textContent = s.linear.toFixed(2);
        $('angular').textContent = s.angular.toFixed(2);
        $('latency').textContent = `${Math.round(lastRx - sentAt)} ms`;
        $('motion').textContent = s.linear || s.angular ? '模拟运动中' : '已停止';
        $('debug').textContent = `来源：${s.simulated ? '模拟指令' : '设备数据'}\nSTM32：${s.stm32_connected ? '已连接' : '未连接'}\nACK：${s.ack} / 原因：${s.reason}\n运行：${Math.floor(s.uptime_ms / 1000)} s / 空闲堆：${s.free_heap} B\n运动超时：${s.timeout_ms} ms`;
      } catch { reset(); socket.close(); }
    };
    socket.onclose = () => {
      reset(); setReady(false, '已断开 · 重连中');
      $('linear').textContent = $('angular').textContent = '—';
      $('motion').textContent = '状态未知'; $('latency').textContent = '—';
      setTimeout(connect, 1500);
    };
    socket.onerror = () => socket.close();
  }
  function drive() {
    const scale = Number($('speed').value) / 100;
    send('drive', { linear: linear * scale, angular: angular * scale });
  }
  const stick = $('joystick');
  function move(event) {
    if (!ready || active !== event.pointerId) return;
    const rect = stick.getBoundingClientRect();
    let x = (event.clientX - rect.left - rect.width / 2) / 78;
    let y = (event.clientY - rect.top - rect.height / 2) / 78;
    const size = Math.max(1, Math.hypot(x, y)); x /= size; y /= size;
    angular = Math.abs(x) < .08 ? 0 : -x;
    linear = Math.abs(y) < .08 ? 0 : -y;
    $('knob').style.transform = `translate(${x * 78}px, ${y * 78}px)`;
    drive();
  }
  stick.addEventListener('pointerdown', (e) => {
    if (!ready || active !== null) return;
    active = e.pointerId; stick.setPointerCapture(e.pointerId); move(e);
  });
  stick.addEventListener('pointermove', move);
  for (const button of document.querySelectorAll('[data-linear]')) {
    button.addEventListener('pointerdown', (e) => {
      if (!ready || active !== null) return;
      active = e.pointerId; button.setPointerCapture(e.pointerId);
      linear = Number(button.dataset.linear); angular = Number(button.dataset.angular); drive();
    });
    button.addEventListener('keydown', (e) => {
      if ((e.key === ' ' || e.key === 'Enter') && !e.repeat && ready && active === null) {
        e.preventDefault(); active = 'keyboard'; linear = Number(button.dataset.linear); angular = Number(button.dataset.angular); drive();
      }
    });
    button.addEventListener('keyup', (e) => { if (e.key === ' ' || e.key === 'Enter') stop(); });
    button.addEventListener('blur', () => { if (active === 'keyboard') stop(); });
  }
  for (const name of ['pointerup', 'pointercancel', 'lostpointercapture']) {
    document.addEventListener(name, (e) => { if (active === e.pointerId) stop(); }, true);
  }
  window.addEventListener('blur', stop);
  window.addEventListener('pagehide', stop);
  document.addEventListener('visibilitychange', () => { if (document.hidden) stop(); });
  $('stop').addEventListener('click', stop);
  $('stop').addEventListener('pointerdown', stop);
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') stop(); });
  $('speed').addEventListener('input', () => { $('speed-value').textContent = `${$('speed').value}%`; });
  setInterval(() => {
    if (!socket || socket.readyState !== WebSocket.OPEN) return;
    if (performance.now() - lastRx > 1200) { reset(); setReady(false, '设备无响应'); socket.close(); return; }
    if (ready && active !== null && !document.hidden) drive(); else send('ping');
  }, 100);
  connect();
})();
