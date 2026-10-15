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
          // same-origin when WebView loads via http://127.0.0.1:8080 (friends use hotspot IP),
          // otherwise explicit base for file:///android_asset / capacitor://localhost
          const probeBase = '';
          return { base: probeBase, ip: r.ip, port: r.port || 8080, pin: r.pin || '', native: true };
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

  window.SongJamBridge = { isNative, hasBridge, ensureServer, listNativeSongs };
})();
