// ============================================================================
// ZCode Mobile — ws-bridge.mjs
// HTTP statis renderer asli + WebSocket IPC ⇄ ipcMain stub.
// - GET  /            → renderer/index.html (disuntik <script /__inject.js>)
// - GET  /assets/...  → berkas renderer asli
// - WS   /ipc         → invoke/send/on dari preload shim
// - GET  /ipc-sync    → ipcRenderer.sendSync (XHR sinkron)
// - GET  /deeplink    → forward zcode://oauth/callback ke app 'second-instance'
// - GET  /health      → untuk deteksi READY oleh shell Java
// ============================================================================
import http from 'node:http'
import { WebSocketServer } from 'ws'
import { readFileSync, existsSync, statSync } from 'node:fs'
import { join, extname, normalize } from 'node:path'
import { routeInvoke, routeSend, hasHandler, listHandlers } from './electron-stub.mjs'

const MIME = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.mjs': 'text/javascript; charset=utf-8',
  '.cjs': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json',
  '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon', '.woff': 'font/woff', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.otf': 'font/otf',
  '.map': 'application/json', '.txt': 'text/plain; charset=utf-8', '.wasm': 'application/wasm',
  '.webp': 'image/webp', '.mp3': 'audio/mpeg', '.mp4': 'video/mp4', '.webm': 'video/webm', '.pdf': 'application/pdf',
}

export function startBridge(opts) {
  const { rendererDir, injectJs, port = 0, log } = opts
  const state = {
    invokeSeq: 0,
    wsSet: new Set(),
    onPush: null, // diisi electron-stub bus
    currentUrl: null,
    followCurrent: true,
    ports: new Map(), // portId → realPort (MessagePortMain-style dari stub)
    portSeq: 0,
    pendingEvents: [], // event sebelum renderer tersambung (ServicePort, startup state)
    lastReadyState: null,      // database-startup-state phase=ready — untuk replay saat renderer masuk
    lastServicePort: null,     // {args, portIds} — event zcode:service-port terakhir
    portHistory: new Map(),    // portId → [payload JSON] pesan keluar host per port (untuk peer baru)
  }
  const b64 = (u8) => Buffer.from(u8).toString('base64')
  function registerPort(p) {
    const id = ++state.portSeq
    state.ports.set(id, p)
    try {
      p.on('message', (e) => {
        const v = e && e.data !== undefined ? e.data : e
        log('[port-out]', id, v instanceof Uint8Array ? v.length + 'B hex=' + Buffer.from(v.slice(0, 24)).toString('hex') : typeof v + ' ' + JSON.stringify(v)?.slice(0, 100))
        const payload = v instanceof Uint8Array ? { t: 'p', portId: id, b64: b64(v) } : { t: 'p', portId: id, value: v === undefined ? null : v }
        const s = JSON.stringify(payload)
        try {
          const h = state.portHistory.get(id) || []
          if (h.length < 64) h.push(s)
          state.portHistory.set(id, h)
        } catch { }
        for (const ws of state.wsSet) { try { ws.send(s) } catch { } }
      })
      p.start?.()
    } catch (e) { log('[port]', e.message) }
    return id
  }

  const server = http.createServer((req, res) => {
    const u = new URL(req.url, 'http://127.0.0.1')
    const p = u.pathname
    try {
      if (p === '/health') {
        res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ ok: true, ts: Date.now() })); return
      }
      if (p === '/ipc-sync') {
        // ipcRenderer.sendSync: channel + args via query (args JSON)
        const ch = u.searchParams.get('channel') || ''
        let args = []
        try { args = JSON.parse(u.searchParams.get('args') || '[]') } catch { }
        const id = ++state.invokeSeq
        Promise.resolve(routeInvoke(ch, args)).then((r) => {
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ id, result: r === undefined ? null : r }))
        }).catch((e) => {
          res.writeHead(200, { 'Content-Type': 'application/json' })
          res.end(JSON.stringify({ id, error: String(e?.message || e).slice(0, 500) }))
        })
        return
      }
      if (p === '/deeplink') {
        const url = u.searchParams.get('url') || ''
        state.onDeeplink?.(url)
        res.writeHead(200, { 'Content-Type': 'text/plain' }); res.end('ok'); return
      }
      if (p === '/__inject.js') {
        res.writeHead(200, { 'Content-Type': 'text/javascript; charset=utf-8', 'Cache-Control': 'no-store' }); res.end(injectJs); return
      }
      if (p === '/openexternal') {
        const url = u.searchParams.get('url') || ''
        state.onOpenExternal?.(url)
        res.writeHead(200, { 'Content-Type': 'text/plain' }); res.end('ok'); return
      }
      if (p === '/current-url') {
        res.writeHead(200, { 'Content-Type': 'application/json' }); res.end(JSON.stringify({ url: state.currentUrl || '/index.html' })); return
      }
      const curBase = (state.currentUrl || '').split('?')[0]
      if ((p === '/' || p === '/index.html') && curBase && curBase.startsWith('/') && curBase !== p && state.followCurrent) {
        res.writeHead(302, { Location: state.currentUrl }); res.end(); return
      }
      // statis renderer + SPA fallback
      let fp = normalize(join(rendererDir, p === '/' ? 'index.html' : decodeURIComponent(p)))
      if (!fp.startsWith(normalize(rendererDir))) { res.writeHead(403); res.end(); return }
      if (!existsSync(fp) || statSync(fp).isDirectory()) {
        // fallback aset tanpa ekstensi → index.html (SPA)
        if (!extname(fp)) fp = join(rendererDir, 'index.html')
        else { res.writeHead(404); res.end('not found: ' + p); return }
      }
      const type = MIME[extname(fp).toLowerCase()] || 'application/octet-stream'
      const buf = type.startsWith('text/html')
        ? Buffer.from(readFileSync(fp).toString('utf8').replace(/<head>/i, '<head><script src="/__inject.js"></script>'))
        : readFileSync(fp)
      res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-cache' })
      res.end(buf)
    } catch (e) {
      log('[http]', e.message)
      try { res.writeHead(500); res.end('err') } catch { }
    }
  })

  const wss = new WebSocketServer({ server, path: '/ipc' })
  wss.on('connection', (ws) => {
    state.wsSet.add(ws)
    log('[ws] renderer tersambung — flush', state.pendingEvents.length, 'event tertunda; replay state=', !!state.lastReadyState, 'port=', !!state.lastServicePort)
    const pend = state.pendingEvents.splice(0)
    for (const payload of pend) { try { ws.send(payload) } catch { } }
    // replay: state ready + service port (renderer yang masuk telat / reload)
    try {
      if (state.lastReadyState) ws.send(JSON.stringify({ t: 'e', channel: 'zcode:database-startup-state', args: [state.lastReadyState], ports: [] }))
      if (state.lastServicePort) ws.send(JSON.stringify({ t: 'e', channel: 'zcode:service-port', args: state.lastServicePort.args, ports: state.lastServicePort.portIds }))
    } catch { }
    ws.on('message', async (raw) => {
      let m = null
      try { m = JSON.parse(raw.toString()) } catch { return }
      if (m.t === 'i') {
        // invoke
        try {
          log('[invoke]', m.channel)
          if (!hasHandler(m.channel)) {
            log('[ipc] invoke TANPA handler:', m.channel)
            ws.send(JSON.stringify({ t: 'i', id: m.id, error: `No handler registered for ${m.channel}` }))
            return
          }
          const r = await routeInvoke(m.channel, m.args || [])
          ws.send(JSON.stringify({ t: 'i', id: m.id, result: r === undefined ? null : r }))
        } catch (e) {
          ws.send(JSON.stringify({ t: 'i', id: m.id, error: String(e?.message || e).slice(0, 800) }))
        }
      } else if (m.t === 's') {
        try { routeSend(m.channel, m.args || []) } catch (e) { log('[ipc] send err', m.channel, e.message) }
      } else if (m.t === 'u') {
        // unsubscribe event dari shim (tidak perlu aksi di sini)
      } else if (m.t === 'p') {
        // pesan dari port virtual renderer → realPort
        const rp = state.ports.get(m.portId)
        if (!rp) { log('[port-in] port hilang', m.portId); return }
        try {
          const v = m.b64 != null ? new Uint8Array(Buffer.from(m.b64, 'base64')) : m.value
          log('[port-in]', m.portId, (v instanceof Uint8Array ? v.length + 'B hex=' + Buffer.from(v.slice(0, 24)).toString('hex') : typeof v + ' ' + JSON.stringify(v)?.slice(0, 100)))
          rp.postMessage(v, m.transfer || [])
          // Gema Initialize(200): jika host sudah mengirim 200 lebih dulu dan tak mengulang,
          // klien renderer menunggu balasan 200 — kirimkan frame 200 yang sama kembali.
          if (v instanceof Uint8Array && v.length === 6 && v[0] === 4 && v[3] === 200) {
            const s2 = JSON.stringify({ t: 'p', portId: m.portId, b64: Buffer.from(v).toString('base64') })
            for (const ws2 of state.wsSet) { try { ws2.send(s2) } catch { } }
          }
        } catch (e) { log('[port-send]', e.message) }
      } else if (m.t === 'phistory') {
        // kirim riwayat pesan port ke peer baru (handshake Initialize dll.)
        const h = state.portHistory.get(m.portId) || []
        try { ws.send(JSON.stringify({ t: 'ph', portId: m.portId, items: h })) } catch { }
      } else if (m.t === 'pc') {
        const rp = state.ports.get(m.portId)
        try { rp?.close?.() } catch { }
        state.ports.delete(m.portId)
      }
    })
    ws.on('close', () => state.wsSet.delete(ws))
  })

  return new Promise((resolve) => {
    server.listen(port, '127.0.0.1', () => {
      const realPort = server.address().port
      resolve({
        port: realPort,
        server,
        setCurrentUrl: (u) => { state.currentUrl = u },
        setFollowCurrent: (v) => { state.followCurrent = !!v },
        registerPort,
        pushEvent(ch, args, portIds = []) {
          const payload = JSON.stringify({ t: 'e', channel: ch, args, ports: portIds })
          try {
            if (ch === 'zcode:database-startup-state' && args && args[0] && args[0].phase === 'ready') state.lastReadyState = args[0]
            if (ch === 'zcode:service-port') state.lastServicePort = { args, portIds }
          } catch { }
          if (String(ch).includes('startup-state') || String(ch).includes('service-port')) log('[push]', ch, 'clients=' + state.wsSet.size, 'ports=' + portIds.length)
          if (state.wsSet.size === 0) {
            if (state.pendingEvents.length < 800) state.pendingEvents.push(payload)
            return
          }
          for (const ws of state.wsSet) { try { ws.send(payload) } catch { } }
        },
        onDeeplink: null,
        broadcast: (obj) => { const s = JSON.stringify(obj); for (const ws of state.wsSet) { try { ws.send(s) } catch { } } },
        log,
        listHandlers,
      })
    })
  })
}
