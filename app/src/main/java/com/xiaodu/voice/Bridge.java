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
    private final Handler ui = new Handler(Looper.getMainLooper());

    public Bridge(MainActivity act, Settings settings, AudioEngine audio, AecTester aec) {
        this.act = act;
        this.settings = settings;
        this.audio = audio;
        this.aec = aec;
    }

    // ---------------- 会话控制 ----------------

    @JavascriptInterface
    public void start() {
        act.startSession();
    }

    @JavascriptInterface
    public void stop() {
        act.stopSession();
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
            au.put("volume", settings.getVolume());
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
                if (au.has("volume")) settings.setVolume(au.optInt("volume"));
                if (au.has("micGain")) settings.setMicGain((float) au.optDouble("micGain"));
                if (au.has("jitterMs")) settings.setJitterMs(au.optInt("jitterMs"));
            }
            if (o.has("ui")) {
                JSONObject u = o.getJSONObject("ui");
                if (u.has("mode")) settings.setUiMode(u.optString("mode"));
                if (u.has("dim")) settings.setDim(u.optInt("dim"));
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
        act.openSystem("wifi");
    }

    @JavascriptInterface
    public void openSettings() {
        act.openSystem("settings");
    }

    @JavascriptInterface
    public void back() {
        act.goHome();
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
                    o.put("refDb", r.refDb);
                    o.put("echoDb", r.echoDb);
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
