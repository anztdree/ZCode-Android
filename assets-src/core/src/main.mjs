// ============================================================================
// ZCode Mobile — main.mjs (entry runtime inti, v1.0: TANPA proot)
// 1) shim DNS  2) execPath override (gaya loader-prefix)  3) bridge + stub
//    electron  4) import out/main ASLI
// ============================================================================
import './dns-shim.cjs'
import { startBridge } from './ws-bridge.mjs'
import { initStub, fireAppReady, app, listHandlers } from './electron-stub.mjs'
import { readFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'

// Gaya D2 (loader-prefix): /proc/self/exe = loader glibc, bukan node —
// override supaya engine bisa spawn CLI agent dengan node yang benar.
if (process.env.ZCODE_NODE) { try { process.execPath = process.env.ZCODE_NODE } catch { } }
process.env.ZCODE_MOBILE = '1'

const ROOT = process.env.ZCODE_APPROOT            // dir berisi out/
const RENDERER = process.env.ZCODE_RENDERER || join(ROOT, 'out', 'renderer')
const PORT = Number(process.env.ZCODE_PORT || 8080)
const elog = (...a) => { try { process.stderr.write('[core] ' + a.map(x => typeof x === 'string' ? x : JSON.stringify(x).slice(0, 400)).join(' ') + '\n') } catch { } }

process.resourcesPath = process.env.ZCODE_RES || join(ROOT, 'res')

// shim preload asli: dibaca dari out/preload/index.cjs, di-edit minimal utk WebView
const preloadPath = join(ROOT, 'out', 'preload', 'index.cjs')
const preloadSrc = readFileSync(preloadPath, 'utf8')

const bridge = await startBridge({ rendererDir: RENDERER, injectJs: buildInject(preloadSrc), port: PORT, log: elog })
elog('bridge siap di port', bridge.port)

initStub({
  registerPort: (p) => bridge.registerPort(p),
  pushEvent: (ch, args, portIds) => bridge.pushEvent(ch, args, portIds || []),
  windowUrl: (id, url) => { elog('windowUrl#' + id, String(url).slice(0, 140)); if (typeof url === 'string' && url.startsWith('/')) { try { bridge.setCurrentUrl(url) } catch { } } },
  openExternal: (url) => { elog('openExternal:', url); bridge.broadcast({ t: 'openexternal', url }) },
  log: elog,
}, {
  rendererDir: RENDERER,
  userData: process.env.ZCODE_USERDATA || '/opt/zdata',
  screenW: Number(process.env.ZCODE_SCREEN_W || 390),
  screenH: Number(process.env.ZCODE_SCREEN_H || 844),
  dpr: Number(process.env.ZCODE_DPR || 2.75),
})

// deep-link zcode://oauth/callback → app 'second-instance' (jalur Linux Desktop)
bridge.onDeeplink = (url) => {
  if (!url) return
  elog('deeplink:', url.slice(0, 120))
  try { app.emit('second-instance', {}, [url], process.cwd()) } catch (e) { elog('deeplink emit err', e.message) }
}

// READY sinyal utk shell Java: /health sudah hidup — tulis marker file
try { const { writeFileSync } = await import('node:fs'); writeFileSync(join(process.env.ZCODE_USERDATA || '/opt/zdata', 'core.ready'), String(bridge.port)) } catch { }
fireAppReady()

// Import main ASLI ZCode Desktop (ESM). 'electron' diselesaikan oleh
// node_modules/electron stub yang dipasang di ROOT.
elog('boot main asli…')
try {
  await import(join(ROOT, 'out', 'main', 'index.js'))
} catch (e) {
  elog('MAIN-ERROR:', e?.stack || e?.message || String(e))
  try { writeFileSync(join(process.env.ZCODE_USERDATA || '/opt/zdata', 'core.error'), String(e?.stack || e)) } catch { }
  process.exit(3)
}
elog('main asli termuat. ipcMain handlers:', listHandlers().length)
setInterval(() => { }, 1 << 30)

function buildInject(preloadSrc) {
  // JS yang disuntikkan ke WebView SEBELUM modul renderer:
  // 1) definisikan global process ala Electron-preload
  // 2) sediakan require('electron') = {contextBridge, ipcRenderer-over-WS, webUtils}
  // 3) jalankan preload ASLI (CJS) di lingkup itu → window.zcode muncul
  return `;(function(){
  'use strict';
  if (window.__zcodeInjected) return; window.__zcodeInjected = true;
  window.__msglog = [];
  window.addEventListener('message', function (e) {
    try {
      var d = e.data || {};
      window.__msglog.push({ t: d.type || (typeof d), ph: d.state && d.state.phase, seq: d.state && d.state.sequence, sid: (d.databaseStartupId || (d.state && d.state.startupId) || '').slice(0, 8), ports: e.ports ? e.ports.length : -1 });
      if (window.__msglog.length > 60) window.__msglog.shift();
    } catch (err) { }
  }, true);
  var WSURL = (location.protocol === 'https:' ? 'wss://' : 'ws://') + location.host + '/ipc';
  var ws = null, wsReady = false, pendingQ = [];
  var listeners = {};   // channel -> Set<cb>
  var pendingInv = {};  // id -> {res, rej}
  var invSeq = 1;

  function wsSend(o) { if (wsReady) ws.send(JSON.stringify(o)); else pendingQ.push(o); }
  function connect() {
    ws = new WebSocket(WSURL);
    ws.onopen = function () {
      wsReady = true;
      var q = pendingQ; pendingQ = [];
      q.forEach(wsSend);
      // replay listener aktif tidak perlu: server mengirim event ke semua ws
      document.documentElement.dispatchEvent(new CustomEvent('zcode-ipc-ready'));
    };
    ws.onmessage = function (ev) {
      var m; try { m = JSON.parse(ev.data) } catch { return }
      if (m.t === 'p') {
        var peers2 = realPeers[m.portId]; if (!peers2) return;
        var data = m.b64 != null ? b64ToU8(m.b64) : m.value;
        // arah benar: pesan UNTUK klien (port1) dikirim lewat port2 (peer)
        peers2.forEach(function (pe) { try { pe.__p2.postMessage(data) } catch (err) { } });
        return;
      }
      if (m.t === 'ph') {
        var peers3 = realPeers[m.portId]; if (!peers3 || !peers3.length) return;
        var target = peers3[peers3.length - 1];
        if (!target.__gotHistory) {
          target.__gotHistory = true;
          (m.items || []).forEach(function (s) {
            try { var o = JSON.parse(s); var v = o.b64 != null ? b64ToU8(o.b64) : o.value; target.__p2.postMessage(v) } catch (err) { }
          });
        }
        return;
      }
      if (m.t === 'e') {
        var ls = listeners[m.channel]; if (!ls) return;
        var e = { sender: { id: 1, send: function () {} }, senderId: 1, ports: (m.ports || []).map(getRealPort), reply: function () {}, frameId: null, processId: 1 };
        ls.forEach(function (cb) { try { cb(e, m.args && m.args[0], m.args && m.args[1], m.args && m.args[2]) } catch (err) { console.error('[ipc-cb]', m.channel, err) } });
      } else if (m.t === 'i') {
        var p = pendingInv[m.id]; if (!p) return; delete pendingInv[m.id];
        if (m.error) { var er = new Error(m.error); er.channel = m.channel; p.rej(er) } else p.res(m.result);
      } else if (m.t === 'openexternal') {
        try { location.href = 'zcode-open:login?url=' + encodeURIComponent(m.url) } catch (e2) { }
      }
    };
    ws.onclose = function () { wsReady = false; setTimeout(connect, 1200) };
    ws.onerror = function () { };
  }

  var ipcRenderer = {
    invoke: function (channel) {
      var args = Array.prototype.slice.call(arguments, 1);
      var id = invSeq++;
      if (window.__flushPortMsgs) setTimeout(window.__flushPortMsgs, 0);
      return new Promise(function (res, rej) {
        pendingInv[id] = { res: res, rej: rej };
        wsSend({ t: 'i', id: id, channel: channel, args: args });
        setTimeout(function () { if (pendingInv[id]) { delete pendingInv[id]; rej(new Error('ipc timeout: ' + channel)) } }, 180000);
      });
    },
    send: function (channel) {
      var args = Array.prototype.slice.call(arguments, 1);
      wsSend({ t: 's', channel: channel, args: args });
    },
    sendSync: function (channel) {
      var args = Array.prototype.slice.call(arguments, 1);
      try {
        var x = new XMLHttpRequest();
        x.open('POST', '/ipc-sync?channel=' + encodeURIComponent(channel) + '&args=' + encodeURIComponent(JSON.stringify(args)), false);
        x.send(null);
        var r = JSON.parse(x.responseText);
        var e = { sender: { id: 1 }, senderId: 1, ports: [] };
        if (r.error) throw new Error(r.error);
        return r.result;
      } catch (err) { console.warn('[sendSync]', channel, err && err.message); return undefined }
    },
    on: function (channel, cb) {
      (listeners[channel] = listeners[channel] || new Set()).add(cb);
      return ipcRenderer;
    },
    once: function (channel, cb) {
      var g = function () { ipcRenderer.removeListener(channel, g); cb.apply(null, arguments) };
      return ipcRenderer.on(channel, g);
    },
    removeListener: function (channel, cb) { listeners[channel] && listeners[channel].delete(cb); return ipcRenderer },
    removeAllListeners: function (channel) { if (channel) delete listeners[channel]; else listeners = {}; return ipcRenderer },
    listenerCount: function (channel) { return listeners[channel] ? listeners[channel].size : 0 },
    postMessage: function (channel, msg, transfer) { wsSend({ t: 's', channel: channel, args: [msg] }) },
    sendToHost: function (channel) { var a = Array.prototype.slice.call(arguments, 1); wsSend({ t: 's', channel: channel, args: a }) },
    eventNames: function () { return Object.keys(listeners) },
    getMaxListeners: function () { return 0 },
    setMaxListeners: function () { return ipcRenderer },
  };

  var noopf = function () { };
  var webFrame = new Proxy({}, {
    get: function (t, k) {
      if (k === 'getZoomFactor') return function () { return 1 }
      if (k === 'getZoomLevel') return function () { return 0 }
      if (k === 'firstLayoutDone') return true
      return noopf
    }
  });
  var electronStub = {
    contextBridge: { exposeInMainWorld: function (k, v) { try { window[k] = v } catch (e) { Object.defineProperty(window, k, { value: v, configurable: true }) } }, executeInMainWorld: function (fn, args) { try { return typeof fn === 'function' ? fn.apply(window, args || []) : (typeof fn === 'string' ? window.eval(fn) : undefined) } catch (e) { console.warn('[executeInMainWorld]', e && e.message) } } },
    ipcRenderer: ipcRenderer,
    webFrame: webFrame,
    webUtils: { getPathForFile: function (f) { return (f && f.name) || null } },
    crashReporter: { addExtraParameter: noopf, removeExtraParameter: noopf, getParameters: function () { return {} } },
    nativeTheme: { shouldUseDarkColors: true, themeSource: 'system', on: noopf, once: noopf, removeListener: noopf },
  };

  var mod = { exports: {} };
  function require(name) {
    if (name === 'electron') return electronStub;
    console.warn('[preload-require] modul diabaikan:', name);
    return {};
  }

  var realPeers = {};  // portId -> [ {__p1, __alive} ] — banyak peer ke endpoint logis sama
  function u8ToB64(u8) { var s = ''; var CH = 0x8000; for (var i = 0; i < u8.length; i += CH) s += String.fromCharCode.apply(null, u8.subarray(i, i + CH)); return btoa(s) }
  function b64ToU8(b) { var s = atob(b); var u = new Uint8Array(s.length); for (var i = 0; i < s.length; i++) u[i] = s.charCodeAt(i); return u }
  function getRealPort(id, fresh) {
    var peers = realPeers[id] = realPeers[id] || [];
    if (peers.length >= 6) return peers[peers.length - 1].__p1;
    if (!fresh && peers.length && peers[0].__alive) return peers[0].__p1;
    var ch = new MessageChannel();
    ch.port2.onmessage = function (e) {
      var v = e.data;
      try {
        if (v instanceof Uint8Array) wsSend({ t: 'p', portId: id, b64: u8ToB64(v) });
        else if (v instanceof ArrayBuffer) wsSend({ t: 'p', portId: id, b64: u8ToB64(new Uint8Array(v)) });
        else wsSend({ t: 'p', portId: id, value: v === undefined ? null : v });
      } catch (err) { console.error('[port-out]', err) }
    };
    ch.port2.start();
    ch.port1.__zportId = id;
    peers.push({ __p1: ch.port1, __p2: ch.port2, __alive: true });
    // replay riwayat pesan host utk peer segar (handshake Initialize dsb.)
    wsSend({ t: 'phistory', portId: id });
    return ch.port1;
  }
  window.__zcodeIpc = ipcRenderer;

  // renderer menerima ServicePort via window.postMessage(msg,'*',[port]) — port palsu
  // tidak bisa ditransfer beneran → sindir MessageEvent dengan ports.
  var __origPostMessage = window.postMessage.bind(window);
  var __portMsgs = [];        // {msg, origin, portIds}
  var __lastStartupState = null;
  var __roundsDone = 0;
  var __roundsScheduled = false;
  var __rounds = [150, 900, 2000, 3500, 5500, 8000, 11500];
  function __flushPortMsgs() {
    if (!__portMsgs.length || __roundsDone > __rounds.length + 2) return;
    __roundsDone++;
    __portMsgs.forEach(function (pm) {
      try {
        var ports = pm.portIds.map(function (pid) { return getRealPort(pid, true) });
        __origPostMessage(pm.msg, pm.origin, ports);
      } catch (e) { console.warn('[flush-port]', e && e.message) }
    });
  }
  function __scheduleRounds() {
    if (__roundsScheduled) return;
    __roundsScheduled = true;
    __rounds.forEach(function (d) { setTimeout(__flushPortMsgs, d) });
  }
  window.addEventListener('zcode-react-startup-ready', __flushPortMsgs);
  window.__flushPortMsgs = __flushPortMsgs;
  var __stateReposted = 0;
  window.postMessage = function (msg, targetOrigin, transfer) {
    try {
      if (msg && msg.type === 'zcode:database-startup-state') {
        __lastStartupState = { msg: msg };
        if (msg.state && msg.state.phase === 'ready' && __stateReposted < 2 && __portMsgs.length) {
          __stateReposted++;
          setTimeout(function () { if (__portMsgs.length) __origPostMessage(__lastStartupState.msg, '*') }, 600);
        }
      }
    } catch (err) { }
    if (transfer && transfer.length && typeof MessagePort !== 'undefined' && transfer[0] instanceof MessagePort) {
      var ids = transfer.map(function (p) { return p.__zportId });
      __portMsgs.push({ msg: msg, origin: targetOrigin, portIds: ids });
      __scheduleRounds();
      return;
    }
    return __origPostMessage(msg, targetOrigin);
  };
  window.__zcodeWaitIpc = function () { return wsReady ? Promise.resolve() : new Promise(function (r) { document.documentElement.addEventListener('zcode-ipc-ready', r, { once: true }) }) };

  connect();

  // process global ala preload Electron
  window.process = { platform: 'linux', arch: 'arm64', env: {}, versions: { electron: '33.4.11', node: '22.20.0', chrome: '130', http_parser: 'node' }, pid: 1, ppid: 1, cwd: function () { return '/workspace' }, chdir: function () { }, nextTick: function (f) { setTimeout(f, 0) }, hrtime: Object.assign(function () { var t = performance.now(); return [Math.floor(t / 1000), Math.floor((t % 1000) * 1e6)] }, { bigint: function () { return BigInt(Math.floor(performance.now() * 1e6)) } }), emitWarning: function () { }, argv: ['zcode', '/index.html'], execArgv: [], argv0: 'zcode', execPath: '/opt/zhost/bin/node', contextIsolated: true, sandbox: false, resourcesPath: ${JSON.stringify(process.resourcesPath || '/opt/zhost/res')}, type: 'renderer', noDeprecation: true, throwDeprecation: false, traceDeprecation: false, memoryUsage: function () { return { rss: 0, heapTotal: 0, heapUsed: 0, external: 0 } }, exit: function (c) { console.warn('[process.exit]', c) }, on: function () { }, once: function () { }, removeListener: function () { }, binding: function () { throw new Error('process.binding unavailable') }, umask: function () { return 18 } };
  window.process.noDeprecation = true;

  var PRELOAD_SRC = ${JSON.stringify(preloadSrc)};
  try {
    new Function('require', 'module', 'exports', 'process', 'global', PRELOAD_SRC).call(mod, require, mod, mod.exports, window.process, window);
    console.log('[zcode-mobile] preload asli termuat');
  } catch (e) {
    console.error('[zcode-mobile] preload gagal:', e && (e.stack || e.message));
    document.title = 'PRELOAD ERROR: ' + (e && e.message);
  }
})();`
}
