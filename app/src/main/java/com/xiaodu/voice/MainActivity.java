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
    private AecModeTester aecMode;
    private Bridge bridge;
    private XzSession session;
    private WifiHelper wifi;
    public UpdateChecker updater;
    private WebViewClient webClient;
    private boolean pageReady = false;
    private String pendingCmd = null;
    private boolean pendingStart = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        settings = new Settings(this);
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        audio = new AudioEngine(am);
        aec = new AecTester(audio, am);
        aecMode = new AecModeTester(audio);
        session = new XzSession(this, settings, audio);
        wifi = new WifiHelper(this);
        updater = new UpdateChecker(this);

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
                pageReady = true;
                runPendingCmd();
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

        bridge = new Bridge(this, settings, audio, aec, wifi);
        bridge.setAecModeTester(aecMode);
        session.setBridge(bridge);
        webView.addJavascriptInterface(bridge, "Native");

        // 冷启动时也响应广播命令
        handleCommand(getIntent());

        webView.loadUrl("file:///android_asset/index.html");
        applyImmersive();
        applyBrightness(settings.getDim());
        ensurePermissions();
        // 默认不自动通话：等用户在界面上点「通话」。
        // 以前这里是 session.startIfReady()，结果是开机就占麦克风、
        // 而且一进桌面就跳出激活码面板。

        // 启动后延迟检查更新：纯网络请求放后台线程，不阻塞页面加载/预热；
        // 第 3 步只查不装，结果打 logcat（tag XzUpdate），界面暂不改。
        scheduleAutoUpdateCheck();
    }

    /** 启动 8 秒后后台检查一次更新；失败静默，绝不影响通话/表情预热。 */
    private void scheduleAutoUpdateCheck() {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            new Thread(() -> {
                try {
                    updater.check();
                } catch (Throwable t) {
                    Log.w(TAG, "auto update check failed: " + t);
                }
            }, "xz-update-check").start();
        }, 8000);
    }

    // ---------------- 音量 / 亮度（走系统接口，不用遮罩层） ----------------

    /**
     * 屏幕亮度。
     *
     * 用窗口级 screenBrightness 而不是 Settings.System.SCREEN_BRIGHTNESS：
     *   · 前者不需要 WRITE_SETTINGS 权限，也能立即生效；
     *   · 作用域只在本 Activity，退出应用自动恢复系统亮度，
     *     不会把用户设备的全局亮度改掉。
     * 取值范围 0.0~1.0，-1 表示跟随系统。
     */
    public void applyBrightness(int pct) {
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (pct >= 100) {
                // 100% = 跟随系统亮度，不强行把背光顶到最大（省电、也不刺眼）
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            } else {
                lp.screenBrightness = Math.max(0.04f, Math.min(1f, pct / 100f));
            }
            getWindow().setAttributes(lp);
        } catch (Throwable t) {
            Log.w(TAG, "applyBrightness failed: " + t);
        }
    }

    /** 系统媒体音量百分比 */
    public int getVolumePct() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            return max <= 0 ? 0 : Math.round(cur * 100f / max);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * 设置系统媒体音量。
     *
     * 以前「应用音量」滑块只写进配置、AudioEngine 里根本没有用它，
     * 属于假控件。这里直接改 STREAM_MUSIC，拖动立刻能听出变化。
     * flags=0：不加 FLAG_SHOW_UI，避免系统弹音量条盖住界面。
     */
    public void setVolumePct(int pct) {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int idx = Math.round(Math.max(0, Math.min(100, pct)) / 100f * max);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(0, Math.min(max, idx)), 0);
            settings.setVolume(pct);
        } catch (Throwable t) {
            Log.w(TAG, "setVolumePct failed: " + t);
        }
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
            if (all && pendingStart) {
                pendingStart = false;
                session.start();
            } else if (!all) {
                pendingStart = false;
            }
        } else if (code == 2) {
            boolean ok = hasLocationPermission();
            bridge.emit("permission", "{\"loc\":" + ok + "}");
        }
    }

    /** 扫描 Wi-Fi 列表需要定位权限（API 23 起把 scan 结果当位置数据管） */
    public boolean hasLocationPermission() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 供 Wi-Fi 页面在扫描前主动申请；结果通过 permission 事件回传 */
    public void requestLocationPermission() {
        if (hasLocationPermission()) {
            bridge.emit("permission", "{\"loc\":true}");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 2);
        }
    }

    // ---------------- 会话 ----------------

    public void startSession() {
        if (session.isRunning()) return;
        // 卸载重装后运行时权限会被清掉，此时点「通话」不能静默失败，
        // 先申请权限，拿到后自动接上。
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true;
            ensurePermissions();
            return;
        }
        session.start();
    }

    public void stopSession() {
        // 先同步把 running 抢下来，防止 stop 线程还没开跑时用户就又点了通话；
        // cleanup() 里要 join 采集/播放线程（各最多 400ms），放工作线程做。
        // 前端会等 session/stopped 事件才把按钮放开，所以 start 不会撞上 cleanup。
        // 即使 already stopped 也要走 finishStop：它会回 stopped 事件，
        // 前端正是等这条事件才把「正在挂断」的按钮放开。
        session.beginStop();
        new Thread(session::finishStop, "xz-stop").start();
    }

    public void goHome() {
        Intent i = new Intent();
        i.setAction(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_HOME);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    /**
     * 返回键优先收起设置浮层，不做退出。
     *
     * 这台设备是常驻终端，误触返回直接退到桌面很难受；
     * 设置页的锁屏也必须有办法退出来（原来只能输对密码）。
     */
    @Override
    public void onBackPressed() {
        if (webView == null) { super.onBackPressed(); return; }
        webView.evaluateJavascript(
                "(function(){var s=document.getElementById('shell');" +
                "if(s&&s.classList.contains('on')){s.classList.remove('on');return '1';}" +
                "return '0';})()",
                value -> {
                    if (value == null || !value.contains("1")) {
                        // 浮层本来就没开，再按返回才退到桌面
                        goHome();
                    }
                });
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
        Log.i(TAG, "handleCommand: " + a);
        if ("com.xiaodu.voice.AEC".equals(a)) {
            Log.i(TAG, "cmd: run AEC matrix");
            pendingCmd = "AEC";
        } else if ("com.xiaodu.voice.AECM".equals(a)) {
            // A/B 对照实验走原生路径，不依赖网页是否加载完
            Log.i(TAG, "cmd: run AEC mode A/B");
            bridge.runAecModeDirect();
            return;
        } else if ("com.xiaodu.voice.PANEL".equals(a)) {
            pendingCmd = "PANEL";
        } else if ("com.xiaodu.voice.UPDATE".equals(a)) {
            // 手动触发更新检查（SSH/adb 自动化用），结果看 logcat tag XzUpdate
            Log.i(TAG, "cmd: check update now");
            new Thread(() -> updater.check(), "xz-update-cmd").start();
            return;
        } else if ("com.xiaodu.voice.DEMO".equals(a)) {
            // 这台设备注入不了语音输入，验证两列气泡落位只能造假事件：
            //   am broadcast -a com.xiaodu.voice.DEMO -n com.xiaodu.voice/.MainActivity \
            //       --es me "我这边说的话" --es ai "小智回复的话"
            String me = intent.getStringExtra("me");
            String ai = intent.getStringExtra("ai");
            String emo = intent.getStringExtra("emo");
            if (me != null) bridge.emit("stt", "{\"text\":\"" + esc(me) + "\"}");
            if (ai != null) bridge.emit("tts", "{\"state\":\"sentence_start\",\"text\":\"" + esc(ai) + "\"}");
            if (emo != null) bridge.emit("llm", "{\"emotion\":\"" + esc(emo) + "\",\"text\":\"\"}");
            return;
        } else {
            return;
        }
        // 页面没加载完时 evaluateJavascript 会静默失败，等 page finished 再执行
        if (pageReady) {
            runPendingCmd();
        }
    }

    /** 最小 JSON 字符串转义：调试广播里的中文文案原样塞进 JSON 用。 */
    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }

    private void runPendingCmd() {
        if (pendingCmd == null) return;
        String c = pendingCmd;
        pendingCmd = null;
        if ("AEC".equals(c)) {
            bridge.runAecMatrixNow();
        } else if ("PANEL".equals(c)) {
            bridge.showAecPanel();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        session.stop();
        audio.stop();
    }
}
