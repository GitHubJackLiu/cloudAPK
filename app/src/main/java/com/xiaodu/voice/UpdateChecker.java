package com.xiaodu.voice;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * GitHub Releases 更新检查（第 3 步：只查不装，结果先打日志）。
 *
 * 数据源用公开仓库的 latest release：
 *   https://api.github.com/repos/GitHubJackLiu/cloudAPK/releases/latest
 * 返回 200 + JSON（tag_name / assets[].browser_download_url）；
 * 仓库还没有任何 Release 时返回 404 —— 视为「暂无版本」，不是错误。
 *
 * 版本换算与 CI 保持一致：tag v1.2 -> versionCode 102（major*100+minor）。
 * 本地版本从 PackageManager 读实际安装的 versionCode，不用写死的常量。
 *
 * 所有请求都在调用方提供的后台线程执行；本类不做线程切换、不碰 UI。
 */
public class UpdateChecker {
    private static final String TAG = "XzUpdate";

    /** 仓库 latest release API */
    private static final String API_LATEST =
            "https://api.github.com/repos/GitHubJackLiu/cloudAPK/releases/latest";

    private final Context ctx;

    public UpdateChecker(Context ctx) {
        this.ctx = ctx;
    }

    /** 当前安装版本（versionCode / versionName） */
    public int localCode() {
        try {
            PackageInfo p = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return p.versionCode;
        } catch (Throwable t) {
            return -1;
        }
    }

    public String localName() {
        try {
            PackageInfo p = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return p.versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    /** tag "v1.2" -> 102；与 build.yml 的 major*100+minor 完全一致 */
    public static int codeOfTag(String tag) {
        if (tag == null) return -1;
        String t = tag.trim().toLowerCase();
        if (t.startsWith("v")) t = t.substring(1);
        String[] parts = t.split("\\.");
        if (parts.length < 2) return -1;
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            return major * 100 + minor;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 同步检查，调用方必须在后台线程。
     * 返回 JSON：{ok, hasUpdate, localCode, localName, remoteCode, tag, name,
     *             url, size, publishedAt, err}
     */
    public JSONObject check() {
        JSONObject out = new JSONObject();
        int local = localCode();
        try {
            out.put("localCode", local);
            out.put("localName", localName());
        } catch (Exception ignored) { }

        try {
            HttpURLConnection c = (HttpURLConnection) new URL(API_LATEST).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(12000);
            c.setRequestMethod("GET");
            // GitHub API 强制要求 User-Agent，缺了会被 403（Python/浏览器默认带，Java 不带）
            c.setRequestProperty("User-Agent", "xiaodu-voice-updater/1.0");
            c.setRequestProperty("Accept", "application/vnd.github+json");

            int code = c.getResponseCode();
            if (code == 404) {
                put(out, "ok", true);
                put(out, "hasUpdate", false);
                put(out, "reason", "no_release");
                Log.i(TAG, "check: no release yet (404), localCode=" + local);
                c.disconnect();
                return out;
            }

            String resp = readAll(code >= 200 && code < 400
                    ? c.getInputStream() : c.getErrorStream());
            c.disconnect();

            if (code < 200 || code >= 300) {
                put(out, "ok", false);
                put(out, "status", code);
                put(out, "err", "HTTP " + code + " " + clip(resp, 120));
                Log.w(TAG, "check: HTTP " + code + " " + clip(resp, 120));
                return out;
            }

            JSONObject rel = new JSONObject(resp);
            String tag = rel.optString("tag_name", "");
            int remote = codeOfTag(tag);
            String url = "";
            long size = 0;
            JSONArray assets = rel.optJSONArray("assets");
            if (assets != null && assets.length() > 0) {
                JSONObject a = assets.optJSONObject(0);
                if (a != null) {
                    url = a.optString("browser_download_url", "");
                    size = a.optLong("size", 0);
                }
            }
            boolean has = remote > local && remote > 0;

            put(out, "ok", true);
            put(out, "hasUpdate", has);
            put(out, "remoteCode", remote);
            put(out, "tag", tag);
            put(out, "name", rel.optString("name", ""));
            put(out, "publishedAt", rel.optString("published_at", ""));
            put(out, "url", url);
            put(out, "size", size);

            Log.i(TAG, "check: local=" + local + " remote=" + remote
                    + " tag=" + tag + " hasUpdate=" + has
                    + " url=" + url + " size=" + size);
            return out;
        } catch (Throwable t) {
            try {
                out.put("ok", false);
                out.put("err", t.getClass().getSimpleName() + ": " + t.getMessage());
            } catch (Exception ignored) { }
            Log.w(TAG, "check failed: " + t);
            return out;
        }
    }

    // ---------------- 下载 + 静默安装（第 4 步） ----------------

    /** 下载进度回调：phase=downloading/installing/done, pct 0~100 */
    public interface Progress {
        void on(int pct, String phase, String msg);
    }

    /**
     * 下载 APK 到内存，经 root 的 stdin 管道写到 /sdcard/Download，再静默安装。
     * 必须在后台线程调用。
     *
     * 为什么这么绕（实测踩坑）：
     *   1. App 无权写 /sdcard/Download（Manifest 没有 WRITE_EXTERNAL_STORAGE，
     *      Android 8.1 下 FileOutputStream 直接 Permission denied）。
     *   2. root 也读不到 App 私有目录 /data/data/com.xiaodu.voice/*（Magisk SELinux 隔离）。
     *   3. 所以走 stdin 管道：App 下载到内存 byte[]，起 su -c "cat > 公共路径"，
     *      把字节流写进该进程 stdin —— 双方都不需要访问对方的文件系统。
     *   这正是 PC 通过 SSH 传 APK 到设备的原理。
     *
     * @param progress 进度回调，可为 null
     * @return {ok, installed, err, path, size}
     */
    public JSONObject downloadAndInstall(String url, long expectedSize, Progress progress) {
        JSONObject out = new JSONObject();
        final String path = "/sdcard/Download/xz-update.apk";
        try {
            if (progress != null) progress.on(0, "downloading", "开始下载…");

            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", "xiaodu-voice-updater/1.0");
            int code = c.getResponseCode();
            if (code != 200) {
                put(out, "ok", false);
                put(out, "err", "download HTTP " + code);
                Log.w(TAG, "download HTTP " + code);
                return out;
            }

            // 下载到内存（APK 约 0.5MB，直接驻留内存无压力）
            long total = c.getContentLength();
            InputStream is = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n, got = 0;
            int lastPct = -1;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
                got += n;
                if (total > 0 && progress != null) {
                    int pct = (int) (got * 100 / total);
                    if (pct != lastPct) { progress.on(pct, "downloading", "下载中"); lastPct = pct; }
                }
            }
            is.close();
            c.disconnect();
            byte[] apkBytes = bos.toByteArray();

            long size = apkBytes.length;
            put(out, "size", size);
            put(out, "path", path);
            if (size <= 0) {
                put(out, "ok", false);
                put(out, "err", "empty download");
                return out;
            }
            // 大小校验：Release API 给了 assets[0].size，对不上说明下错/被截断
            if (expectedSize > 0 && size != expectedSize) {
                put(out, "ok", false);
                put(out, "err", "size mismatch " + size + " != " + expectedSize);
                Log.w(TAG, "size mismatch " + size + " != " + expectedSize);
                return out;
            }

            if (progress != null) progress.on(100, "installing", "正在安装…");
            // 通过 stdin 把字节流喂给 root 的 cat，落到公共路径
            String writeRes = suFeed("cat > " + path, apkBytes);
            Log.i(TAG, "write apk: " + clip(writeRes, 120));
            if (writeRes != null && writeRes.toLowerCase().contains("permission denied")) {
                put(out, "ok", false);
                put(out, "err", "write: " + clip(writeRes, 160));
                return out;
            }

            // 拉起交给 BootReceiver 的 ACTION_MY_PACKAGE_REPLACED：
            // pm install -r 覆盖安装会立刻杀掉本进程（codePath changed），
            // 进程内任何拉起代码都是死代码（setsid/am start 实测无效）。
            // 系统装完会主动发 MY_PACKAGE_REPLACED 广播拉起进程，由它 startActivity。

            String res = su("pm install -r " + path);
            Log.i(TAG, "pm install: " + clip(res, 200));
            boolean ok = res != null && res.toLowerCase().contains("success");
            put(out, "ok", ok);
            put(out, "installed", ok);
            if (!ok) put(out, "err", "install: " + clip(res, 160));
            return out;
        } catch (Throwable t) {
            put(out, "ok", false);
            put(out, "err", t.getClass().getSimpleName() + ": " + t.getMessage());
            Log.w(TAG, "downloadAndInstall failed: " + t);
            return out;
        }
    }

    /** 执行 su -c 命令并把 data 写进其 stdin，返回合并输出（stdout+stderr） */
    private String suFeed(String cmd, byte[] data) {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            OutputStream os = p.getOutputStream();
            os.write(data);
            os.flush();
            os.close();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            while ((line = er.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
        } catch (Throwable t) {
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) { }
        }
        return sb.toString();
    }

    /** 执行 su -c 命令，返回合并输出（stdout+stderr） */
    private String su(String cmd) {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            BufferedReader er = new BufferedReader(new InputStreamReader(p.getErrorStream()));
            while ((line = er.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
        } catch (Throwable t) {
            sb.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) { }
        }
        return sb.toString();
    }

    private static void put(JSONObject o, String k, Object v) {
        try { o.put(k, v); } catch (Exception ignored) { }
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= n ? s : s.substring(0, n);
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
}
