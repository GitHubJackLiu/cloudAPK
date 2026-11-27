package com.xiaodu.voice;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
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
