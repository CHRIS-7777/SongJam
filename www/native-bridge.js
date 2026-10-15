/* songjam native bridge — APK auto-starts server silently in bg on Stream.
   Web fallback: plain python server.py on same origin. */
(function () {
  const isNative = !!(window.Capacitor && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform());
  const hasBridge = !!(window.SongJamNative && window.SongJamNative.startServer);

  async function ensureServer() {
    // 1) Real APK: embedded NanoHTTPD ForegroundService, started SILENTLY.
    //    Android @JavascriptInterface is SYNC and returns a JSON string.
    if (window.SongJamNative && window.SongJamNative.startServer) {
      try {
        let r = window.SongJamNative.startServer(JSON.stringify({ port: 8080 }));
        if (r && typeof r.then === 'function') r = await r; // Capacitor plugin (async) compat
        if (typeof r === 'string') { try { r = JSON.parse(r); } catch (_) {} }
        // also accept {port} object form
        if (!r || !r.ip) { try { r = await window.SongJamNative.startServer({ port: 8080 }); } catch (_) {} }
        if (typeof r === 'string') { try { r = JSON.parse(r); } catch (_) {} }
        if (r && r.ip) {
          try {
            localStorage.setItem('sj_last_ip', r.ip + ':' + (r.port || 8080));
            localStorage.setItem('sj_last_pin', r.pin || '');
          } catch (_) {}
          // Host API lives on the embedded JamService at 127.0.0.1 — never same-origin
          // (Capacitor's own WebView server would return index.html for /api/*).
          const base = 'http://127.0.0.1:' + (r.port || 8080);
          return { base: base, ip: r.ip, port: r.port || 8080, pin: r.pin || '', native: true };
        }
      } catch (e) { /* fall through to web */ }
    }
    // 2) Capacitor without plugin (dev): server runs at 127.0.0.1:8080 in same WebView host
    if (isNative) {
      try {
        const ctl = await fetch('http://127.0.0.1:8080/api/info').then((x) => x.json());
        if (ctl && ctl.pin) return { base: 'http://127.0.0.1:8080', ip: ctl.ip, port: 8080, pin: ctl.pin, native: true };
      } catch (_) {}
    }
    // 3) Browser / python server.py
    return { base: '', native: false };
  }

  async function listNativeSongs() {
    if (window.SongJamNative && window.SongJamNative.listSongs) {
      try { return await window.SongJamNative.listSongs(); } catch (_) { return null; }
    }
    return null;
  }

  async function requestPermissions() {
    try {
      if (window.SongJamNative && window.SongJamNative.requestPermissions) {
        let r = window.SongJamNative.requestPermissions();
        if (r && typeof r.then === 'function') r = await r;
        if (typeof r === 'string') { try { r = JSON.parse(r); } catch (_) {} }
        return r;
      }
    } catch (_) {}
    return null;
  }

  window.SongJamBridge = { isNative, hasBridge, ensureServer, listNativeSongs, requestPermissions };
})();
