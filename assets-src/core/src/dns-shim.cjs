// ============================================================================
// ZCode Mobile — dns-shim.cjs (v1.0)
// Android tidak punya /etc/resolv.conf — resolver glibc akan jatuh ke 127.0.0.1
// dan DNS gagal total. Shim ini mem-patch dns.lookup agar memakai DoH (1.1.1.1,
// IP literal — bootstrap tanpa DNS). Aktif otomatis hanya bila /etc/resolv.conf
// tidak ada, atau dipaksa via ZCODE_DNS_SHIM=1 (untuk pengujian desktop).
// Dimuat otomatis dari main.mjs (murni JS, tanpa root/privilese apa pun).
// ============================================================================
'use strict'
;(function () {
  const fs = require('node:fs')
  if (process.platform !== 'linux') return
  const forced = process.env.ZCODE_DNS_SHIM === '1'
  if (!forced && !process.env.ZCODE_MOBILE) return
  if (!forced && fs.existsSync('/etc/resolv.conf')) return

  const dns = require('node:dns')
  const https = require('node:https')
  const cache = new Map()
  const TTL = 5 * 60 * 1000
  const IP_RE = /^\d{1,3}(\.\d{1,3}){3}$/

  function dohResolve4(name, cb) {
    if (IP_RE.test(name)) return cb(null, [name])
    const path = '/dns-query?name=' + encodeURIComponent(name) + '&type=A'
    const req = https.get(
      { host: '1.1.1.1', servername: 'cloudflare-dns.com', path: path, headers: { accept: 'application/dns-json' }, timeout: 8000 },
      function (res) {
        let b = ''
        res.setEncoding('utf8')
        res.on('data', function (c) { b += c })
        res.on('end', function () {
          try {
            const j = JSON.parse(b)
            const ips = (j.Answer || []).filter(function (a) { return a.type === 1 }).map(function (a) { return a.data })
            if (ips.length) cb(null, ips)
            else cb(new Error('dns-shim: tidak ada A record untuk ' + name))
          } catch (e) { cb(new Error('dns-shim: DoH respons tidak valid: ' + e.message)) }
        })
      }
    )
    req.on('timeout', function () { req.destroy(new Error('dns-shim: DoH timeout')) })
    req.on('error', function (e) { cb(e) })
  }

  function makeLookup() {
    return function (host, opts, cb) {
      if (typeof opts === 'function') { cb = opts; opts = {} }
      opts = opts || {}
      const cb2 = typeof cb === 'function' ? cb : function () { }
      const key = String(host) + '|' + (opts.family || 0)
      const hit = cache.get(key)
      if (hit && Date.now() - hit.t < TTL) { cb2(null, hit.ip, hit.family); return }
      dohResolve4(host, function (err, ips) {
        if (err) return cb2(err)
        const ip = ips[0]
        cache.set(key, { ip: ip, family: 4, t: Date.now() })
        cb2(null, ip, 4)
      })
    }
  }

  const lookup = makeLookup()
  dns.lookup = lookup
  dns.promises.lookup = function (host, opts) {
    return new Promise(function (res, rej) {
      lookup(host, opts, function (e, a, f) { e ? rej(e) : res({ address: a, family: f }) })
    })
  }
  try {
    const line = '[dns-shim] aktif (resolv.conf=' + fs.existsSync('/etc/resolv.conf') + ', forced=' + forced + ')'
    process.stderr.write(line + '\n')
  } catch (e) { }
})()
