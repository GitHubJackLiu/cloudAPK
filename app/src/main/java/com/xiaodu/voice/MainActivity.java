package com.xiaodu.voice;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 主界面：全屏 WebView 壳 + 原生能力。
 *
 * 架构（B 计划）：
 *   HTML/CSS/JS  —— UI、表情、气泡
 *   JS Bridge    —— 控制指令与状态回传
 *   Java 原生    —— 音频采集/播放、AEC 通路、MQTT/UDP/AES、OTA
 *
 * 与旧 Python 方案的关键差异：音频不再经过 WebSocket 在 JS 与原生间中转，
 * 而是原生内部闭环（采集→编码→上行；下行→解码→播放），
 * 这样回声参考的时序不会被跨进程/JS 中转打乱，AEC 才能真正生效。
 */
public class MainActivity extends Activity {

    private static final String TAG = "XzMain";

    public WebView webView;
    private Settings settings;
    private AudioEngine audio;
    private AecTester aec;
    private Bridge bridge;
    private XzSession session;
    private WebViewClient webClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        settings = new Settings(this);
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        audio = new AudioEngine(am);
        aec = new AecTester(audio, am);
        session = new XzSession(this, settings, audio);

        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // 页面从 assets 加载，emoji 等相对资源同源访问
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        // 音频走原生，WebView 本身不需要媒体权限，但保留以便降级自检
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);

        webClient = new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                // 页面内跳转（settings.html）留在 WebView 内
                return false;
            }
            @Override
            public void onPageFinished(WebView v, String url) {
                Log.i(TAG, "page loaded: " + url);
            }
        };
        webView.setWebViewClient(webClient);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest request) {
                // 直接授予 WebRTC 资源请求（麦克风/摄像头）
                runOnUiThread(new Runnable() {
                    public void run() {
                        request.grant(request.getResources());
                    }
                });
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.i(TAG, "[web] " + cm.message() + " @" + cm.lineNumber());
                return true;
            }
        });

        bridge = new Bridge(this, settings, audio, aec);
        session.setBridge(bridge);
        webView.addJavascriptInterface(bridge, "Native");

        // 冷启动时也响应广播命令
        handleCommand(getIntent());

        webView.loadUrl("file:///android_asset/index.html");
        applyImmersive();
        ensurePermissions();
        // Phase 1（AEC 通路验证）：音频协议尚未接通（Opus 未链接），
        // 不自动拉起小智会话，避免验证码面板干扰 AEC 实验。
        // Phase 2 接通后改为 session.start()。
        session.startIfReady();
    }

    // ---------------- 全屏 ----------------

    private void applyImmersive() {
        View d = getWindow().getDecorView();
        d.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    // ---------------- 权限 ----------------

    private void ensurePermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            boolean need = false;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                need = true;
            if (checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED)
                need = true;
            if (need) {
                requestPermissions(new String[]{
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.ACCESS_NETWORK_STATE}, 1);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grant) {
        super.onRequestPermissionsResult(code, perms, grant);
        if (code == 1) {
            boolean all = true;
            for (int g : grant) if (g != PackageManager.PERMISSION_GRANTED) all = false;
            bridge.emit("permission", all ? "{\"ok\":true}" : "{\"ok\":false}");
        }
    }

    // ---------------- 会话 ----------------

    public void startSession() {
        if (session.isRunning()) return;
        session.start();
    }

    public void stopSession() {
        session.stop();
    }

    public void goHome() {
        Intent i = new Intent();
        i.setAction(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_HOME);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    public void openSystem(String target) {
        // 用全限定名：本包的 Settings 类会遮蔽 android.provider.Settings
        String action;
        switch (target == null ? "" : target) {
            case "wifi": action = android.provider.Settings.ACTION_WIFI_SETTINGS; break;
            case "display": action = android.provider.Settings.ACTION_DISPLAY_SETTINGS; break;
            case "sound": action = android.provider.Settings.ACTION_SOUND_SETTINGS; break;
            default: action = android.provider.Settings.ACTION_SETTINGS; break;
        }
        try {
            startActivity(new Intent(action));
        } catch (Throwable t) {
            Log.e(TAG, "openSystem failed", t);
        }
    }

    // ---------------- OTA 检测 ----------------

    /**
     * POST 小智 OTA 接口，返回绑定状态。
     * Device-Id 必须是 AA:BB:CC:DD:EE:FF 形式，否则服务端判 Invalid MAC。
     */
    public String otaCheck() {
        JSONObject out = new JSONObject();
        try {
            String dev = settings.getDeviceId();
            JSONObject body = new JSONObject();
            body.put("version", 2);
            body.put("language", "zh-CN");
            body.put("mac_address", dev);
            body.put("uuid", settings.getClientId());
            JSONObject app = new JSONObject();
            app.put("name", "xiaozhi");
            app.put("version", "1.0.0");
            app.put("elf_sha256", settings.getHmacKey());
            body.put("application", app);
            JSONObject board = new JSONObject();
            board.put("type", "bread-compact-wifi");
            board.put("name", "xiaozhi");
            board.put("ip", settings.localIp());
            board.put("mac", dev);
            body.put("board", board);
            JSONObject proto = new JSONObject();
            JSONObject feat = new JSONObject();
            feat.put("mcp", true);
            proto.put("features", feat);
            body.put("protocol", proto);

            String url = settings.getOtaUrl();
            if (!url.endsWith("/")) url += "/";
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Device-Id", dev);
            c.setRequestProperty("Client-Id", settings.getClientId());
            c.setRequestProperty("Activation-Version", "2");
            c.setRequestProperty("Serial-Number", settings.getSerialNumber());
            c.setRequestProperty("User-Agent", "bread-compact-wifi/xiaozhi-1.0.0");
            c.setRequestProperty("Accept-Language", "zh-CN");
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            String resp = readAll(code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream());
            c.disconnect();

            out.put("ok", code >= 200 && code < 300);
            out.put("status", code);
            try {
                JSONObject d = new JSONObject(resp);
                JSONObject act2 = d.optJSONObject("activation");
                if (act2 != null) {
                    out.put("code", act2.optString("code"));
                    out.put("message", act2.optString("message"));
                }
                JSONObject fw = d.optJSONObject("firmware");
                if (fw != null) out.put("fw", fw.optString("version"));
                JSONObject mq = d.optJSONObject("mqtt");
                if (mq != null) {
                    out.put("hasMqtt", true);
                    out.put("endpoint", mq.optString("endpoint"));
                    out.put("publishTopic", mq.optString("publish_topic"));
                }
            } catch (Exception ignored) { }
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("err", t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Exception ignored) { }
        }
        return out.toString();
    }

    private String readAll(java.io.InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleCommand(intent);
    }

    /**
     * 支持用 adb 广播触发动作，避免设备上无法可靠注入按键/手势。
     *   adb shell am broadcast -a com.xiaodu.voice.AEC -n com.xiaodu.voice/.MainActivity
     */
    private void handleCommand(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String a = intent.getAction();
        if ("com.xiaodu.voice.AEC".equals(a)) {
            Log.i(TAG, "cmd: run AEC matrix");
            if (bridge != null) bridge.runAecMatrixNow();
        } else if ("com.xiaodu.voice.PANEL".equals(a)) {
            if (bridge != null) bridge.showAecPanel();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        session.stop();
        audio.stop();
    }
}
