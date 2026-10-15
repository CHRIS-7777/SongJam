package com.chris7777.songjam;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;

import com.getcapacitor.BridgeActivity;

import org.json.JSONObject;

/**
 * songjam MainActivity — Capacitor shell + silent bg server bridge.
 * JS calls window.SongJamNative.startServer({port}) on Stream tap.
 */
public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        attachBridge();
        ensurePerms();
        // pre-warm server silently so Stream list is instant
        try {
            Intent i = new Intent(this, JamService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception ignored) {}
    }

    @Override
    public void onResume() {
        super.onResume();
        attachBridge();
    }

    void attachBridge() {
        try {
            WebView wv = getBridge() != null ? getBridge().getWebView() : null;
            if (wv != null) {
                wv.getSettings().setMediaPlaybackRequiresUserGesture(false);
                wv.addJavascriptInterface(new Js(), "SongJamNative");
            }
        } catch (Exception ignored) {}
    }

    void ensurePerms() {
        try {
            java.util.List<String> need = new java.util.ArrayList<>();
            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission(android.Manifest.permission.READ_MEDIA_AUDIO)
                        != PackageManager.PERMISSION_GRANTED)
                    need.add(android.Manifest.permission.READ_MEDIA_AUDIO);
                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED)
                    need.add(android.Manifest.permission.POST_NOTIFICATIONS);
            } else {
                if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED)
                    need.add(android.Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (!need.isEmpty()) ActivityCompat.requestPermissions(this, need.toArray(new String[0]), 1001);
        } catch (Exception ignored) {}
    }

    public class Js {
        @JavascriptInterface
        public String startServer(String optsJson) {
            int port = 8080;
            try {
                JSONObject o = new JSONObject(optsJson == null ? "{}" : optsJson);
                port = o.optInt("port", 8080);
            } catch (Exception ignored) {}
            try {
                Intent i = new Intent(MainActivity.this, JamService.class);
                i.putExtra("port", port);
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                else startService(i);
            } catch (Exception ignored) {}
            // wait briefly for httpd
            for (int k = 0; k < 20 && JamService.HTTPD == null; k++) {
                try { Thread.sleep(100); } catch (Exception ignored) {}
            }
            try {
                JSONObject r = new JSONObject();
                r.put("ip", JamService.hotspotIp());
                r.put("port", JamService.PORT);
                r.put("pin", JamService.PIN == null ? "" : JamService.PIN);
                return r.toString();
            } catch (Exception e) { return "{\"ip\":\"192.168.43.1\",\"port\":8080,\"pin\":\"\"}"; }
        }

        @JavascriptInterface
        public String requestPermissions() {
            try {
                MainActivity.this.runOnUiThread(new Runnable() {
                    public void run() { try { ensurePerms(); } catch (Exception ignored) {} }
                });
            } catch (Exception ignored) {}
            return "{\"requested\":true}";
        }

        @JavascriptInterface
        public String listSongs() {
            return "[]";
        }
    }
}
