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
  const base = (mode === 'host' && nativeBase) ? nativeBase : (baseURL || '');
  const url = base + path;
  const r = await fetch(url, opts);
  if (!r.ok) throw new Error(r.status);
  return r.json();
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
    $('jamPin').textContent = info.pin;
    pin = info.pin;
    window.__SJ_PERM = (info.audioPerm !== false);
    $('livePill').classList.add('on');
    $('liveTxt').textContent = `${info.songs} songs`;
    if(mode === 'host' && !songs.length) renderSongs(songs);
  }catch(e){ $('jamAddr').textContent = 'server offline'; }
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
    $('songList').innerHTML = `<div class="empty"><div class="big">🎧</div>No songs on this device yet.<br/>Tap <b>🔴 Stream a song from my phone</b> and pick any audio file — it goes live for everyone.</div>`;
    return;
  }
  $('songList').innerHTML = '';
  list.forEach(s=>{
    const d = document.createElement('div');
    d.className = 'song' + (s.id===currentId ? ' paused' : '');
    d.innerHTML = `<div class="art">🎵</div>
      <div class="meta"><div class="t">${escapeHtml(s.name)}</div><div class="a">${escapeHtml(s.file)}</div></div>
      <div class="eq"><i></i><i></i><i></i><i></i></div>`;
    d.onclick = ()=> hostPlay(s.id, 0);
    $('songList').appendChild(d);
  });
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
  const streamURL = hbase + '/stream/' + String(id).split('/').map(encodeURIComponent).join('/');
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
  if(!v.startsWith('http')) v = 'http://' + v;
  baseURL = v.replace(/\/$/, '');
  try{
    const j = await api('/api/songs');
    songs = j.songs;
    renderRemote();
    await syncClock();
    $('livePill').classList.add('on');
    $('liveTxt').textContent = 'joined ✓';
    $('syncBadge').textContent = 'connected';
    $('syncBadge').className = 'badge sync';
  }catch(e){ alert('Cannot reach host. Check: same Hotspot WiFi + correct IP.'); }
};
$('leaveBtn').onclick = ()=>{
  baseURL=''; clearInterval(pollTimer);
  audio.pause(); $('syncBadge').textContent='idle'; $('syncBadge').className='badge off';
  show('home');
};
function renderRemote(){
  if(!songs.length){ $('remoteList').innerHTML = `<div class="empty">Host has no songs yet.</div>`; return; }
  $('remoteList').innerHTML = '';
  songs.forEach(s=>{
    const d=document.createElement('div');
    d.className='song'+(s.id===currentId?'':'');
    d.innerHTML=`<div class="art">🎵</div><div class="meta"><div class="t">${escapeHtml(s.name)}</div><div class="a">${escapeHtml(s.file)}</div></div>`;
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
function startJoinLoop(){
  clearInterval(pollTimer);
  pollTimer = setInterval(async ()=>{
    if(mode!=='join' || !baseURL) return;
    const t0 = Date.now()/1000;
    let st;
    try{ st = await api('/api/state'); }catch(e){ setBadge('offline','off'); return; }
    const t1 = Date.now()/1000;
    const rtt = t1 - t0;
    const hostPos = st.position + (rtt/2); // compensate half trip
    if(!st.songId){ setBadge('waiting…','off'); return; }

    if(st.songId !== currentId){
      currentId = st.songId;
      const known = songs.find(s=>s.id===st.songId);
      $('nowPlaying').textContent = '♪ ' + (known?known.name:st.songId);
      playLocal(st.songId, hostPos, st.playing);
      renderRemote();
    } else {
      const drift = hostPos - audio.currentTime;
      lastSyncMs = Math.abs(drift)*1000;
      if(st.playing && audio.paused) audio.play().catch(()=>{});
      if(!st.playing && !audio.paused) audio.pause();
      if(st.playing && Math.abs(drift) > 0.4){
        audio.currentTime = hostPos; // re-sync if >400ms off
      } else if(st.playing && Math.abs(drift) > 0.15){
        audio.playbackRate = drift > 0 ? 1.03 : 0.97; // gentle catch-up
      } else {
        audio.playbackRate = 1.0;
      }
    }
    if(!st.playing) setBadge('paused by host','off');
    else if(lastSyncMs < 150) setBadge(`synced ✓ ${Math.round(lastSyncMs)}ms`,'sync');
    else if(lastSyncMs < 400) setBadge(`syncing… ${Math.round(lastSyncMs)}ms`,'lag');
    else setBadge(`lag ${Math.round(lastSyncMs)}ms`,'lag');
    updatePlayIcon();
  }, 1000);
}
function setBadge(t,c){ $('syncBadge').textContent=t; $('syncBadge').className='badge '+c; }

// init — start on Home like a real APK, pre-load host data in background
show('home'); setMode('host');
ensureHostServer().then(()=>{ loadInfo(); loadSongs(); });
setInterval(loadInfo, 10000);
