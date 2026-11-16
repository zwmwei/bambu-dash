package com.xingxing.bambudash;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

public class MainActivity extends Activity implements BambuClient.Sink {

    private WebView web;
    private BambuClient client;
    private SharedPreferences sp;
    private FrameLayout root;
    private CamOverlay camLayer;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        sp = getSharedPreferences("bambu", MODE_PRIVATE);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xFF0A0D10));
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(0xFF0A0D10);
            getWindow().setNavigationBarColor(0xFF0A0D10);
        }

        web = new WebView(this);
        web.setBackgroundColor(0xFF0A0D10);
        web.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        if (Build.VERSION.SDK_INT >= 19) WebView.setWebContentsDebuggingEnabled(true);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0A0D10);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        camLayer = new CamOverlay(this);
        root.addView(camLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        web.loadUrl("file:///android_asset/index.html");
    }

    /* ---------------- JS 可调用的接口 ---------------- */

    public class Bridge {

        @JavascriptInterface
        public void connect(final String ip, final String code, final String serial) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (client != null) client.stop();
                    client = new BambuClient(ip, code, serial, MainActivity.this);
                    client.start();
                }
            });
        }

        @JavascriptInterface
        public void disconnect() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (client != null) { client.stop(); client = null; }
                }
            });
        }

        @JavascriptInterface
        public void command(final String json) {
            final BambuClient c = client;
            if (c != null) c.publishCommand(json);
        }

        @JavascriptInterface
        public void saveConfig(String ip, String code, String serial) {
            sp.edit()
                    .putString("ip", ip)
                    .putString("code", code)
                    .putString("serial", serial)
                    .apply();
        }

        @JavascriptInterface
        public String getSavedConfig() {
            String ip = sp.getString("ip", "");
            if (ip == null || ip.length() == 0) return "";
            try {
                JSONObject o = new JSONObject();
                o.put("ip", ip);
                o.put("code", sp.getString("code", ""));
                o.put("serial", sp.getString("serial", ""));
                return o.toString();
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void toast(String msg) {
            final String m = msg;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    android.widget.Toast.makeText(MainActivity.this, m, android.widget.Toast.LENGTH_SHORT).show();
                }
            });
        }

        /* ---- 摄像头：原生播放 RTSPS，WebView 本身不支持 RTSP ---- */

        @JavascriptInterface
        public boolean camSupported() {
            return true;
        }

        @JavascriptInterface
        public void camStart(final String ip, final String code) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (camLayer != null) camLayer.open(ip, code);
                }
            });
        }

        @JavascriptInterface
        public void camStop() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (camLayer != null) camLayer.hide();
                }
            });
        }

        @JavascriptInterface
        public boolean camIsOpen() {
            return camLayer != null && camLayer.isOpen();
        }
    }

    /* ---------------- 原生 -> JS 推送 ---------------- */

    @Override
    public void onJson(final String json) {
        if (web == null) return;
        web.post(new Runnable() {
            @Override
            public void run() {
                String js = "window.__onBambuData&&window.__onBambuData(" + JSONObject.quote(json) + ")";
                try {
                    web.evaluateJavascript(js, null);
                } catch (Exception ignored) { }
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (camLayer != null && camLayer.isOpen()) {
            camLayer.hide();
            return;
        }
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        if (camLayer != null) camLayer.hide();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (client != null) { client.stop(); client = null; }
        if (camLayer != null) camLayer.hide();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
