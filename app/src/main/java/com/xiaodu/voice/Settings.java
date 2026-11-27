package com.xiaodu.voice;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * 配置存储。用 SharedPreferences 代替 Python 版的 ~/xiaozhi/settings.json。
 *
 * 结构保持与 server.py 的 default_settings() 一致，方便前端 settings.html
 * 迁移时不用改字段名。
 */
public class Settings {

    private static final String TAG = "XzSettings";
    private static final String PREFS = "xiaozhi";

    private final SharedPreferences sp;
    private final Context appContext;

    public Settings(Context ctx) {
        this.appContext = ctx.getApplicationContext();
        this.sp = this.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------- 小智连接 ----------------
    public String getEndpoint() { return sp.getString("endpoint", "wss://api.tenclass.net/xiaozhi/v1/"); }
    public void setEndpoint(String v) { sp.edit().putString("endpoint", v).apply(); }

    public String getToken() { return sp.getString("token", ""); }
    public void setToken(String v) { sp.edit().putString("token", v).apply(); }

    public String getOtaUrl() { return sp.getString("ota", "https://api.tenclass.net/xiaozhi/ota/"); }
    public void setOtaUrl(String v) { sp.edit().putString("ota", v).apply(); }

    public String getTransport() { return sp.getString("transport", "auto"); }
    public void setTransport(String v) { sp.edit().putString("transport", v).apply(); }

    /** Device-Id 必须是 AA:BB:CC:DD:EE:FF 形式，无冒号服务端会判 Invalid MAC */
    public String getDeviceId() {
        String v = sp.getString("deviceId", "");
        if (v.isEmpty()) {
            v = readMac().toUpperCase();
            if (v.isEmpty()) {
                v = (UUID.randomUUID().toString().replace("-", "")).substring(0, 12);
            }
            setDeviceId(v);
        }
        return v;
    }
    public void setDeviceId(String v) { sp.edit().putString("deviceId", v).apply(); }

    public String getClientId() {
        String v = sp.getString("clientId", "");
        if (v.isEmpty()) {
            v = UUID.randomUUID().toString();
            setClientId(v);
        }
        return v;
    }
    public void setClientId(String v) { sp.edit().putString("clientId", v).apply(); }

    // ---------------- 音频 ----------------
    public int getVolume() { return sp.getInt("volume", 80); }
    public void setVolume(int v) { sp.edit().putInt("volume", v).apply(); }

    public float getMicGain() { return sp.getFloat("micGain", 1.0f); }
    public void setMicGain(float v) { sp.edit().putFloat("micGain", v).apply(); }

    public int getJitterMs() { return sp.getInt("jitterMs", 200); }
    public void setJitterMs(int v) { sp.edit().putInt("jitterMs", v).apply(); }

    // ---------------- 交互 / 显示 ----------------
    /** half=半双工（TTS 期间停上行），full=全双工 */
    public String getUiMode() { return sp.getString("uiMode", "half"); }
    public void setUiMode(String v) { sp.edit().putString("uiMode", v).apply(); }
    public boolean isHalfDuplex() { return !"full".equals(getUiMode()); }

    public int getDim() { return sp.getInt("dim", 100); }
    public void setDim(int v) { sp.edit().putInt("dim", v).apply(); }

    public boolean getWakeLock() { return sp.getBoolean("wakeLock", true); }
    public void setWakeLock(boolean v) { sp.edit().putBoolean("wakeLock", v).apply(); }

    public boolean getShowDebug() { return sp.getBoolean("showDebug", true); }
    public void setShowDebug(boolean v) { sp.edit().putBoolean("showDebug", v).apply(); }

    /** AEC 通路选择：0=自动(目标组合)，其它为实验编号 */
    public int getAecProfile() { return sp.getInt("aecProfile", 0); }
    public void setAecProfile(int v) { sp.edit().putInt("aecProfile", v).apply(); }

    // ---------------- 设备身份（对应 ESP32 的 efuse） ----------------
    /**
     * SN / hmac_key 由 MAC 稳定派生。
     * py-xiaozhi 在 PC 上没有真 efuse 也这么做，激活 v2 靠这两个值确认「这台设备」。
     */
    public JSONObject getEfuse() {
        try {
            String raw = sp.getString("efuse", "");
            if (!raw.isEmpty()) return new JSONObject(raw);
        } catch (Exception ignored) { }
        String macClean = (getDeviceId() == null ? "" : getDeviceId()).toLowerCase().replace(":", "");
        JSONObject d = new JSONObject();
        try {
            String sn = "SN-" + md5(macClean).substring(0, 8).toUpperCase() + "-" + macClean;
            d.put("serial_number", sn);
            d.put("hmac_key", sha256Hex("xiaodu1c||" + macClean));
            d.put("activation_status", false);
            sp.edit().putString("efuse", d.toString()).apply();
        } catch (JSONException ignored) { }
        return d;
    }

    public void setActivationStatus(boolean ok) {
        try {
            JSONObject d = getEfuse();
            d.put("activation_status", ok);
            sp.edit().putString("efuse", d.toString()).apply();
        } catch (JSONException ignored) { }
    }

    public String getSerialNumber() {
        try { return getEfuse().optString("serial_number", ""); }
        catch (Exception e) { return ""; }
    }

    public String getHmacKey() {
        try { return getEfuse().optString("hmac_key", ""); }
        catch (Exception e) { return ""; }
    }

    // ---------------- 系统信息 ----------------
    public static String readMac() {
        String[] paths = {"/sys/class/net/wlan0/address", "/sys/class/net/eth0/address"};
        for (String p : paths) {
            try {
                java.io.File f = new java.io.File(p);
                if (f.canRead()) {
                    java.io.FileInputStream in = new java.io.FileInputStream(f);
                    BufferedReader br = new BufferedReader(new InputStreamReader(in));
                    String s = br.readLine();
                    br.close();
                    if (s != null && !s.trim().isEmpty()) return s.trim();
                }
            } catch (Exception ignored) { }
        }
        return "";
    }

    /**
     * 本机 IP。走 WifiManager（需要 ACCESS_WIFI_STATE 权限，静态读法最省事）。
     */
    public String localIp() {
        try {
            android.net.wifi.WifiManager wm = (android.net.wifi.WifiManager)
                    appContext.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return String.format("%d.%d.%d.%d",
                            ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
                }
            }
        } catch (Throwable ignored) { }
        return "127.0.0.1";
    }

    public static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    public static String hmacSha256Hex(String key, String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"));
            byte[] r = mac.doFinal(data.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : r) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    public static String httpGet(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "bread-compact-wifi/xiaozhi-1.0.0");
            int code = c.getResponseCode();
            java.io.InputStream is = (code >= 200 && code < 400) ? c.getInputStream() : c.getErrorStream();
            if (is == null) return "";
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            return sb.toString();
        } catch (Exception e) {
            Log.w(TAG, "httpGet failed: " + e);
            return "";
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
