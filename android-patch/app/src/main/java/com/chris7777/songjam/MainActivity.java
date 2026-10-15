package com.chris7777.songjam;

import android.content.Intent;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * songjam MainActivity — Capacitor shell + silent bg server bridge.
 * JS calls window.SongJamNative.startServer({port}) on Stream tap.
 */
public class MainActivity extends BridgeActivity {
    @Override
    public void onStart() {
        super.onStart();
        try {
            WebView wv = getBridge() != null ? getBridge().getWebView() : null;
            if (wv != null) {
                wv.getSettings().setMediaPlaybackRequiresUserGesture(false);
                wv.addJavascriptInterface(new Js(), "SongJamNative");
            }
        } catch (Exception ignored) {}
        // pre-warm server silently so Stream list is instant
        try {
            Intent i = new Intent(this, JamService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
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
        public String listSongs() {
            try {
                JSONArray arr = new JSONArray();
                // query via a temporary service-less helper is complex; let WebView use /api/songs instead
                return arr.toString();
            } catch (Exception e) { return "[]"; }
        }
    }
}
