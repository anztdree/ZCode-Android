// ============================================================================
// ZCode Mobile — electron-stub.mjs
// Implementasi modul 'electron' untuk menjalankan out/main/index.js ASLI di
// node murni. Prinsip: jalur chat/login harus fungsional; sisanya no-op aman.
// Semua API yang tak dikenal dicatat (unknown-*) agar bisa diiterasi.
// ============================================================================
import { EventEmitter } from 'node:events'
import { Worker, MessageChannel as NodeMessageChannel } from 'node:worker_threads'
import { join, dirname, basename, sep } from 'node:path'
import { existsSync, mkdirSync } from 'node:fs'

// ---------------- state global runtime ----------------
const G = {
  bus: null,                 // ws-bridge: {pushEvent(ch,args), windowUrl(win,url), log(...)}
  windows: new Set(),
  winSeq: 0,
  portSeq: 0,
  resourcesPath: process.env.ZCODE_RES || '/opt/zhost/res',
  appRoot: process.env.ZCODE_APPROOT || dirname(dirname(new URL(import.meta.url).pathname)),
  rendererDir: process.env.ZCODE_RENDERER || null, // diisi main.mjs
  userData: process.env.ZCODE_USERDATA || '/opt/zdata',
  screenW: Number(process.env.ZCODE_SCREEN_W || 390),
  screenH: Number(process.env.ZCODE_SCREEN_H || 844),
  dpr: Number(process.env.ZCODE_DPR || 2.75),
  deepLinkUrl: null,
}
export function initStub(bus, opts = {}) {
  G.bus = bus
  Object.assign(G, opts)
}
const warn = (...a) => { try { G.bus?.log('[stub]', ...a) } catch { } }

// ---------------- app ----------------
const app = new EventEmitter()
app.isPackaged = true
app.name = 'ZCode'
app.version = process.env.ZCODE_VERSION || '3.15.1'
let readyResolve
const readyP = new Promise((r) => (readyResolve = r))
app.whenReady = () => readyP
app.isReady = () => G.readyFired === true
app.quit = () => { app.emit('before-quit'); app.emit('will-quit'); process.exit(0) }
app.exit = (c = 0) => process.exit(c)
app.relaunch = () => warn('app.relaunch (no-op)')
app.focus = () => { }
app.hide = () => { }
app.show = () => { }
app.setBadgeCount = () => true
app.addRecentDocument = () => { }
app.clearRecentDocuments = () => { }
app.setAboutPanelOptions = () => { }
app.setLoginItemSettings = () => { }
app.getLoginItemSettings = () => ({ openAtLogin: false })
app.isInApplicationsFolder = () => true
app.requestSingleInstanceLock = () => true
app.hasSingleInstanceLock = () => true
app.releaseSingleInstanceLock = () => { }
app.setActivationPolicy = () => { }
app.getSystemVersion = () => '15.0'
app.getAppLicenseInfo = () => ({})
app.isEmojiPanelSupported = () => false
app.commandLine = { appendSwitch: () => { }, appendArgument: () => { }, hasSwitch: () => false, getSwitchValue: () => '' }
const _paths = { home: process.env.HOME || '/root', appData: G.userData, userData: G.userData, temp: '/tmp', logs: join(G.userData, 'logs'), exe: '/opt/zhost/bin/node', module: G.appRoot, documents: G.userData, downloads: G.userData, desktop: G.userData, pictures: G.userData, videos: G.userData, music: G.userData, crashDumps: join(G.userData, 'crashes') }
export { app }
app.getPath = (n) => {
  if (_paths[n]) { try { mkdirSync(_paths[n], { recursive: true }) } catch { } return _paths[n] }
  warn('app.getPath unknown:', n); return G.userData
}
app.setPath = (n, p) => { _paths[n] = p }
app.getAppPath = () => G.appRoot
app.getLocale = () => process.env.ZCODE_LOCALE || 'id-ID'
app.getLocaleCountryCode = () => (process.env.ZCODE_LOCALE || 'id-ID').split('-')[1] || 'ID'
app.getGPUInfo = async () => ({})
app.getAppMetrics = () => []
app.setName = () => { }
app.setAppUserModelId = () => { }
app.isDefaultProtocolClient = () => true
app.setAsDefaultProtocolClient = () => true
app.removeAsDefaultProtocolClient = () => { }
app.setAccessibilitySupportEnabled = () => { }
app.isAccessibilitySupportEnabled = () => false
app.getBadgeCount = () => 0
app.setJumpList = () => true
app.getJumpListSettings = () => ({ minItems: 10, removedItems: [] })
app.setUserTasks = () => true
app.getFileIcon = async () => nativeImage.createEmpty()
app.getLoginItemSettings = () => ({ openAtLogin: false, executableWillLaunchAtLogin: false })
app.runningUnderARM64Translation = () => false
app.enableSandbox = () => { }
app.disableHardwareAcceleration = () => { }
export function fireAppReady() { G.readyFired = true; readyResolve(); app.emit('ready') }

// ---------------- webContents stub ----------------
function makeWebContents(win) {
  const wc = new EventEmitter()
  wc.id = ++G.winSeq
  wc._win = win
  wc._url = ''
  wc.isDestroyed = () => win._destroyed
  wc.getOSProcessId = () => process.pid
  wc.getProcessId = () => process.pid
  wc.getTitle = () => 'ZCode'
  wc.getURL = () => wc._url
  wc.loadURL = async (url) => { wc._url = String(url); G.bus?.windowUrl(wc.id, wc._url); setTimeout(() => { try { wc.emit('dom-ready'); wc.emit('did-finish-load') } catch { } }, 250); return }
  wc.isLoading = () => false
  wc.isLoadingMainFrame = () => false
  wc.isStopped = () => true
  wc.isFocused = () => true
  wc.isCrashed = () => false
  wc.canGoBack = () => false
  wc.canGoForward = () => false
  wc.goBack = () => { }
  wc.goForward = () => { }
  wc.navigationHistory = { canGoBack: () => false, canGoForward: () => false, goBack: () => { }, goForward: () => { }, getEntryIndex: () => 0, restoreIndex: () => { } }
  wc.loadFile = async (p, opts) => {
    // ../renderer/index.html → http URL lewat bridge
    let url = null
    if (G.rendererDir && String(p).startsWith(G.rendererDir)) {
      const rel = String(p).slice(G.rendererDir.length).replace(/^\//, '')
      const q = new URLSearchParams(opts?.query || {}).toString()
      url = '/' + rel + (q ? '?' + q : '')
    } else {
      const base = basename(String(p))
      url = '/' + base + (opts?.query ? '?' + new URLSearchParams(opts.query).toString() : '')
    }
    wc._url = url
    G.bus?.windowUrl(wc.id, url)
    setTimeout(() => { try { wc.emit('dom-ready'); wc.emit('did-finish-load') } catch { } }, 250)
    return
  }
  wc.postMessage = (ch, msg, ports = []) => {
    // transfer MessagePortMain ke renderer → port virtual via WS
    warn('[postMessage]', ch, 'ports=' + (ports || []).length)
    const ids = (ports || []).map((p) => G.bus?.registerPort(p)).filter((x) => x != null)
    G.bus?.pushEvent(ch, [msg], ids)
  }
  wc._emitNav = (u) => { }
  wc.send = (ch, ...args) => { if (String(ch).includes('tartup') || String(ch).includes('database')) { try { warn('[send]', ch, JSON.stringify(args[0])?.slice(0, 12000)) } catch { } } ; G.bus?.pushEvent(ch, args, []) }
  wc.executeJavaScript = async () => undefined
  wc.focus = () => { }
  wc.stop = () => { }
  wc.reload = () => { }
  wc.openDevTools = () => { }
  wc.closeDevTools = () => { }
  wc.isDevToolsOpened = () => false
  wc.setWindowOpenHandler = () => { }
  wc.getSession = () => sessionStub
  wc.printToPDF = async () => Buffer.alloc(0)
  wc.beginFrameSubscription = () => { }
  wc.endFrameSubscription = () => { }
  wc.insertCSS = async () => ''
  wc.getWebPreferences = () => ({})
  wc.copy = () => { }
  wc.paste = () => { }
  wc.selectAll = () => { }
  wc.undo = () => { }
  wc.redo = () => { }
  wc.cut = () => { }
  wc.zoomFactor = 1
  wc.setZoomFactor = () => { }
  wc.setBackgroundThrottling = () => { }
  wc.debugger = { attach: () => { }, detach: () => { }, sendCommand: async () => ({}) }
  wc.attached = false
  return wc
}

// ---------------- BrowserWindow ----------------
export class BrowserWindow extends EventEmitter {
  constructor(opts = {}) {
    super()
    this.id = ++G.winSeq
    this._opts = opts
    this._destroyed = false
    this.webContents = makeWebContents(this)
    G.windows.add(this)
    app.emit('web-contents-created', { preventDefault() { } }, this.webContents)
    app.emit('browser-window-created', { preventDefault() { } }, this)
    setTimeout(() => { if (!this._destroyed) this.emit('ready-to-show') }, 30)
    warn('BrowserWindow dibuat:', opts?.title || '(tanpa judul)', 'preload=' + (opts?.webPreferences?.preload || '-'))
  }
  static getAllWindows() { return [...G.windows] }
  static fromId(id) { return [...G.windows].find((w) => w.id === id) || null }
  static getFocusedWindow() { return [...G.windows][0] || null }
  get isDestroyed() { return () => this._destroyed }
  isDestroyedSync() { return this._destroyed }
  loadURL(u) { return this.webContents.loadURL(u) }
  loadFile(p, o) { return this.webContents.loadFile(p, o) }
  getURL() { return this.webContents.getURL() }
  show() { }
  hide() { }
  close() { this._destroyed = true; G.windows.delete(this); this.emit('closed') }
  destroy() { this.close() }
  focus() { }
  blur() { }
  isMinimized() { return false }
  restore() { }
  isVisible() { return true }
  isMaximized() { return false }
  maximize() { }
  unmaximize() { }
  isFullScreen() { return false }
  setFullScreen() { }
  setTitle() { }
  getTitle() { return 'ZCode' }
  getBounds() { return { x: 0, y: 0, width: G.screenW, height: G.screenH } }
  setBounds() { }
  setSize() { }
  getPosition() { return [0, 0] }
  setPosition() { }
  setMenuBarVisibility() { }
  setWindowButtonVisibility() { }
  setAlwaysOnTop() { }
  moveTop() { }
  center() { }
  on(ev, cb) { return super.on(ev, cb) }
}

// ---------------- ipcMain ----------------
const ipcMain = new EventEmitter()
const handlers = new Map()
ipcMain.handle = (ch, fn) => { handlers.set(ch, fn); warn('[handle]', ch); return ipcMain }
ipcMain.removeHandler = (ch) => { handlers.delete(ch) }
ipcMain.handleOnce = (ch, fn) => { handlers.set(ch, fn) }
ipcMain.invoke = async (ch, ...args) => {
  const fn = handlers.get(ch)
  if (!fn) throw new Error('No handler registered for ' + ch)
  return fn({ sender: { send: () => { }, id: 1 }, ports: [] }, ...args)
}
export function routeInvoke(ch, args) {
  const fn = handlers.get(ch)
  if (!fn) return { __nohandler: true }
  return fn({ sender: { send: () => { }, id: 1 }, ports: [], reply: () => { } }, ...args)
}
export function hasHandler(ch) { return handlers.has(ch) }
export function listHandlers() { return [...handlers.keys()] }
export function routeSend(ch, args) { ipcMain.emit(ch, { sender: { send: () => { }, id: 1 }, ports: [] }, ...args) }
export { ipcMain }

// ---------------- utilityProcess (worker_threads, semantik terbukti bridge.mjs) ----------------
let workerSeq = 0
export const utilityProcess = {
  fork(modulePath, args = [], opts = {}) {
    const id = ++workerSeq
    const child = new EventEmitter()
    child.__id = id
    child.pid = 30000 + id
    const wenv = { ...(opts.env || process.env), ZHOST_ENTRY: modulePath, ZHOST_RES: G.resourcesPath }
    const worker = new Worker(new URL('./host-worker.mjs', import.meta.url), {
      env: wenv, stdout: 'pipe', stderr: 'pipe',
      resourceLimits: { maxOldGenerationSizeMb: 1536 },
    })
    child.__worker = worker
    child.stdout = worker.stdout
    child.stderr = worker.stderr
    worker.on('message', (m) => {
      // bootstrap membungkus: {data, ports}
      const ev = { data: m?.data, ports: m?.ports || [] }
      child.emit('message', ev.data, ev)
    })
    worker.on('error', (e) => child.emit('error', e))
    worker.on('exit', (c) => { child.emit('exit', c ?? 0); try { child.stdout?.unpipe?.() } catch { } })
    child.postMessage = (value, transfer = []) => {
      // Electron: parentPort.on('message', e => e.data + e.ports) — bootstrap membaca m.data/m.ports
      const raw = transfer.map((t) => (t && t.__raw) ? t.__raw : t)
      worker.postMessage({ data: value, ports: raw }, raw)
    }
    child.kill = () => { try { worker.terminate() } catch { } }
    setTimeout(() => child.emit('spawn'), 10)
    warn('utilityProcess.fork:', opts.serviceName || basename(modulePath), modulePath)
    return child
  },
}

// ---------------- MessageChannelMain ----------------
export class MessageChannelMain {
  constructor() {
    const ch = new NodeMessageChannel()
    const wrap = (p) => {
      const listeners = new Set()
      const closeL = new Set()
      const w = {
        __raw: p,
        on(ev, cb) {
          if (ev === 'message') { listeners.add(cb); p.on('message', (v, ports) => { for (const l of [...listeners]) l({ data: v, ports: ports || [] }) }) }
          else if (ev === 'close') { closeL.add(cb); p.on('close', () => { for (const l of [...closeL]) l() }) }
          return w
        },
        once(ev, cb) { this.on(ev, (e) => { this.removeListener(ev, cb); cb(e) }); return w },
        off(ev, cb) { if (ev === 'message') { listeners.delete(cb) } else if (ev === 'close') { closeL.delete(cb) } return w },
        addEventListener(ev, cb) { return this.on(ev, cb) },
        removeEventListener(ev, cb) { return this.off(ev, cb) },
        postMessage(v, transfer = []) { p.postMessage(v, transfer) },
        start() { p.start() },
        close() { try { p.close() } catch { } },
        unref() { try { p.unref() } catch { } },
        ref() { try { p.ref() } catch { } },
      }
      return w
    }
    this.port1 = wrap(ch.port1)
    this.port2 = wrap(ch.port2)
  }
}

// ---------------- session ----------------
const noopReq = () => { }
const sessionStub = {
  defaultSession: null,
  fromPartition: () => sessionStub,
  fromPath: () => sessionStub,
  getUserAgent: () => 'Mozilla/5.0 (Linux; Android 15) ZCodeMobile',
  setUserAgent: () => { },
  getWebContents: () => null,
  setPermissionRequestHandler: () => { },
  setPermissionCheckHandler: () => { },
  setDevicePermissionHandler: () => { },
  setDisplayMediaRequestHandler: () => { },
  selectAudioOutput: async () => null,
  webRequest: new Proxy({}, { get: () => () => { } }),
  protocol: { handle: () => { }, unhandle: () => { }, registerFileProtocol: () => true, registerBufferProtocol: () => true, registerStringProtocol: () => true, registerStreamProtocol: () => true, registerSchemesAsPrivileged: () => { }, interceptFileProtocol: () => true, unregisterProtocol: () => true, unregisterInterceptor: () => true, isProtocolHandled: () => true, isProtocolIntercepted: () => false },
  preconnect: () => { },
  clearCache: async () => { },
  clearStorageData: async () => { },
  getStoragePath: () => G.userData,
  cookies: { get: async () => [], set: async () => { }, on: () => { }, remove: async () => { }, flushStore: async () => { } },
  canPreconnect: () => { },
  spellcheckEnabled: false,
  setSpellCheckerEnabled: () => { },
  availableSpellCheckerLanguages: [],
  setSpellCheckerLanguages: () => { },
  handlers: {},
  ses: {},
  isPersistent: () => true,
  getPreloads: () => [],
  setPreloads: () => { },
  registerPreloadScript: () => { },
  unregisterPreloadScript: () => { },
  getAllPreloads: () => [],
  createInterruptedDownload: () => { },
  downloadURL: () => { },
  allowNTLMCredentialsForDomains: () => { },
  imports: {},
}
sessionStub.defaultSession = sessionStub
const session = sessionStub
export { sessionStub as session }

// ---------------- dialog / shell / clipboard ----------------
export const dialog = {
  showOpenDialog: async () => ({ canceled: true, filePaths: [] }),
  showSaveDialog: async () => ({ canceled: true, filePath: undefined }),
  showMessageBox: async () => ({ response: 0, checkboxChecked: false }),
  showErrorBox: () => { },
}
export const shell = {
  openExternal: async (url) => { G.bus?.openExternal(String(url)); return },
  openPath: async () => '',
  showItemInFolder: () => { },
  trashItem: async () => { },
  beep: () => { },
  writeShortcutLink: () => true,
  readShortcutLink: () => ({}),
}
const clipStore = { text: '', html: '', rtf: '', buf: Buffer.alloc(0) }
export const clipboard = {
  writeText: (t) => { clipStore.text = String(t ?? '') },
  readText: () => clipStore.text,
  writeHTML: (t) => { clipStore.html = String(t ?? '') },
  readHTML: () => clipStore.html,
  writeRTF: (t) => { clipStore.rtf = String(t ?? '') },
  readRTF: () => clipStore.rtf,
  write: (o) => { if (o?.text != null) clipStore.text = String(o.text) },
  read: () => clipStore.text,
  readBuffer: () => clipStore.buf,
  writeBuffer: (b) => { clipStore.buf = b },
  availableFormats: () => (clipStore.text ? ['text/plain'] : []),
  clear: () => { clipStore.text = '' },
}

// ---------------- net (fetch-based) ----------------
function clientRequest(inp, opts) {
  let url = typeof inp === 'string' ? inp : inp?.url || inp?.href
  let method = 'GET', headers = {}
  if (inp && typeof inp === 'object') {
    method = inp.method || 'GET'
    headers = inp.headers && typeof inp.headers === 'object' ? { ...inp.headers } : {}
    if (!url) {
      const p = inp.protocol || 'https:', h = inp.hostname || inp.host, port = inp.port, path = inp.path || '/'
      url = `${p}//${h}${port ? ':' + port : ''}${path}`
    }
  }
  if (opts && typeof opts === 'object' && opts !== undefined && !(opts instanceof Function)) {
    method = opts.method || method
    if (opts.headers) Object.assign(headers, opts.headers)
  }
  const req = new EventEmitter()
  const chunks = []
  let ended = false, aborted = false
  req.setHeader = (k, v) => { headers[k] = v; return req }
  req.getHeader = (k) => headers[k]
  req.removeHeader = (k) => { delete headers[k] }
  req.setHeaderNames?undefined:undefined
  req.chunkedEncoding = false
  req.setChunkedEncoding = () => { }
  req.setNoDelay = () => { }
  req.setSocketKeepAlive = () => { }
  req.setTimeout = () => { }
  req.abort = () => { aborted = true }
  req.getOption = (k) => undefined
  req.write = (c) => { if (!ended) chunks.push(Buffer.isBuffer(c) ? c : Buffer.from(String(c))); return true }
  req.end = (c, cb) => {
    if (c) req.write(c)
    ended = true
    if (typeof cb === 'function') cb()
    if (aborted) return
    const body = chunks.length ? Buffer.concat(chunks) : undefined
    fetch(url, { method, headers, body: method === 'GET' || method === 'HEAD' ? undefined : body })
      .then(async (r) => {
        const bufs = Buffer.from(await r.arrayBuffer())
        const res = new EventEmitter()
        res.statusCode = r.status
        res.statusMessage = r.statusText
        res.headers = Object.fromEntries(r.headers.entries())
        res.rawHeaders = [...r.headers.entries()].flatMap(([k, v]) => [k, v])
        res.headersSent?undefined:undefined
        res.setEncoding = () => { }
        req.emit('response', res)
        if (bufs.length) res.emit('data', bufs)
        res.emit('end')
        res.emit('close')
        req.emit('close')
      })
      .catch((e) => { req.emit('error', e); req.emit('close') })
    return req
  }
  return req
}
export const net = {
  fetch: (...a) => fetch(...a),
  request: (inp, opts) => clientRequest(inp, opts),
  isOnline: () => true,
  online: true,
}

// ---------------- lain-lain ----------------
export const nativeTheme = { shouldUseDarkColors: true, shouldUseHighContrastColors: false, shouldUseInvertedColorScheme: false, themeSource: 'system', on: () => { }, once: () => { }, removeListener: () => { } }
export const screen = {
  getPrimaryDisplay: () => ({ id: 1, scaleFactor: G.dpr, size: { width: G.screenW, height: G.screenH }, workArea: { x: 0, y: 0, width: G.screenW, height: G.screenH }, workAreaSize: { width: G.screenW, height: G.screenH }, rotation: 0, touchSupport: 'unsupported', colorDepth: 24, colorSpace: 'srgb', depthPerComponent: 8, displayFrequency: 60, bounds: { x: 0, y: 0, width: G.screenW, height: G.screenH }, internal: true }),
  getAllDisplays: () => [screen.getPrimaryDisplay()],
  getCursorScreenPoint: () => ({ x: 0, y: 0 }),
  getDisplayMatching: () => screen.getPrimaryDisplay(),
  getDisplayNearestPoint: () => screen.getPrimaryDisplay(),
  on: () => { }, once: () => { }, removeListener: () => { },
}
export const systemPreferences = {
  getUserDefault: (k) => (k === 'AppleInterfaceStyle' ? undefined : undefined),
  setColorScheme: () => { },
  getAccentColor: () => '#4A4A4A',
  getColor: () => '#000000',
  isAeroGlassEnabled: () => false,
  isDarkMode: () => true,
  isSwipeTrackingFromScrollEventsEnabled: () => false,
  on: () => { }, once: () => { }, removeListener: () => { },
  subscribeNotification: () => { }, unsubscribeNotification: () => { },
  subscribeLocalNotification: () => { }, unsubscribeLocalNotification: () => { },
}
export const powerMonitor = new EventEmitter()
export const powerSaveBlocker = { start: () => 1, stop: () => true, isStarted: () => false }
export const globalShortcut = { register: () => true, registerAll: () => { }, unregister: () => { }, unregisterAll: () => { }, isRegistered: () => false }
export const protocol = { registerSchemesAsPrivileged: () => { }, handle: () => { }, unhandle: () => { }, registerFileProtocol: () => true, registerBufferProtocol: () => true, registerStringProtocol: () => true, unregisterProtocol: () => true, interceptFileProtocol: () => true, isProtocolIntercepted: () => false, isProtocolHandled: () => true, unregisterInterceptor: () => true }
export const Notification = class { constructor() { } show() { } on() { return this } once() { return this } static isSupported() { return false } }
export const Tray = class { constructor() { } setToolTip() { } setContextMenu() { } setImage() { } on() { return this } destroy() { } }
export const Menu = {
  buildFromTemplate: () => ({ popup: () => { }, append: () => { }, items: [] }),
  setApplicationMenu: () => { },
  getApplicationMenu: () => null,
  setContextMenu: () => { },
  buildFromTemplateForAction: () => null,
}
export const MenuItem = class { constructor(o) { Object.assign(this, o || {}) } }
export const nativeImage = {
  createEmpty: () => ({ isEmpty: () => true, toDataURL: () => '', toPNG: () => Buffer.alloc(0), resize: () => nativeImage.createEmpty(), getSize: () => ({ width: 0, height: 0 }) }),
  createFromPath: (p) => { warn('nativeImage.createFromPath', p); return nativeImage.createEmpty() },
  createFromDataURL: () => nativeImage.createEmpty(),
  createFromBuffer: () => nativeImage.createEmpty(),
}
export const desktopCapturer = { getSources: async () => [] }
export const crashReporter = { start: () => { }, addExtraParameter: () => { }, removeExtraParameter: () => { }, getParameters: () => ({}) }
export const webContents = {
  getAllWebContents: () => BrowserWindow.getAllWindows().map((w) => w.webContents),
  fromId: (id) => webContents.getAllWebContents().find((w) => w.id === id) || null,
  getFocusedWebContents: () => webContents.getAllWebContents()[0] || null,
  getActiveWebContents: () => webContents.getAllWebContents()[0] || null,
}
export const webFrameMain = { fromId: () => null }
export const contentTracing = { startRecording: async () => { }, stopRecording: async () => '' }
export const inAppPurchase = { getProducts: async () => [], purchaseProduct: async () => false }
export const netLog = { startLogging: async () => { }, stopLogging: async () => '' }
export const pushNotifications = { on: () => { }, register: async () => { } }
export const safeStorage = {
  isEncryptionAvailable: () => true,
  encryptString: (s) => Buffer.from(String(s), 'utf8'),
  decryptString: (b) => Buffer.from(b).toString('utf8'),
  isPlainTextAvailable: () => true,
}
export const share = { show: async () => 'success' }
export const webUtils = { getPathForFile: () => null }

export const electron = {
  app, BrowserWindow, ipcMain, utilityProcess, MessageChannelMain, session,
  dialog, shell, clipboard, net, nativeTheme, screen, systemPreferences,
  powerMonitor, powerSaveBlocker, globalShortcut, protocol, Notification, Tray,
  Menu, MenuItem, nativeImage, desktopCapturer, crashReporter, webContents,
  webFrameMain, contentTracing, inAppPurchase, netLog, pushNotifications,
  safeStorage, share, webUtils,
}
export default new Proxy({}, {
  get(t, k) { if (k in electron) return electron[k]; if (app && k in app) return app[k]; return undefined },
  has(t, k) { return k in electron || (app && k in app) },
})
