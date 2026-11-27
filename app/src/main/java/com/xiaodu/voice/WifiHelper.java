package com.xiaodu.voice;

import android.content.Context;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/**
 * Wi-Fi 扫描与连接。
 *
 * 为什么用 WifiManager 老接口而不是 WifiNetworkSpecifier：
 *   本机是 Android 8.1（API 27），WifiNetworkSpecifier（API 29+）不存在；
 *   addNetwork + enableNetwork 这条通路在 API 27 上完整可用。
 *   （targetSdk 34 只影响编译期弃用警告，运行时行为按设备 API 决定。）
 *
 * 权限要求：
 *   ACCESS_WIFI_STATE   读状态/扫描结果
 *   CHANGE_WIFI_STATE   开关 Wi-Fi、增删网络、连接
 *   ACCESS_FINE_LOCATION  API 23 起，扫描结果与已保存网络列表都被视为
 *                         可用于推断位置的敏感数据，必须拿到定位权限；
 *                         否则 getScanResults() 返回空列表。
 *   另外系统「位置信息」总开关也要打开，这是 API 27 的硬性前提。
 */
public class WifiHelper {

    private static final String TAG = "XzWifi";

    private final Context ctx;
    private final WifiManager wm;

    public WifiHelper(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.wm = (WifiManager) this.ctx.getSystemService(Context.WIFI_SERVICE);
    }

    // ---------------- 状态 ----------------

    /** 当前连接状态：{enabled, ssid, ip, rssi, level, speed, mac} */
    public String status() {
        JSONObject o = new JSONObject();
        try {
            o.put("supported", wm != null);
            if (wm == null) return o.toString();
            o.put("enabled", wm.isWifiEnabled());
            WifiInfo wi = wm.getConnectionInfo();
            if (wi != null) {
                o.put("ssid", unquote(wi.getSSID()));
                o.put("rssi", wi.getRssi());
                o.put("level", WifiManager.calculateSignalLevel(wi.getRssi(), 4));
                o.put("speed", wi.getLinkSpeed());
                int ip = wi.getIpAddress();
                o.put("ip", ip == 0 ? "" : String.format("%d.%d.%d.%d",
                        ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff));
            }
            o.put("mac", Settings.readMac());
        } catch (Throwable t) {
            Log.w(TAG, "status: " + t);
            try { o.put("err", String.valueOf(t.getMessage())); } catch (Exception ignored) { }
        }
        return o.toString();
    }

    public boolean setEnabled(boolean on) {
        try {
            return wm != null && wm.setWifiEnabled(on);
        } catch (Throwable t) {
            Log.w(TAG, "setWifiEnabled: " + t);
            return false;
        }
    }

    // ---------------- 扫描 ----------------

    /**
     * 扫描周边 AP。
     *
     * startScan() 在 Android 8 以后有频率限制（前台约 4 次/2 分钟），
     * 超限会返回 false 但不会抛错，此时直接读上一次的缓存结果即可，
     * 所以这里不把 startScan 的返回值当失败判据。
     */
    public String scan() {
        JSONObject out = new JSONObject();
        JSONArray arr = new JSONArray();
        try {
            if (wm == null) {
                out.put("ok", false);
                out.put("err", "本机没有 Wi-Fi 服务");
                return out.toString();
            }
            if (!wm.isWifiEnabled()) {
                out.put("ok", false);
                out.put("err", "Wi-Fi 处于关闭状态");
                out.put("off", true);
                return out.toString();
            }
            boolean kicked = false;
            try { kicked = wm.startScan(); } catch (Throwable t) { Log.w(TAG, "startScan: " + t); }

            List<ScanResult> rs = wm.getScanResults();
            if (rs == null) rs = new ArrayList<>();

            WifiInfo wi = wm.getConnectionInfo();
            String curSsid = (wi == null) ? "" : unquote(wi.getSSID());

            // 同一 SSID 可能有多个 AP（mesh/多路由），只保留信号最强的一条
            HashSet<String> seen = new HashSet<>();
            List<ScanResult> list = new ArrayList<>();
            Collections.sort(rs, new Comparator<ScanResult>() {
                @Override
                public int compare(ScanResult a, ScanResult b) {
                    return b.level - a.level;
                }
            });
            for (ScanResult r : rs) {
                if (r.SSID == null || r.SSID.isEmpty()) continue;   // 隐藏网络
                if (seen.contains(r.SSID)) continue;
                seen.add(r.SSID);
                list.add(r);
            }

            for (ScanResult r : list) {
                JSONObject o = new JSONObject();
                o.put("ssid", r.SSID);
                o.put("rssi", r.level);
                // calculateSignalLevel 的 -1/0 语义在 framework 里被规范化过，
                // 直接用 RSSI 分段更稳：-55 以上满格，-85 以下一格。
                o.put("bars", bars(r.level));
                o.put("secured", isSecured(r.capabilities));
                o.put("caps", r.capabilities == null ? "" : r.capabilities);
                o.put("freq", r.frequency);
                o.put("current", r.SSID.equals(curSsid));
                arr.put(o);
            }

            out.put("ok", true);
            out.put("kicked", kicked);
            out.put("list", arr);
            if (arr.length() == 0) {
                // 空列表最常见的原因是定位权限或位置服务没开，明确告知而不是让用户干等
                out.put("err", "没扫到网络。请确认已授予定位权限，且系统「位置信息」已打开");
            }
        } catch (Throwable t) {
            Log.w(TAG, "scan: " + t);
            try {
                out.put("ok", false);
                out.put("err", t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Exception ignored) { }
        }
        return out.toString();
    }

    // ---------------- 连接 ----------------

    /**
     * 连接指定 SSID。
     *
     * 流程：删掉同名旧配置（避免「保存过但密码变了」时复用旧密钥）→ 新建
     * 配置 → addNetwork → saveConfiguration (API 27 需要显式保存才掉电不丢)
     * → enableNetwork(disableOthers=true) → reconnect。
     *
     * @param ssid 不带引号的明文 SSID
     * @param pwd  空串表示开放网络
     */
    public String connect(String ssid, String pwd) {
        JSONObject o = new JSONObject();
        try {
            if (wm == null) {
                o.put("ok", false);
                o.put("err", "本机没有 Wi-Fi 服务");
                return o.toString();
            }
            if (ssid == null || ssid.isEmpty()) {
                o.put("ok", false);
                o.put("err", "SSID 不能为空");
                return o.toString();
            }
            if (!wm.isWifiEnabled()) wm.setWifiEnabled(true);

            String quoted = "\"" + ssid + "\"";

            // 同名旧配置必须先删：addNetwork 遇到已存在的网络会返回 -1
            try {
                List<WifiConfiguration> olds = wm.getConfiguredNetworks();
                if (olds != null) {
                    for (WifiConfiguration e : olds) {
                        if (e.SSID != null && e.SSID.equals(quoted)) {
                            wm.removeNetwork(e.networkId);
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "清理旧配置失败（可能缺定位权限）: " + t);
            }

            WifiConfiguration cfg = new WifiConfiguration();
            cfg.SSID = quoted;
            if (pwd == null || pwd.isEmpty()) {
                cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            } else {
                cfg.preSharedKey = "\"" + pwd + "\"";
                cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
            }

            int id = wm.addNetwork(cfg);
            if (id < 0) {
                o.put("ok", false);
                // 最常见的两个原因：同名网络已存在且删不掉（缺定位权限读不到配置列表），
                // 或密码/加密类型与路由器不匹配。
                o.put("err", "写入网络配置被拒绝（请先授予定位权限，或检查密码）");
                return o.toString();
            }
            wm.saveConfiguration();
            boolean enabled = wm.enableNetwork(id, true);
            wm.reconnect();

            o.put("ok", enabled);
            o.put("id", id);
            if (!enabled) o.put("err", "启用网络失败");
        } catch (Throwable t) {
            Log.w(TAG, "connect: " + t);
            try {
                o.put("ok", false);
                o.put("err", t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Exception ignored) { }
        }
        return o.toString();
    }

    /** 断开当前网络（保留配置，不做 forget） */
    public boolean disconnect() {
        try {
            return wm != null && wm.disconnect();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- 工具 ----------------

    private static String unquote(String s) {
        if (s == null) return "";
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** 只有开放网络才算不安全；WEP/WPA/WPA2/WPA3 一律当作需要密码 */
    private static boolean isSecured(String caps) {
        if (caps == null) return false;
        String c = caps.toUpperCase();
        return c.contains("WPA") || c.contains("WEP") || c.contains("SAE")
                || c.contains("EAP") || c.contains("OWE");
    }

    /** RSSI → 0~4 格 */
    private static int bars(int rssi) {
        if (rssi >= -55) return 4;
        if (rssi >= -67) return 3;
        if (rssi >= -78) return 2;
        if (rssi >= -88) return 1;
        return 0;
    }
}
