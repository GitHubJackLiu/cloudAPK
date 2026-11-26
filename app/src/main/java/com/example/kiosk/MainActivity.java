package com.example.kiosk;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.media.AudioManager;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import java.io.File;

/**
 * HTML UI + JS Bridge 架构：
 * - 界面全部在 assets/index.html（浏览器可实时预览）
 * - 硬件功能由本类注入的 Native 对象提供，网页 JS 调用
 * - 状态通过 evaluateJavascript 回写到网页
 */
public class MainActivity extends Activity {
    private WebView webView;
    private SurfaceView camView;
    private Camera camera;
    private MediaRecorder recorder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 根容器：WebView 打底，摄像头 SurfaceView 叠加其上
        FrameLayout root = new FrameLayout(this);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        camView = new SurfaceView(this);
        camView.setVisibility(ViewGroup.GONE);
        camView.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder h) { applyPreview(h); }
            public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) { }
            public void surfaceDestroyed(SurfaceHolder h) { }
        });
        FrameLayout.LayoutParams camLp = new FrameLayout.LayoutParams(dp(360), dp(270));
        camLp.topMargin = dp(300);
        camLp.leftMargin = dp(20);
        root.addView(camView, camLp);

        setContentView(root);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        // 让 file:// 页面能加载 assets 里的脚本（同源）
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);

        // 注入 Bridge：网页里用 Native.xxx() 调用
        webView.addJavascriptInterface(new Bridge(), "Native");

        webView.loadUrl("file:///android_asset/index.html");
        ensurePermissions();
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    // ---------- JS Bridge ----------
    public class Bridge {
        @JavascriptInterface
        public void testSpeaker() {
            runOnUiThread(new Runnable() { public void run() { call("status", "喇叭响 2 秒（1kHz）——听到即正常"); } });
            doSpeaker();
        }

        @JavascriptInterface
        public void testMic() {
            if (!ensurePermissions()) { call("status", "正在申请权限，请允许后再点一次"); return; }
            call("status", "录音中… 3 秒，请对着麦克风说话");
            doMic();
        }

        @JavascriptInterface
        public void toggleCamera() {
            if (!ensurePermissions()) { call("status", "正在申请权限，请允许后再点一次"); return; }
            runOnUiThread(new Runnable() { public void run() { doCamera(); } });
        }
    }

    /** 调用网页里的 JS 函数，把状态/视图变化写回 HTML */
    private void call(final String func, final String arg) {
        runOnUiThread(new Runnable() {
            public void run() {
                String safe = arg.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
                webView.evaluateJavascript(func + "('" + safe + "')", null);
            }
        });
    }

    // ---------- ① 喇叭 ----------
    private void doSpeaker() {
        try {
            final int sr = 44100, secs = 2, total = sr * secs;
            final byte[] pcm = new byte[total * 2];
            for (int i = 0; i < total; i++) {
                short v = (short) (Math.sin(2 * Math.PI * 1000 * i / sr) * 12000);
                pcm[i * 2] = (byte) (v & 0xff);
                pcm[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
            }
            AudioTrack track = new AudioTrack(
                    AudioManager.STREAM_MUSIC, sr,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    pcm.length, AudioTrack.MODE_STATIC);
            track.write(pcm, 0, pcm.length);
            track.play();
        } catch (Exception e) {
            call("status", "喇叭失败：" + e.getMessage());
        }
    }

    // ---------- ② 麦克风 ----------
    private void doMic() {
        try {
            final File f = new File(getCacheDir(), "mictest.3gp");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.setOutputFile(f.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            camView.postDelayed(new Runnable() {
                public void run() {
                    try { recorder.stop(); } catch (Exception ignored) { }
                    try { recorder.release(); } catch (Exception ignored) { }
                    recorder = null;
                    call("status", "回放中… 应听到刚才的声音");
                    try {
                        MediaPlayer mp = new MediaPlayer();
                        mp.setDataSource(f.getAbsolutePath());
                        mp.prepare();
                        mp.start();
                        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                            public void onCompletion(MediaPlayer m) {
                                call("status", "完成——有回放 = 麦克风和喇叭都通");
                                m.release();
                            }
                        });
                    } catch (Exception e) { call("status", "回放失败：" + e.getMessage()); }
                }
            }, 3000);
        } catch (Exception e) {
            call("status", "录音失败：" + e.getMessage());
        }
    }

    // ---------- ③ 摄像头 ----------
    private void doCamera() {
        if (camera != null) {
            camera.stopPreview();
            camera.release();
            camera = null;
            camView.setVisibility(ViewGroup.GONE);
            call("showCam", "false");
            call("status", "摄像头已关闭");
            return;
        }
        try {
            int id = 0;
            int n = Camera.getNumberOfCameras();
            for (int i = 0; i < n; i++) {
                Camera.CameraInfo info = new Camera.CameraInfo();
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) { id = i; break; }
            }
            camera = Camera.open(id);
            camera.setDisplayOrientation(90);
            camView.setVisibility(ViewGroup.VISIBLE);
            call("showCam", "true");
            call("status", "摄像头已开——应看到预览；再点一次关闭");
            SurfaceHolder h = camView.getHolder();
            if (h.getSurface() != null && h.getSurface().isValid()) applyPreview(h);
        } catch (Exception e) {
            call("status", "摄像头失败：" + e.getMessage());
            camera = null;
        }
    }

    private void applyPreview(SurfaceHolder h) {
        if (camera == null) return;
        try {
            camera.setPreviewDisplay(h);
            camera.startPreview();
        } catch (Exception e) {
            call("status", "预览失败：" + e.getMessage());
        }
    }

    // ---------- 权限 ----------
    private boolean ensurePermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            boolean need = false;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) need = true;
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) need = true;
            if (need) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA}, 1);
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grant) {
        super.onRequestPermissionsResult(code, perms, grant);
        if (code == 1) {
            boolean all = true;
            for (int g : grant) if (g != PackageManager.PERMISSION_GRANTED) all = false;
            call("status", all ? "权限已授权，再点一次按钮" : "权限被拒——去系统设置里手动授权");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (camera != null) { camera.stopPreview(); camera.release(); camera = null; }
        if (recorder != null) { try { recorder.stop(); } catch (Exception ignored) { } recorder.release(); recorder = null; }
    }
}
