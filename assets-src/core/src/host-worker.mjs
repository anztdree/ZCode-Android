// ZCode Mobile — host worker: menjalankan host ZCode ASLI (out/host/index.js)
// dengan shim process.parentPort ala Electron utilityProcess (worker_threads).
// Tidak mengubah satu baris pun dari host ZCode.
import { parentPort } from 'node:worker_threads'

// Electron MessagePortMain: 'message' event => MessageEvent {data}
// worker_threads MessagePort: 'message' event => nilai mentah
// Adapter: bungkus port agar terlihat seperti port gaya Electron bagi host.
function adaptPort(p) {
  const listeners = new Set()
  const closeListeners = new Set()
  p.on('message', (v) => { for (const l of [...listeners]) l({ data: v }) })
  p.on('close', () => { for (const l of [...closeListeners]) l() })
  return {
    on(ev, cb) { ev === 'message' ? listeners.add(cb) : ev === 'close' && closeListeners.add(cb) },
    off(ev, cb) { ev === 'message' ? listeners.delete(cb) : ev === 'close' && closeListeners.delete(cb) },
    addEventListener(ev, cb) { this.on(ev, cb) },
    removeEventListener(ev, cb) { this.off(ev, cb) },
    once(ev, cb) { ev === 'close' ? closeListeners.add(cb) : ev === 'message' && listeners.add((e) => { listeners.delete(cb); cb(e) }) },
    postMessage(v, transfer) { p.postMessage(v, transfer) },
    start() { p.start() },
    close() { p.close() },
  }
}

// 1) Shim process.parentPort (Electron utilityProcess style: MessageEvent {data, ports})
process.parentPort = {
  on(ev, cb) {
    parentPort.on('message', (m) => cb({ data: m.data, ports: (m.ports || []).map(adaptPort) }))
  },
  postMessage(data, transfer = []) {
    parentPort.postMessage({ data, ports: transfer }, transfer)
  },
}

// 2) resourcesPath → config builtin provider + glm tools (seperti Electron packaged)
process.resourcesPath = process.env.ZHOST_RES || '/opt/zhost/res'

// 3) Jalankan host ASLI ZCode
await import(process.env.ZHOST_ENTRY || '/opt/zhost/out/host/index.js')
