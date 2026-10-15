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

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
        if (HTTPD != null) return;
        try {
            HTTPD = new JamHttpd(port);
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
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            for (NetworkInterface ni : Collections.list(nis)) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        if (ip.startsWith("192.168.43.") || ip.startsWith("192.168.49.")) return ip;
                        if (ip.startsWith("192.168.")) fallback = ip;
                    }
                }
            }
            return fallback != null ? fallback : "192.168.43.1";
        } catch (Exception e) { return "192.168.43.1"; }
    }

    static class Song { String id, name, file; long size; Song(String i,String n,String f,long s){id=i;name=n;file=f;size=s;} }

    public List<Song> querySongs() {
        List<Song> out = new ArrayList<>();
        ContentResolver cr = getContentResolver();
        Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String[] proj = {"_id", "_display_name", "_size"};
        String sel = MediaStore.Audio.Media.IS_MUSIC + "!=0";
        try (Cursor c = cr.query(uri, proj, sel, null, "_display_name ASC")) {
            if (c != null) while (c.moveToNext()) {
                long id = c.getLong(0);
                String dn = c.getString(1);
                long sz = c.getLong(2);
                if (dn == null) dn = "track-" + id;
                String stem = dn.contains(".") ? dn.substring(0, dn.lastIndexOf('.')) : dn;
                out.add(new Song(String.valueOf(id), stem, dn, sz));
            }
        } catch (Exception ignored) {}
        return out;
    }

    class JamHttpd extends NanoHTTPD {
        JamHttpd(int port) { super(port); }
        @Override public Response serve(IHTTPSession s) {
            String uri = s.getUri();
            Method m = s.getMethod();
            try {
                if (uri.equals("/api/time")) return json(new JSONObject().put("t", System.currentTimeMillis() / 1000.0));
                if (uri.equals("/api/info")) {
                    JSONObject o = new JSONObject();
                    o.put("ip", hotspotIp()); o.put("port", PORT); o.put("pin", PIN);
                    o.put("songs", querySongs().size());
                    return json(o);
                }
                if (uri.equals("/api/songs")) {
                    JSONArray arr = new JSONArray();
                    for (Song sg : querySongs()) {
                        JSONObject o = new JSONObject();
                        o.put("id", sg.id); o.put("name", sg.name); o.put("file", sg.file); o.put("size", sg.size);
                        arr.put(o);
                    }
                    return json(new JSONObject().put("songs", arr));
                }
                if (uri.equals("/api/state") && m == Method.GET) {
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
                    if (!PIN.equals(String.valueOf(b.opt("pin", "")))) return jsonErr("wrong pin", 403);
                    synchronized (JamService.class) {
                        String nid = b.isNull("songId") ? null : b.optString("songId", null);
                        if (nid != null && !nid.equals(songId) && !b.has("position")) basePosition = 0.0;
                        else if (b.has("position") && !b.isNull("position")) basePosition = b.optDouble("position", currentPosition());
                        else basePosition = currentPosition();
                        if (b.has("songId")) songId = b.isNull("songId") ? null : b.optString("songId");
                        if (b.has("playing")) playing = b.optBoolean("playing", false);
                        updatedAt = System.currentTimeMillis();
                    }
                    return json(new JSONObject().put("ok", true));
                }
                if (uri.startsWith("/stream/") && m == Method.GET) {
                    String id = uri.substring("/stream/".length());
                    return serveTrack(id, s.getHeaders().get("range"));
                }
                return serveAsset(uri);
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "err");
            }
        }
        Response json(JSONObject o) {
            Response r = newFixedLengthResponse(Response.Status.OK, "application/json", o.toString());
            r.addHeader("Access-Control-Allow-Origin", "*");
            return r;
        }
        Response jsonErr(String e, int code) {
            Response.Status st = code == 403 ? Response.Status.FORBIDDEN : Response.Status.BAD_REQUEST;
            Response r = newFixedLengthResponse(st, "application/json", "{\"ok\":false,\"err\":\"" + e + "\"}");
            r.addHeader("Access-Control-Allow-Origin", "*");
            return r;
        }
        Response serveTrack(String id, String range) {
            try {
                Uri cu = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, Long.parseLong(id));
                ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(cu, "r");
                long size = pfd.getStatSize();
                String mime = getContentResolver().getType(cu);
                if (mime == null) mime = "audio/mpeg";
                long start = 0, end = size - 1;
                if (range != null && range.startsWith("bytes=")) {
                    String[] parts = range.substring(6).split("-", 2);
                    try { if (!parts[0].isEmpty()) start = Long.parseLong(parts[0]); } catch (Exception ignored) {}
                    try { if (parts.length > 1 && !parts[1].isEmpty()) end = Math.min(Long.parseLong(parts[1]), size - 1); } catch (Exception ignored) {}
                }
                long len = end - start + 1;
                InputStream in = new java.io.FileInputStream(pfd.getFileDescriptor());
                //noinspection ResultOfMethodCallIgnored
                in.skip(start);
                InputStream capped = new CappedStream(in, len);
                Response r = newFixedLengthResponse(start == 0 && len == size ? Response.Status.OK : Response.Status.PARTIAL_CONTENT, mime, capped, len);
                r.addHeader("Accept-Ranges", "bytes");
                if (r.getStatus() == Response.Status.PARTIAL_CONTENT) r.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
                return r;
            } catch (FileNotFoundException fnf) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found");
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found");
            }
        }
        Response serveAsset(String uri) {
            try {
                AssetManager am = getAssets();
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
                        return newFixedLengthResponse(Response.Status.OK, mime, new java.io.ByteArrayInputStream(bos.toByteArray()), bos.size());
                    } catch (Exception ignored) {}
                }
                InputStream in = am.open("public/index.html");
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                return newFixedLengthResponse(Response.Status.OK, "text/html", new java.io.ByteArrayInputStream(bos.toByteArray()), bos.size());
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.OK, "text/html", "<h1>songjam running — open Host page</h1>");
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
