/* SongJam client — host + join, local sync */
const $ = id => document.getElementById(id);
const audio = $('audio');
let mode = 'host';              // 'host' | 'join'
let songs = [];                 // [{id,name,file}]
let currentId = null;
let isHostPlaying = false;
let clockOffset = 0;            // serverTime - clientTime
let baseURL = '';               // '' = same origin (host), or 'http://ip:port' (join)
let pollTimer = null;
let lastSyncMs = 0;
let pin = '';
let nativeBase = ''; // APK: '' (same-origin via plugin) or 'http://127.0.0.1:8080'

async function ensureHostServer(){
  try{
    if(window.SongJamBridge && window.SongJamBridge.ensureServer){
      const s = await window.SongJamBridge.ensureServer();
      if(s){
        nativeBase = s.base || '';
        if(s.pin) pin = s.pin;
        if(s.ip && $('jamAddr')) $('jamAddr').textContent = `${s.ip}:${s.port || 8080}`;
        if(s.pin && $('jamPin')) $('jamPin').textContent = s.pin;
      }
    }
  }catch(_){}
}

const fmt = s => {
  if (!isFinite(s) || s < 0) return '0:00';
  s = Math.floor(s);
  return `${Math.floor(s/60)}:${String(s%60).padStart(2,'0')}`;
};

async function api(path, opts={}) {
  let base = baseURL || '';
  let q = '';
  const needsPin = path.startsWith('/api/songs') || path.startsWith('/api/state');
  if (mode === 'host' && nativeBase) {
    base = nativeBase;
    if (needsPin && pin) q = '?pin=' + encodeURIComponent(pin);
  } else if (mode === 'join' && baseURL) {
    base = baseURL;
    if (needsPin && pin) q = '?pin=' + encodeURIComponent(pin);
  }
  const url = base + path + q;
  const { timeoutMs, ...fetchOpts } = opts;
  let ctrl = null, t = null;
  try {
    if (timeoutMs) { ctrl = new AbortController(); t = setTimeout(() => ctrl.abort(), timeoutMs); }
    const r = await fetch(url, { ...fetchOpts, signal: ctrl ? ctrl.signal : undefined });
    if (!r.ok) throw new Error(String(r.status));
    return r.json();
  } finally { if (t) clearTimeout(t); }
}

// ---------- APK-style navigation: Home > Stream > Pick Song ----------
function show(screen){
  ['home','host','join'].forEach(s=>{ $('screen-'+s).style.display = s===screen ? '' : 'none'; });
  document.querySelectorAll('.bn').forEach(b=>b.classList.toggle('active', b.dataset.go===screen));
  if(screen==='host'){ setMode('host'); }
  if(screen==='join'){ setMode('join'); }
}
document.querySelectorAll('.bn').forEach(b=> b.onclick = ()=> show(b.dataset.go));
$('goHost').onclick = ()=> show('host');
$('goJoin').onclick = ()=> show('join');
$('streamBtn').onclick = async ()=>{
  // APK: silently start bg server on Stream tap, then list + pick
  await ensureHostServer();
  await loadInfo(); await loadSongs();
  $('shareFile').click();
};

function setMode(m){
  mode = m;
  if (m==='host'){ baseURL=''; ensureHostServer().then(()=>{ loadInfo(); loadSongs(); }); startHostLoop(); }
  else { startJoinLoop(); }
}

// ---------- load info / songs (host) ----------
async function loadInfo(){
  try{
    const info = await api('/api/info');
    $('jamAddr').textContent = `${info.ip}:${info.port}`;
    if(info.pin){ pin = info.pin; $('jamPin').textContent = info.pin; }
    window.__SJ_PERM = (info.audioPerm !== false);
    window.__SJ_IPS = info.ips || [];
    renderAltIps();
    $('livePill').classList.add('on');
    $('liveTxt').textContent = `${info.songs} songs`;
    if(mode === 'host' && !songs.length) renderSongs(songs);
  }catch(e){ $('jamAddr').textContent = 'server offline'; }
}
function renderAltIps(){
  const el = $('altIps');
  if(!el) return;
  const ips = window.__SJ_IPS || [];
  const primary = $('jamAddr') ? $('jamAddr').textContent : '';
  const others = ips.filter(x => x && x !== primary);
  if(others.length){
    el.style.display = '';
    el.innerHTML = 'Joiner stuck? Also try: ' + others.map(escapeHtml).join(' &nbsp;•&nbsp; ');
  } else { el.style.display = 'none'; el.innerHTML = ''; }
}
async function loadSongs(){
  try{
    const j = await api('/api/songs');
    songs = j.songs;
    renderSongs(songs);
    $('songCount').textContent = `• ${songs.length} found`;
    $('liveTxt').textContent = `${songs.length} songs`;
  }catch(e){
    $('songList').innerHTML = `<div class="empty"><div class="big">🎵</div>Starting jam server…<br/>If this stays, reopen the app and tap Stream again.</div>`;
  }
}
function renderSongs(list){
  if(!list.length){
    const needGrant = !!(window.SongJamBridge && window.SongJamBridge.isNative && window.__SJ_PERM === false);
    if(needGrant){
      $('songList').innerHTML = `<div class="empty"><div class="big">🔒</div>songjam needs access to your music to list songs.<br/><br/><button class="btn primary" id="grantBtn" style="max-width:260px">Allow music access</button><div class="hint">Tap Allow on the system popup, then your songs appear here.</div></div>`;
      const gb = $('grantBtn');
      if(gb) gb.onclick = async ()=>{
        gb.textContent = 'Waiting for permission…'; gb.disabled = true;
        try{ await window.SongJamBridge.requestPermissions(); }catch(e){}
        setTimeout(async ()=>{ await loadInfo(); await loadSongs(); }, 1200);
      };
      return;
    }
    $('songList').innerHTML = `<div class="empty"><div class="big">🎧</div>No songs here yet.<br/>Tap <b>🔴 Stream a song from my phone</b> to go live.</div>`;
    return;
  }
  $('songList').innerHTML = '';
  list.forEach(s=>{
    const d = document.createElement('div');
    d.className = 'song' + (s.id===currentId ? ' active' : '');
    d.innerHTML = rowHTML(s);
    d.onclick = ()=> hostPlay(s.id, 0);
    $('songList').appendChild(d);
  });
}
function fmtDur(ms){
  if(!ms || ms <= 0 || !isFinite(ms)) return '—';
  return fmt(ms/1000);
}
function rowHTML(s){
  return `<div class="art">🎵</div>
    <div class="meta"><div class="t">${escapeHtml(s.name)}</div></div>
    <div class="dur">${fmtDur(s.dur)}</div>
    <div class="eq"><i></i><i></i><i></i><i></i></div>`;
}
function escapeHtml(s){ return String(s).replace(/[&<>"']/g, c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }

$('search').oninput = e=>{
  const q = e.target.value.toLowerCase();
  renderSongs(songs.filter(s=>s.name.toLowerCase().includes(q)));
};

// ---------- host controls ----------
async function hostPlay(id, pos){
  currentId = id;
  isHostPlaying = true;
  playLocal(id, pos, true);
  await pushState();
  renderSongs(filtered());
}
function filtered(){
  const q = $('search').value.toLowerCase();
  return songs.filter(s=>s.name.toLowerCase().includes(q));
}
async function pushState(){
  try{
    await api('/api/state', {method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({songId: currentId, playing: isHostPlaying, position: audio.currentTime || 0, pin})});
  }catch(e){}
}
$('playBtn').onclick = async ()=>{
  if(mode==='join') return; // guests can't control
  if(!currentId && songs.length) return hostPlay(songs[0].id, 0);
  isHostPlaying = !isHostPlaying;
  if(isHostPlaying) audio.play().catch(()=>{});
  else audio.pause();
  updatePlayIcon();
  await pushState();
};
$('nextBtn').onclick = async ()=>{
  if(mode==='join' || !songs.length) return;
  const i = songs.findIndex(s=>s.id===currentId);
  const n = songs[(i+1+songs.length)%songs.length];
  hostPlay(n.id, 0);
};
$('prevBtn').onclick = async ()=>{
  if(mode==='join' || !songs.length) return;
  const i = songs.findIndex(s=>s.id===currentId);
  const p = songs[(i-1+songs.length)%songs.length];
  hostPlay(p.id, 0);
};
audio.onended = ()=> $('nextBtn').click();
audio.ontimeupdate = ()=>{
  if(audio.duration){
    $('bar').style.width = (audio.currentTime/audio.duration*100)+'%';
    $('tCur').textContent = fmt(audio.currentTime);
    $('tDur').textContent = fmt(audio.duration);
  }
};
$('vol').oninput = e=> audio.volume = e.target.value/100;
audio.volume = 0.9;

// Broadcast ANY local file — no need to pre-copy to Music/
const shareInput = $('shareFile');
if (shareInput) shareInput.onchange = async (e)=>{
  const f = e.target.files[0];
  if(!f) return;
  $('shareStatus').textContent = '⏫ Uploading ' + f.name + '…';
  try{
    const buf = await f.arrayBuffer();
    const hbase = (mode === 'host' && nativeBase) ? nativeBase : (baseURL || '');
    const r = await fetch(hbase + '/api/share?filename=' + encodeURIComponent(f.name) + '&pin=' + encodeURIComponent(pin), {
      method:'POST', body: buf
    });
    const j = await r.json();
    if(!j.ok) throw new Error(j.err||'upload failed');
    $('shareStatus').textContent = '✅ Live now: ' + f.name;
    await loadSongs();
    hostPlay(j.id, 0);
  }catch(err){ $('shareStatus').textContent = '❌ ' + err.message; }
  e.target.value = '';
};

function playLocal(id, pos=0, autoplay=true){
  // support subfolders: encode each part, keep slashes; host uses nativeBase in APK
  const hbase = (mode === 'host' && nativeBase) ? nativeBase : (baseURL || '');
  let streamURL = hbase + '/stream/' + String(id).split('/').map(encodeURIComponent).join('/');
  if(pin) streamURL += '?pin=' + encodeURIComponent(pin);
  if(audio.dataset.id !== id){
    audio.dataset.id = id;
    audio.src = streamURL;
  }
  audio.currentTime = pos || 0;
  const name = (songs.find(s=>s.id===id)||{name:id}).name;
  $('nowPlaying').textContent = '♪ ' + name;
  if(autoplay) audio.play().catch(()=>{});
  updatePlayIcon();
}
function updatePlayIcon(){
  const playing = mode==='host' ? isHostPlaying : !audio.paused;
  $('playBtn').textContent = playing ? '⏸' : '▶';
  document.querySelectorAll('.song').forEach(el=>el.classList.toggle('paused', !playing));
}

// host heartbeat: keep pushing position every 5s so joiners stay close
async function startHostLoop(){
  clearInterval(pollTimer);
  pollTimer = setInterval(async ()=>{
    if(mode!=='host' || !currentId) return;
    try{
      await api('/api/state', {method:'POST', headers:{'Content-Type':'application/json'},
        body: JSON.stringify({songId: currentId, playing: isHostPlaying, position: audio.currentTime||0, pin})});
    }catch(e){}
  }, 5000);
}

// ---------- join (listener) ----------
$('joinBtn').onclick = async ()=>{
  let v = $('hostAddr').value.trim();
  if(!v) return alert('Enter host address, e.g. 192.168.43.1:8080');
  const hp = $('hostPin') ? $('hostPin').value.trim() : '';
  if(!hp) return alert('Enter the Host PIN shown on the host phone.');
  pin = hp;
  currentId = null;
  let nv = v.replace(/^https?:\/\//i, '').replace(/\/+$/, '');
  if(!/:\d+$/.test(nv)) nv += ':8080'; // default jam port if omitted
  baseURL = 'http://' + nv;
  try{
    const j = await api('/api/songs', { timeoutMs: 8000 });
    songs = j.songs;
    renderRemote();
    await syncClock();
    $('livePill').classList.add('on');
    $('liveTxt').textContent = 'joined ✓';
    $('syncBadge').textContent = 'connected';
    $('syncBadge').className = 'badge sync';
  }catch(e){
    const msg = String((e && e.message) || e);
    if(e && e.name === 'AbortError') alert('No response from host (8s).\nCheck:\n1. Same Hotspot WiFi\n2. Exact address, incl :8080\n3. Host app open on Stream\n4. Turn OFF mobile data + VPN on this phone, retry');
    else if(msg.includes('403')) alert('Wrong PIN — check the 4 digits on the host phone and retry.');
    else if(msg.includes('404')) alert('Host reachable but jam not found. Update both phones to the latest songjam APK.');
    else alert('Cannot reach host (' + msg + ').\nCheck:\n1. Same Hotspot WiFi\n2. Try the "Also try" addresses on host screen\n3. Host app open, host hotspot ON\n4. Turn OFF mobile data + VPN on this phone');
  }
};
$('leaveBtn').onclick = ()=>{
  baseURL=''; clearInterval(pollTimer);
  audio.pause(); $('syncBadge').textContent='idle'; $('syncBadge').className='badge off';
  show('home');
};
function remoteFiltered(){
  const q = ($('rsearch') && $('rsearch').value || '').toLowerCase();
  if(!q) return songs;
  return songs.filter(s=>(s.name||'').toLowerCase().includes(q));
}
if($('rsearch')) $('rsearch').oninput = ()=> renderRemote();
function renderRemote(){
  if(!songs.length){ $('remoteList').innerHTML = `<div class="empty">No songs from host yet.</div>`; return; }
  const list = remoteFiltered();
  if(!list.length){ $('remoteList').innerHTML = `<div class="empty">No match.</div>`; return; }
  $('remoteList').innerHTML = '';
  list.forEach(s=>{
    const d=document.createElement('div');
    d.className='song'+(s.id===currentId?' active':'');
    d.innerHTML=rowHTML(s);
    $('remoteList').appendChild(d);
  });
}
async function syncClock(){
  // simple NTP: avg of 3 samples
  let offs=[];
  for(let i=0;i<3;i++){
    const t0=Date.now()/1000;
    try{
      const j=await api('/api/time');
      const t1=Date.now()/1000;
      const rtt=t1-t0;
      offs.push(j.t - (t0+rtt/2));
    }catch(e){}
  }
  if(offs.length) clockOffset = offs.sort()[1] ?? offs[0];
}
// ---------- join sync: WebSocket push + fallback polling ----------
// WebSocket push: host state changes arrive instantly, no polling delay.
// Falls back to spike-tolerant polling if WebSocket fails (old WebView, etc.).
const PREBUF_SEC = 3;
const SEEK_GATE = 1.0;
const GENTLE_GATE = 0.25;
const NEED_STRIKES = 2;
const JOIN_WAIT_MAX = 10; // polls (~10s) before forcing playback anyway
let ws = null;
let wsReconnectTimer = null;
let rttHist = [];
let driftSm = 0;
let corrStrikes = 0;
let joinReady = false;
let joinWait = 0;

function median(a){
  const s = [...a].sort((x,y)=>x-y);
  const m = Math.floor(s.length/2);
  return s.length % 2 ? s[m] : (s[m-1]+s[m])/2;
}
function bufferedAhead(){
  try{
    const b = audio.buffered, t = audio.currentTime || 0;
    for(let i=0;i<b.length;i++){
      if(t >= b.start(i)-0.25 && t <= b.end(i)) return b.end(i)-t;
    }
  }catch(e){}
  return 0;
}
function tryConnectWebSocket(url){
  if(ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
  try{
    ws = new WebSocket(url);
    ws.binaryType = 'text';
    ws.onopen = () => {
      console.log('WebSocket connected');
      setBadge('synced ✓','sync');
    };
    ws.onmessage = (evt) => {
      try{
        const st = JSON.parse(evt.data);
        handleState(st, 0); // WebSocket: no RTT to compensate
      }catch(e){}
    };
    ws.onclose = () => {
      console.log('WebSocket closed, reconnecting...');
      ws = null;
      clearTimeout(wsReconnectTimer);
      wsReconnectTimer = setTimeout(() => tryConnectWebSocket(url), 3000);
    };
    ws.onerror = (err) => {
      console.log('WebSocket error:', err);
    };
  }catch(e){
    console.log('WebSocket unavailable, using polling');
  }
}

function handleState(st, rtt){
  if(!st.songId){ setBadge('waiting…','off'); return; }

  const hostPos = st.position + (rtt/2); // for polling fallback only

  if(st.songId !== currentId){
    // new track: load (muted start), reset smoothing, wait for buffer
    currentId = st.songId;
    driftSm = 0; corrStrikes = 0; joinReady = false; joinWait = 0;
    const known = songs.find(s=>s.id===st.songId);
    $('nowPlaying').textContent = '♪ ' + (known?known.name:st.songId);
    playLocal(st.songId, hostPos, false);
    renderRemote();
  } else {
    if(!joinReady){
      joinWait++;
      if(bufferedAhead() >= PREBUF_SEC || audio.readyState >= 4 || joinWait > JOIN_WAIT_MAX){
        joinReady = true;
        if(st.playing) audio.play().catch(()=>{});
      } else {
        setBadge('buffering…','lag');
        updatePlayIcon();
        return;
      }
    }
    const rawDrift = hostPos - (audio.currentTime || 0);
    driftSm = driftSm*0.6 + rawDrift*0.4;
    lastSyncMs = Math.abs(driftSm)*1000;
    if(joinReady && st.playing && audio.paused) audio.play().catch(()=>{});
    if(!st.playing && !audio.paused) audio.pause();
    const ad = Math.abs(driftSm);
    if(!st.playing || ad < GENTLE_GATE){
      corrStrikes = 0;
      audio.playbackRate = 1.0;
    } else if(ad > SEEK_GATE){
      corrStrikes++;
      if(corrStrikes >= NEED_STRIKES || Math.abs(rawDrift) > 2.5){
        audio.currentTime = hostPos; // true jump only: >1s off, twice in a row
        driftSm = 0; corrStrikes = 0;
      }
    } else {
      corrStrikes++;
      if(corrStrikes >= NEED_STRIKES) audio.playbackRate = driftSm > 0 ? 1.01 : 0.99;
    }
    if(!st.playing) setBadge('paused by host','off');
    else if(lastSyncMs < 250) setBadge(`synced ✓ ${Math.round(lastSyncMs)}ms`,'sync');
    else if(lastSyncMs < 1000) setBadge(`syncing… ${Math.round(lastSyncMs)}ms`,'lag');
    else setBadge(`lag ${Math.round(lastSyncMs)}ms`,'lag');
    updatePlayIcon();
}

// Helper: median of RTT history (for polling fallback)
function median(a){
  const s = [...a].sort((x,y)=>x-y);
  const m = Math.floor(s.length/2);
  return s.length % 2 ? s[m] : (s[m-1]+s[m])/2;
}
function bufferedAhead(){
  try{
    const b = audio.buffered, t = audio.currentTime || 0;
    for(let i=0;i<b.length;i++){
      if(t >= b.start(i)-0.25 && t <= b.end(i)) return b.end(i)-t;
    }
  }catch(e){}
  return 0;
}

function startJoinLoop(){
  clearInterval(pollTimer);
  if(mode!=='join' || !baseURL) return;

  // Try WebSocket first
  const wsUrl = baseURL.replace(/^http/, 'ws') + '/ws/state';
  tryConnectWebSocket(wsUrl);

  // Fallback polling (spike-tolerant) if WebSocket fails
  pollTimer = setInterval(async ()=>{
    if(mode!=='join' || !baseURL) return;
    if(ws && ws.readyState === WebSocket.OPEN) return; // WebSocket active, skip polling
    const t0 = Date.now()/1000;
    let st;
    try{ st = await api('/api/state', { timeoutMs: 8000 }); }catch(e){ setBadge('offline','off'); return; }
    const t1 = Date.now()/1000;
    rttHist.push(t1-t0);
    if(rttHist.length > 5) rttHist.shift();
    handleState(st, t1 - t0);
  }, 1000);
}

function tryConnectWebSocket(url){
  if(ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;
  try{
    ws = new WebSocket(url);
    ws.binaryType = 'text';
    ws.onopen = () => {
      console.log('WebSocket connected');
      setBadge('synced ✓','sync');
    };
    ws.onmessage = (evt) => {
      try{
        const st = JSON.parse(evt.data);
        handleState(st, 0); // WebSocket: no RTT to compensate
      }catch(e){}
    };
    ws.onclose = () => {
      console.log('WebSocket closed, reconnecting...');
      ws = null;
      clearTimeout(wsReconnectTimer);
      wsReconnectTimer = setTimeout(() => tryConnectWebSocket(url), 3000);
    };
    ws.onerror = (err) => {
      console.log('WebSocket error:', err);
    };
  }catch(e){
    console.log('WebSocket unavailable, using polling');
  }
}

function handleState(st, rtt){
  if(!st.songId){ setBadge('waiting…','off'); return; }

  const hostPos = st.position + (rtt/2); // for polling fallback only

  if(st.songId !== currentId){
    // new track: load (muted start), reset smoothing, wait for buffer
    currentId = st.songId;
    driftSm = 0; corrStrikes = 0; joinReady = false; joinWait = 0;
    const known = songs.find(s=>s.id===st.songId);
    $('nowPlaying').textContent = '♪ ' + (known?known.name:st.songId);
    playLocal(st.songId, hostPos, false);
    renderRemote();
  } else {
    if(!joinReady){
      joinWait++;
      if(bufferedAhead() >= PREBUF_SEC || audio.readyState >= 4 || joinWait > JOIN_WAIT_MAX){
        joinReady = true;
        if(st.playing) audio.play().catch(()=>{});
      } else {
        setBadge('buffering…','lag');
        updatePlayIcon();
        return;
      }
    }
    const rawDrift = hostPos - (audio.currentTime || 0);
    driftSm = driftSm*0.6 + rawDrift*0.4;
    lastSyncMs = Math.abs(driftSm)*1000;
    if(joinReady && st.playing && audio.paused) audio.play().catch(()=>{});
    if(!st.playing && !audio.paused) audio.pause();
    const ad = Math.abs(driftSm);
    if(!st.playing || ad < GENTLE_GATE){
      corrStrikes = 0;
      audio.playbackRate = 1.0;
    } else if(ad > SEEK_GATE){
      corrStrikes++;
      if(corrStrikes >= NEED_STRIKES || Math.abs(rawDrift) > 2.5){
        audio.currentTime = hostPos; // true jump only: >1s off, twice in a row
        driftSm = 0; corrStrikes = 0;
      }
    } else {
      corrStrikes++;
      if(corrStrikes >= NEED_STRIKES) audio.playbackRate = driftSm > 0 ? 1.01 : 0.99;
    }
    if(!st.playing) setBadge('paused by host','off');
    else if(lastSyncMs < 250) setBadge(`synced ✓ ${Math.round(lastSyncMs)}ms`,'sync');
    else if(lastSyncMs < 1000) setBadge(`syncing… ${Math.round(lastSyncMs)}ms`,'lag');
    else setBadge(`lag ${Math.round(lastSyncMs)}ms`,'lag');
    updatePlayIcon();
}

// Helper: median of RTT history (for polling fallback)
function median(a){
  const s = [...a].sort((x,y)=>x-y);
  const m = Math.floor(s.length/2);
  return s.length % 2 ? s[m] : (s[m-1]+s[m])/2;
}
function bufferedAhead(){
  try{
    const b = audio.buffered, t = audio.currentTime || 0;
    for(let i=0;i<b.length;i++){
      if(t >= b.start(i)-0.25 && t <= b.end(i)) return b.end(i)-t;
    }
  }catch(e){}
  return 0;
}

function setBadge(t,c){ $('syncBadge').textContent=t; $('syncBadge').className='badge '+c; }

// init — start on Home like a real APK, pre-load host data in background
show('home'); setMode('host');
ensureHostServer().then(()=>{ loadInfo(); loadSongs(); });
setInterval(loadInfo, 10000);
}
}