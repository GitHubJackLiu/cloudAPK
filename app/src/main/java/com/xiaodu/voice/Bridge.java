package com.xiaodu.voice;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * JS Bridge：网页调原生的唯一入口。
 *
 * 网页侧调用方式（assets/index.html）：
 *   Native.start()             开始对话（起音频引擎 + 小智连接）
 *   Native.stop()              停止
 *   Native.setMode('half')     切交互模式
 *   Native.getSettings()       返回 JSON 字符串
 *   Native.saveSettings(json)  保存
 *   Native.otaCheck()          OTA 绑定检测
 *   Native.openWifi()          拉起系统 Wi-Fi 面板
 *   Native.back()              回到主界面
 *
 * 原生 → 网页：统一走 window.onNativeEvent(jsonString)，
 * 避免为每种事件在网页里注册一个全局函数。
 */
public class Bridge {

    private static final String TAG = "XzBridge";

    private final MainActivity act;
    private final Settings settings;
    private final AudioEngine audio;
    private final AecTester aec;
    private final WifiHelper wifi;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private AecModeTester aecMode;

    public Bridge(MainActivity act, Settings settings, AudioEngine audio, AecTester aec,
                  WifiHelper wifi) {
        this.act = act;
        this.settings = settings;
        this.audio = audio;
        this.aec = aec;
        this.wifi = wifi;
    }

    public void setAecModeTester(AecModeTester t) { this.aecMode = t; }

    // ---------------- 会话控制 ----------------

    /**
     * 注意：@JavascriptInterface 的方法跑在 WebView 的 JavaBridge 线程上，
     * 而 startActivity / requestPermissions / 改 Window 属性都必须回到主线程，
     * 否则轻则无效重则抛 CalledFromWrongThreadException。
     */
    @JavascriptInterface
    public void start() {
        ui.post(act::startSession);
    }

    @JavascriptInterface
    public void stop() {
        ui.post(act::stopSession);
    }

    @JavascriptInterface
    public void setMode(String mode) {
        settings.setUiMode(mode);
        emit("mode", "{\"mode\":\"" + mode + "\"}");
    }

    @JavascriptInterface
    public String getMode() {
        return settings.getUiMode();
    }

    @JavascriptInterface
    public void setEmotion(String name) {
        // 表情由网页自己驱动 Lottie，原生只做记录，便于以后接系统动画
        emit("emotion", "{\"emotion\":\"" + name + "\"}");
    }

    // ---------------- 配置 ----------------

    @JavascriptInterface
    public String getSettings() {
        JSONObject o = new JSONObject();
        try {
            JSONObject xz = new JSONObject();
            xz.put("endpoint", settings.getEndpoint());
            xz.put("token", settings.getToken());
            xz.put("ota", settings.getOtaUrl());
            xz.put("transport", settings.getTransport());
            xz.put("deviceId", settings.getDeviceId());
            xz.put("clientId", settings.getClientId());
            o.put("xiaozhi", xz);

            JSONObject au = new JSONObject();
            // 音量以系统 STREAM_MUSIC 为真源，配置里的旧值只作兜底
            au.put("volume", act.getVolumePct());
            au.put("micGain", settings.getMicGain());
            au.put("jitterMs", settings.getJitterMs());
            o.put("audio", au);

            JSONObject ui2 = new JSONObject();
            ui2.put("mode", settings.getUiMode());
            ui2.put("dim", settings.getDim());
            ui2.put("wakeLock", settings.getWakeLock());
            ui2.put("showDebug", settings.getShowDebug());
            o.put("ui", ui2);
        } catch (Exception e) {
            Log.e(TAG, "getSettings", e);
        }
        return o.toString();
    }

    @JavascriptInterface
    public void saveSettings(String json) {
        try {
            JSONObject o = new JSONObject(json);
            if (o.has("xiaozhi")) {
                JSONObject xz = o.getJSONObject("xiaozhi");
                if (xz.has("endpoint")) settings.setEndpoint(xz.optString("endpoint"));
                if (xz.has("token")) settings.setToken(xz.optString("token"));
                if (xz.has("ota")) settings.setOtaUrl(xz.optString("ota"));
                if (xz.has("transport")) settings.setTransport(xz.optString("transport"));
                if (xz.has("deviceId")) settings.setDeviceId(xz.optString("deviceId"));
                if (xz.has("clientId")) settings.setClientId(xz.optString("clientId"));
            }
            if (o.has("audio")) {
                JSONObject au = o.getJSONObject("audio");
                if (au.has("volume")) act.setVolumePct(au.optInt("volume"));
                if (au.has("micGain")) settings.setMicGain((float) au.optDouble("micGain"));
                if (au.has("jitterMs")) settings.setJitterMs(au.optInt("jitterMs"));
            }
            if (o.has("ui")) {
                JSONObject u = o.getJSONObject("ui");
                if (u.has("mode")) settings.setUiMode(u.optString("mode"));
                if (u.has("dim")) {
                    final int dimPct = u.optInt("dim");
                    ui.post(() -> act.applyBrightness(dimPct));
                }
                if (u.has("wakeLock")) settings.setWakeLock(u.optBoolean("wakeLock"));
                if (u.has("showDebug")) settings.setShowDebug(u.optBoolean("showDebug"));
            }
            emit("saved", "{\"ok\":true}");
        } catch (Exception e) {
            emit("saved", "{\"ok\":false,\"err\":\"" + e.getMessage() + "\"}");
        }
    }

    // ---------------- 系统 ----------------

    @JavascriptInterface
    public String getSysInfo() {
        JSONObject o = new JSONObject();
        try {
            o.put("ip", settings.localIp());
            o.put("mac", Settings.readMac());
            o.put("model", android.os.Build.MODEL);
            o.put("sdk", Build.VERSION.SDK_INT);
            o.put("abi", Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "");
        } catch (Exception ignored) { }
        return o.toString();
    }

    /** OTA 绑定检测：返回 {ok, code, message, fw} */
    @JavascriptInterface
    public String otaCheck() {
        return act.otaCheck();
    }

    @JavascriptInterface
    public void openWifi() {
        ui.post(() -> act.openSystem("wifi"));
    }

    @JavascriptInterface
    public void openSettings() {
        ui.post(() -> act.openSystem("settings"));
    }

    @JavascriptInterface
    public void back() {
        ui.post(act::goHome);
    }

    // ---------------- 音量 / 亮度 ----------------

    /** 系统媒体音量百分比（0~100），与设置页滑块同一真源 */
    @JavascriptInterface
    public int getVolume() {
        return act.getVolumePct();
    }

    @JavascriptInterface
    public void setVolume(int pct) {
        act.setVolumePct(pct);
    }

    @JavascriptInterface
    public void setBrightness(final int pct) {
        settings.setDim(Math.max(4, Math.min(100, pct)));
        ui.post(() -> act.applyBrightness(pct));
    }

    // ---------------- Wi-Fi ----------------

    @JavascriptInterface
    public String wifiStatus() {
        return wifi.status();
    }

    @JavascriptInterface
    public String wifiScan() {
        return wifi.scan();
    }

    @JavascriptInterface
    public String wifiConnect(String ssid, String pwd) {
        return wifi.connect(ssid, pwd);
    }

    @JavascriptInterface
    public boolean wifiEnabled() {
        try {
            return wifi.status().contains("\"enabled\":true");
        } catch (Throwable t) {
            return false;
        }
    }

    @JavascriptInterface
    public boolean setWifiEnabled(boolean on) {
        return wifi.setEnabled(on);
    }

    @JavascriptInterface
    public void requestLocation() {
        ui.post(act::requestLocationPermission);
    }

    @JavascriptInterface
    public boolean hasLocation() {
        return act.hasLocationPermission();
    }

    @JavascriptInterface
    public void testSpeaker() {
        audio.playTone(1000, 2000, 8000);
    }

    // ---------------- AEC 通路实验 ----------------

    /**
     * 跑 AEC 通路对比矩阵，结果通过 onNativeEvent('aec') 逐条回传。
     * 这是验证「原生 VOICE_COMMUNICATION 能否启用硬件 AEC」的关键实验。
     */
    @JavascriptInterface
    public void runAecMatrix() {
        new Thread(() -> {
            List<AecTester.Result> rs = aec.runMatrix((name, r) -> {
                JSONObject o = new JSONObject();
                try {
                    o.put("name", r.name);
                    o.put("floorDb", r.floorDb);
                    o.put("toneDb", r.toneDb);
                    o.put("erle", r.erle);
                    o.put("note", r.note == null ? "" : r.note);
                } catch (Exception ignored) { }
                emit("aec", o.toString());
            });
            JSONObject done = new JSONObject();
            try { done.put("count", rs.size()); } catch (Exception ignored) { }
            emit("aecDone", done.toString());
        }, "xz-aec").start();
    }

    @JavascriptInterface
    public void cancelAec() {
        aec.cancel();
    }

    /**
     * AEC 第二轮实验：围绕「回声参考从哪来」做播放侧变量矩阵。
     * 跨通路矩阵无法回答「硬件 AEC 有没有在抑制回声」，这个才是。
     */
    @JavascriptInterface
    public void runAecMode() {
        if (aecMode == null) return;
        new Thread(() -> {
            List<AecModeTester.Result> rs = aecMode.runMatrix((res) -> {
                JSONObject o = new JSONObject();
                try {
                    o.put("name", res.name);
                    o.put("src", res.src);
                    o.put("usage", res.usage);
                    o.put("mode", res.mode);
                    o.put("trackFirst", res.trackFirst);
                    o.put("prefSpeaker", res.prefSpeaker);
                    o.put("floorDb", res.floorDb);
                    o.put("toneDb", res.toneDb);
                    o.put("note", res.note == null ? "" : res.note);
                } catch (Exception ignored) { }
                emit("aecMode", o.toString());
            });
            JSONObject done = new JSONObject();
            try { done.put("count", rs.size()); } catch (Exception ignored) { }
            emit("aecModeDone", done.toString());
        }, "xz-aecmode").start();
    }

    /** 原生直接触发 A/B（供 adb / SSH 自动化用） */
    @JavascriptInterface
    public void runAecModeNow() {
        ui.postDelayed(() -> {
            try {
                act.webView.evaluateJavascript(
                        "window.showAecPanel && window.showAecPanel();"
                                + "window.runAecMode && window.runAecMode()", null);
            } catch (Throwable t) {
                Log.w(TAG, "runAecModeNow failed: " + t);
            }
        }, 1500);
    }

    /**
     * 原生直接跑 A/B，完全不依赖网页加载。
     * 结果同样写 logcat（tag XzAecMode），自动化脚本只需要读 logcat。
     */
    public void runAecModeDirect() {
        if (aecMode == null) {
            Log.w(TAG, "aecMode tester not ready");
            return;
        }
        new Thread(() -> {
            List<AecModeTester.Result> rs = aecMode.runMatrix(res -> {
                JSONObject o = new JSONObject();
                try {
                    o.put("name", res.name);
                    o.put("floorDb", res.floorDb);
                    o.put("toneDb", res.toneDb);
                    o.put("note", res.note == null ? "" : res.note);
                } catch (Exception ignored) { }
                emit("aecMode", o.toString());
            });
            Log.i(TAG, "AEC mode matrix done, cases=" + rs.size());
        }, "xz-aecmode").start();
    }

    /**
     * 直接打开 AEC 实验面板。
     * 不依赖手势：设备上 input swipe 的长按不可靠，而原生调 JS 最稳。
     */
    @JavascriptInterface
    public void showAecPanel() {
        ui.post(() -> {
            try {
                act.webView.evaluateJavascript(
                        "window.showAecPanel && window.showAecPanel()", null);
            } catch (Throwable t) {
                Log.w(TAG, "showAecPanel failed: " + t);
            }
        });
    }

    /** 原生直接触发矩阵（供 adb / SSH 自动化用，无需屏幕点击） */
    @JavascriptInterface
    public void runAecMatrixNow() {
        // 页面可能还没加载完，延后一点再调 JS
        ui.postDelayed(() -> {
            try {
                act.webView.evaluateJavascript(
                        "window.showAecPanel && window.showAecPanel();"
                                + "window.runAecMatrix && window.runAecMatrix()", null);
            } catch (Throwable t) {
                Log.w(TAG, "runAecMatrixNow failed: " + t);
            }
        }, 1500);
    }

    /**
     * 采集电平（0~100）。
     *
     * 单独开一个返回 int 的接口：电平条要按 4Hz 刷新，走 getAudioPath() 的话
     * 每次都要在原生侧拼 JSON、再到 JS 侧 JSON.parse，同步穿越两次，
     * 低端机上一次十几毫秒，界面跟着发涩。
     */
    @JavascriptInterface
    public int getLevel() {
        return audio.getLevel();
    }

    /** 当前音频通路参数，供网页显示 */
    @JavascriptInterface
    public String getAudioPath() {
        JSONObject o = new JSONObject();
        try {
            o.put("src", audio.getCaptureSource());
            o.put("usage", audio.getPlayUsage());
            o.put("mode", audio.getAudioMode());
            o.put("running", audio.isRunning());
            o.put("muted", audio.isMuted());
            o.put("level", audio.getLevel());
        } catch (Exception ignored) { }
        return o.toString();
    }

    // ---------------- 事件下发 ----------------

    /**
     * 原生 → 网页。所有事件统一走 window.onNativeEvent(json)。
     */
    public void emit(String type, String payloadJson) {
        final String js = "window.onNativeEvent && window.onNativeEvent('" + type + "',"
                + (payloadJson == null ? "{}" : payloadJson) + ")";
        ui.post(() -> {
            try {
                act.webView.evaluateJavascript(js, null);
            } catch (Throwable t) {
                Log.w(TAG, "emit failed: " + t);
            }
        });
    }
}
