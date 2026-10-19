package com.chris7777.songjam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import fi.iki.elonen.NanoHTTPD;

/**
 * songjam bg server — started SILENTLY when user taps Stream.
 * Mirrors server.py: GET /api/info|songs|time|state, POST /api/state(pin),
 * POST /api/share(pin), GET /stream/<id> with Range, static www/ fallback.
 * Songs come from MediaStore (no Music/ folder needed on phone).
 */
public class JamService extends Service {
    public static final String CH = "songjam";
    public static final int PORT_DEFAULT = 8080;
    public static int PORT = PORT_DEFAULT;
    public static String PIN = null;
    public static JamHttpd HTTPD = null;
    static JamService INST = null;

    // jam state (same as server.py)
    public static String songId = null;
    public static boolean playing = false;
    public static double basePosition = 0.0;
    public static long updatedAt = System.currentTimeMillis();

    public static synchronized double currentPosition() {
        if (playing && songId != null) {
            return basePosition + (System.currentTimeMillis() - updatedAt) / 1000.0;
        }
        return basePosition;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        INST = this;
        SharedPreferences sp = getSharedPreferences("songjam", MODE_PRIVATE);
        PIN = sp.getString("pin", null);
        if (PIN == null) {
            PIN = String.format("%04d", 1000 + new Random().nextInt(9000));
            sp.edit().putString("pin", PIN).apply();
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CH, "songjam", NotificationManager.IMPORTANCE_MIN);
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        Notification n = new NotificationCompat.Builder(this, CH)
                .setContentTitle("songjam")
                .setContentText("Jam server running in background")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true).build();
        startForeground(1, n);
        startHttpd(PORT_DEFAULT);
    }

    public static synchronized void startHttpd(int port) {
        PORT = port;
        if (HTTPD != null || INST == null) return;
        try {
            HTTPD = new JamHttpd(INST, port);
            HTTPD.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        } catch (Exception e) { HTTPD = null; }
    }

    @Override
    public int onStartCommand(Intent i, int f, int id) {
        if (i != null && i.hasExtra("port")) startHttpd(i.getIntExtra("port", PORT_DEFAULT));
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    public static String hotspotIp() {
        List<String> ips = hotspotIps();
        return ips.isEmpty() ? "192.168.43.1" : ips.get(0);
    }

    /** All site-local IPv4s, hotspot subnets first — host shows every candidate. */
    public static List<String> hotspotIps() {
        List<String> c43 = new ArrayList<>(), c192 = new ArrayList<>(),
                c172 = new ArrayList<>(), c10 = new ArrayList<>(), other = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            for (NetworkInterface ni : Collections.list(nis)) {
                try { if (!ni.isUp() || ni.isLoopback()) continue; } catch (Exception ignored) { continue; }
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ip == null) continue;
                    if (ip.startsWith("192.168.43.") || ip.startsWith("192.168.49.")) { if (!c43.contains(ip)) c43.add(ip); }
                    else if (ip.startsWith("192.168.")) { if (!c192.contains(ip)) c192.add(ip); }
                    else if (ip.startsWith("172.")) { if (!c172.contains(ip)) c172.add(ip); }
                    else if (ip.startsWith("10.")) { if (!c10.contains(ip)) c10.add(ip); }
                    else if (!a.isLinkLocalAddress()) { if (!other.contains(ip)) other.add(ip); }
                }
            }
        } catch (Exception ignored) {}
        List<String> all = new ArrayList<>();
        all.addAll(c43); all.addAll(c192); all.addAll(c172); all.addAll(c10); all.addAll(other);
        return all;
    }

    static class Song { String id, name, file; long size, dur; Song(String i,String n,String f,long s,long d){id=i;name=n;file=f;size=s;dur=d;} }

    public List<Song> querySongs() {
        List<Song> out = new ArrayList<>();
        ContentResolver cr = getContentResolver();
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {"_id", "_display_name", "_size", MediaStore.Audio.Media.DURATION};
        String sel = MediaStore.Audio.Media.IS_MUSIC + "!=0";
        try (Cursor c = cr.query(uri, proj, sel, null, "_display_name ASC")) {
            if (c != null) while (c.moveToNext()) {
                long id = c.getLong(0);
                String dn = c.getString(1);
                long sz = c.getLong(2);
                long du = 0;
                try { du = c.getLong(3); } catch (Exception ignored) {}
                if (dn == null) dn = "track-" + id;
                String stem = dn.contains(".") ? dn.substring(0, dn.lastIndexOf('.')) : dn;
                out.add(new Song(String.valueOf(id), stem, dn, sz, du));
            }
        } catch (Exception ignored) {}
        // uploads via POST /api/share (streamed by id "shared/<name>")
        try {
            File dir = new File(getFilesDir(), "shared");
            File[] fs = dir.listFiles();
            if (fs != null) {
                java.util.Arrays.sort(fs);
                for (File f : fs) {
                    if (!f.isFile()) continue;
                    String dn = f.getName();
                    String stem = dn.contains(".") ? dn.substring(0, dn.lastIndexOf('.')) : dn;
                    out.add(new Song("shared/" + dn, stem, dn, f.length(), 0));
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    boolean hasAudioPerm() {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED;
            }
            return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) { return false; }
    }

    static Map<String, String> queryParams(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null) return m;
        for (String kv : q.split("&")) {
            if (kv.isEmpty()) continue;
            int i = kv.indexOf('=');
            try {
                if (i > 0) m.put(java.net.URLDecoder.decode(kv.substring(0, i), "UTF-8"),
                        java.net.URLDecoder.decode(kv.substring(i + 1), "UTF-8"));
                else m.put(java.net.URLDecoder.decode(kv, "UTF-8"), "");
            } catch (Exception ignored) {}
        }
        return m;
    }

    static byte[] readBody(InputStream in, long len) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long left = len;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) break;
            bos.write(buf, 0, n);
            left -= n;
        }
        return bos.toByteArray();
    }

    static class JamHttpd extends NanoHTTPD {
        final JamService svc;
        JamHttpd(JamService svc, int port) { super(port); this.svc = svc; }

        // WebSocket clients for real-time state push
        private final CopyOnWriteArrayList<WebSocketClient> wsClients = new CopyOnWriteArrayList<>();

        static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

        static class WebSocketClient {
            final java.net.Socket socket;
            final OutputStream out;
            final InputStream in;
            boolean open = true;
            WebSocketClient(java.net.Socket s) throws IOException {
                this.socket = s;
                this.out = s.getOutputStream();
                this.in = s.getInputStream();
            }
            void send(String text) {
                if (!open) return;
                try {
                    byte[] payload = text.getBytes("UTF-8");
                    // WebSocket frame: FIN=1, opcode=1 (text), mask=0
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    bos.write(0x81); // FIN + text frame
                    if (payload.length < 126) {
                        bos.write(payload.length);
                    } else if (payload.length < 65536) {
                        bos.write(126);
                        bos.write((payload.length >> 8) & 0xFF);
                        bos.write(payload.length & 0xFF);
                    } else {
                        bos.write(127);
                        for (int i = 7; i >= 0; i--) bos.write((payload.length >> (i * 8)) & 0xFF);
                    }
                    bos.write(payload);
                    out.write(bos.toByteArray());
                    out.flush();
                } catch (IOException e) {
                    open = false;
                }
            }
            void close() {
                open = false;
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
        @Override public Response serve(IHTTPSession s) {
            String uri = s.getUri();
            Method m = s.getMethod();
            try {
                // WebSocket upgrade check
                if (uri.equals("/ws/state") && m == Method.GET) {
                    if (handleWebSocketUpgrade(s)) {
                        // Status 101 = Switching Protocols (not in NanoHTTPD Response.Status)
                        Response r = newFixedLengthResponse(Response.Status.OK, "text/plain", "");
                        r.setStatus(" 101 Switching Protocols");
                        return r;
                    }
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "WebSocket upgrade required");
                }
                // CORS preflight: host WebView POSTs JSON cross-origin (localhost -> LAN IP),
                // so every state/share POST is preceded by OPTIONS. Must answer 200 + allow headers.
                if (m == Method.OPTIONS) {
                    return cors(newFixedLengthResponse(Response.Status.OK, "text/plain", "ok"));
                }
                if (uri.equals("/api/time")) return json(new JSONObject().put("t", System.currentTimeMillis() / 1000.0));
                if (uri.equals("/api/info")) {
                    JSONObject o = new JSONObject();
                    o.put("ip", hotspotIp()); o.put("port", PORT);
                    o.put("songs", svc.querySongs().size());
                    o.put("audioPerm", svc.hasAudioPerm());
                    JSONArray iparr = new JSONArray();
                    for (String x : hotspotIps()) iparr.put(x + ":" + PORT);
                    o.put("ips", iparr);
                    return json(o);
                }
                if (uri.equals("/api/songs")) {
                    if (!authed(s)) return jsonErr("wrong pin", 403);
                    JSONArray arr = new JSONArray();
                    for (Song sg : svc.querySongs()) {
                        JSONObject o = new JSONObject();
                        o.put("id", sg.id); o.put("name", sg.name); o.put("file", sg.file); o.put("size", sg.size); o.put("dur", sg.dur);
                        arr.put(o);
                    }
                    return json(new JSONObject().put("songs", arr));
                }
                if (uri.equals("/api/state") && m == Method.GET) {
                    if (!authed(s)) return jsonErr("wrong pin", 403);
                    JSONObject o = new JSONObject();
                    o.put("songId", songId == null ? JSONObject.NULL : songId);
                    o.put("playing", playing);
                    o.put("position", Math.round(currentPosition() * 1000.0) / 1000.0);
                    o.put("serverTime", System.currentTimeMillis() / 1000.0);
                    return json(o);
                }
                if (uri.equals("/api/state") && m == Method.POST) {
                    Map<String, String> files = new HashMap<>();
                    s.parseBody(files);
                    String body = files.get("postData");
                    JSONObject b = new JSONObject(body == null ? "{}" : body);
                    if (!PIN.equals(String.valueOf(b.optString("pin", "")))) return jsonErr("wrong pin", 403);
                    synchronized (JamService.class) {
                        String nid = b.isNull("songId") ? null : b.optString("songId", null);
                        if (nid != null && !nid.equals(songId) && !b.has("position")) basePosition = 0.0;
                        else if (b.has("position") && !b.isNull("position")) basePosition = b.optDouble("position", currentPosition());
                        else basePosition = currentPosition();
                        if (b.has("songId")) songId = b.isNull("songId") ? null : b.optString("songId");
                        if (b.has("playing")) playing = b.optBoolean("playing", false);
                        updatedAt = System.currentTimeMillis();
                    }
                    // Broadcast state change to all WebSocket clients
                    broadcastState();
                    return json(new JSONObject().put("ok", true));
                }
                if (uri.equals("/api/share") && (m == Method.POST || m == Method.PUT)) {
                    Map<String, String> qp = queryParams(s.getQueryParameterString());
                    if (!PIN.equals(qp.get("pin"))) return jsonErr("wrong pin", 403);
                    String fname = qp.get("filename");
                    if (fname == null || fname.isEmpty()) fname = "shared-track.mp3";
                    fname = new File(fname).getName();
                    if (fname.isEmpty()) fname = "shared-track.mp3";
                    long cl = 0;
                    try { cl = Long.parseLong(s.getHeaders().get("content-length")); } catch (Exception ignored) {}
                    if (cl <= 0 || cl > 100 * 1024 * 1024) return jsonErr("empty or >100MB", 400);
                    byte[] data = readBody(s.getInputStream(), cl);
                    if (data.length == 0) return jsonErr("empty upload", 400);
                    File dir = new File(svc.getFilesDir(), "shared");
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                    File dest = new File(dir, fname);
                    if (dest.exists()) dest = new File(dir, System.currentTimeMillis() + "-" + fname);
                    try {
                        OutputStream out = new java.io.FileOutputStream(dest);
                        out.write(data);
                        out.close();
                    } catch (Exception e) { return jsonErr("write failed", 500); }
                    return json(new JSONObject().put("ok", true).put("id", "shared/" + dest.getName()));
                }
                if (uri.startsWith("/stream/") && m == Method.GET) {
                    if (!authed(s)) return cors(newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "wrong pin"));
                    String id = uri.substring("/stream/".length());
                    return serveTrack(id, s.getHeaders().get("range"));
                }
                if (uri.startsWith("/api/")) return jsonErr("not found", 404);
                return serveAsset(uri);
            } catch (Exception e) {
                return cors(newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "err"));
            }
        }
        Response cors(Response r) {
            r.addHeader("Access-Control-Allow-Origin", "*");
            r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            r.addHeader("Access-Control-Allow-Headers", "Content-Type, X-Jam-Pin, Range");
            return r;
        }
        Response json(JSONObject o) {
            Response r = newFixedLengthResponse(Response.Status.OK, "application/json", o.toString());
            return cors(r);
        }
        Response jsonErr(String e, int code) {
            Response.Status st = code == 403 ? Response.Status.FORBIDDEN : Response.Status.BAD_REQUEST;
            Response r = newFixedLengthResponse(st, "application/json", "{\"ok\":false,\"err\":\"" + e + "\"}");
            return cors(r);
        }
        /** PIN gate for joiners: ?pin= or X-Jam-Pin header. Host UI gets PIN via native bridge. */
        boolean authed(IHTTPSession s) {
            try {
                String got = queryParams(s.getQueryParameterString()).get("pin");
                if (got == null) got = s.getHeaders().get("x-jam-pin");
                return PIN != null && PIN.equals(got);
            } catch (Exception e) { return false; }
        }

        // WebSocket upgrade handling
        private boolean handleWebSocketUpgrade(IHTTPSession s) {
            String upgrade = s.getHeaders().get("upgrade");
            String connection = s.getHeaders().get("connection");
            String wsKey = s.getHeaders().get("sec-websocket-key");
            String wsVersion = s.getHeaders().get("sec-websocket-version");

            if (upgrade == null || !upgrade.equalsIgnoreCase("websocket") ||
                wsKey == null || wsVersion == null || !wsVersion.equals("13")) {
                return false;
            }

            try {
                String acceptKey = computeWebSocketAccept(wsKey);
                
                // Get socket via reflection from NanoHTTPD session
                java.net.Socket socket = null;
                try {
                    // Try to get socket from session via reflection
                    java.lang.reflect.Field f = s.getClass().getDeclaredField("socket");
                    f.setAccessible(true);
                    socket = (java.net.Socket) f.get(s);
                } catch (Exception ignored) {
                    // Fallback: try common field names
                    for (String fieldName : new String[]{"socket", "mSocket", "clientSocket"}) {
                        try {
                            java.lang.reflect.Field f = s.getClass().getDeclaredField(fieldName);
                            f.setAccessible(true);
                            socket = (java.net.Socket) f.get(s);
                            if (socket != null) break;
                        } catch (Exception ignored2) {}
                    }
                }
                if (socket == null) {
                    // Last resort: try to get from HTTPD instance
                    try {
                        java.lang.reflect.Method getSocketMethod = s.getClass().getMethod("getSocket");
                        socket = (java.net.Socket) getSocketMethod.invoke(s);
                    } catch (Exception ignored) {}
                }
                if (socket == null) return false;

                // Send 101 Switching Protocols
                String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";
                socket.getOutputStream().write(response.getBytes("UTF-8"));
                socket.getOutputStream().flush();

                // Create WebSocket client and start reading frames
                WebSocketClient client = new WebSocketClient(socket);
                wsClients.add(client);
                new Thread(() -> readWebSocketFrames(client)).start();
                return true;
            } catch (Exception e) {
                Log.e("JamService", "WebSocket upgrade failed", e);
                return false;
            }
        }

        private String computeWebSocketAccept(String key) {
            try {
                String combined = key + WS_GUID;
                MessageDigest md = MessageDigest.getInstance("SHA-1");
                byte[] hash = md.digest(combined.getBytes("UTF-8"));
                return Base64.encodeToString(hash, Base64.NO_WRAP);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }

        private void readWebSocketFrames(WebSocketClient client) {
            try {
                while (client.open) {
                    // Read frame header (2 bytes minimum)
                    int b1 = client.in.read();
                    if (b1 == -1) break;
                    int b2 = client.in.read();
                    if (b2 == -1) break;

                    boolean fin = (b1 & 0x80) != 0;
                    int opcode = b1 & 0x0F;
                    boolean masked = (b2 & 0x80) != 0;
                    int payloadLen = b2 & 0x7F;

                    if (payloadLen == 126) {
                        payloadLen = (client.in.read() << 8) | client.in.read();
                    } else if (payloadLen == 127) {
                        // 64-bit length - skip 8 bytes
                        for (int i = 0; i < 8; i++) client.in.read();
                        payloadLen = 0; // We don't handle frames > 65535
                    }

                    byte[] mask = new byte[4];
                    if (masked) client.in.read(mask);

                    byte[] payload = new byte[payloadLen];
                    int read = 0;
                    while (read < payloadLen) {
                        int n = client.in.read(payload, read, payloadLen - read);
                        if (n <= 0) break;
                        read += n;
                    }
                    if (read != payloadLen) break;

                    if (masked) {
                        for (int i = 0; i < payloadLen; i++) {
                            payload[i] ^= mask[i % 4];
                        }
                    }

                    if (opcode == 0x8) { // Close frame
                        client.close();
                        break;
                    } else if (opcode == 0x9) { // Ping
                        sendPong(client);
                    } else if (opcode == 0x1 || opcode == 0x2) { // Text or binary
                        // Client message - we can ignore for now
                    }
                }
            } catch (IOException ignored) {
            } finally {
                client.close();
                wsClients.remove(client);
            }
        }

        private void sendPong(WebSocketClient client) {
            try {
                client.out.write(new byte[]{(byte) 0x8A, 0}); // Pong frame
                client.out.flush();
            } catch (IOException ignored) {}
        }

        // Broadcast state to all WebSocket clients
        void broadcastState() {
            if (wsClients.isEmpty()) return;
            try {
                JSONObject o = new JSONObject();
                o.put("songId", songId == null ? JSONObject.NULL : songId);
                o.put("playing", playing);
                o.put("position", Math.round(currentPosition() * 1000.0) / 1000.0);
                o.put("serverTime", System.currentTimeMillis() / 1000.0);
                String msg = o.toString();

                for (WebSocketClient c : wsClients) {
                    if (c.open) c.send(msg);
                    else wsClients.remove(c);
                }
            } catch (Exception ignored) {}
        }
        static String mimeFor(String name) {
            String n = name.toLowerCase();
            if (n.endsWith(".m4a")) return "audio/mp4";
            if (n.endsWith(".opus") || n.endsWith(".ogg")) return "audio/ogg";
            if (n.endsWith(".wav")) return "audio/wav";
            if (n.endsWith(".flac")) return "audio/flac";
            return "audio/mpeg";
        }
        Response serveTrack(String id, String range) {            try {
                InputStream in;
                long size;
                String mime;
                if (id.startsWith("shared/")) {
                    File f = new File(new File(svc.getFilesDir(), "shared"),
                            new File(id.substring("shared/".length())).getName());
                    if (!f.isFile()) return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found");
                    size = f.length();
                    mime = mimeFor(f.getName());
                    in = new java.io.FileInputStream(f);
                } else {
                    Uri cu = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Long.parseLong(id));
                    ParcelFileDescriptor pfd = svc.getContentResolver().openFileDescriptor(cu, "r");
                    size = pfd.getStatSize();
                    mime = svc.getContentResolver().getType(cu);
                    if (mime == null) mime = "audio/mpeg";
                    in = new java.io.FileInputStream(pfd.getFileDescriptor());
                }
                long start = 0, end = size - 1;
                if (range != null && range.startsWith("bytes=")) {
                    String[] parts = range.substring(6).split("-", 2);
                    try { if (!parts[0].isEmpty()) start = Long.parseLong(parts[0]); } catch (Exception ignored) {}
                    try { if (parts.length > 1 && !parts[1].isEmpty()) end = Math.min(Long.parseLong(parts[1]), size - 1); } catch (Exception ignored) {}
                }
                long len = end - start + 1;
                //noinspection ResultOfMethodCallIgnored
                in.skip(start);
                InputStream capped = new CappedStream(in, len);
                Response r = newFixedLengthResponse(start == 0 && len == size ? Response.Status.OK : Response.Status.PARTIAL_CONTENT, mime, capped, len);
                r.addHeader("Accept-Ranges", "bytes");
                if (r.getStatus() == Response.Status.PARTIAL_CONTENT) r.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
                return cors(r);
            } catch (FileNotFoundException fnf) {
                return cors(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found"));
            } catch (Exception e) {
                return cors(newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found"));
            }
        }
        Response serveAsset(String uri) {
            try {
                AssetManager am = svc.getAssets();
                String p = uri.equals("/") || uri.isEmpty() ? "public/index.html" : "public" + uri;
                // try www/ then public/ (capacitor copies webDir to both on some versions)
                for (String cand : new String[]{p, uri.equals("/") ? "www/index.html" : "www" + uri}) {
                    try {
                        InputStream in = am.open(cand);
                        String mime = "text/html";
                        if (cand.endsWith(".css")) mime = "text/css";
                        else if (cand.endsWith(".js")) mime = "application/javascript";
                        else if (cand.endsWith(".json")) mime = "application/json";
                        else if (cand.endsWith(".svg")) mime = "image/svg+xml";
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192]; int n;
                        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                        in.close();
                        return cors(newFixedLengthResponse(Response.Status.OK, mime, new java.io.ByteArrayInputStream(bos.toByteArray()), bos.size()));
                    } catch (Exception ignored) {}
                }
                InputStream in = am.open("public/index.html");
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                return cors(newFixedLengthResponse(Response.Status.OK, "text/html", new java.io.ByteArrayInputStream(bos.toByteArray()), bos.size()));
            } catch (Exception e) {
                return cors(newFixedLengthResponse(Response.Status.OK, "text/html", "<h1>songjam running — open Host page</h1>"));
            }
        }
    }

    static class CappedStream extends InputStream {
        final InputStream in; long left;
        CappedStream(InputStream i, long l) { in = i; left = l; }
        @Override public int read() throws java.io.IOException { if (left <= 0) return -1; int b = in.read(); if (b >= 0) left--; return b; }
        @Override public int read(byte[] b, int o, int l) throws java.io.IOException {
            if (left <= 0) return -1; int n = in.read(b, o, (int) Math.min(l, left)); if (n > 0) left -= n; return n;
        }
        @Override public void close() throws java.io.IOException { in.close(); }
    }
}
