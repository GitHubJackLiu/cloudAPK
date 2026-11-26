package com.example.kiosk;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.Camera;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;

/** 硬件自检：喇叭 / 麦克风 / 摄像头，三个按钮点哪个测哪个。 */
public class MainActivity extends Activity {
    private TextView status;
    private SurfaceView camView;
    private Camera camera;
    private MediaRecorder recorder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(0x18, 0x18, 0x18));
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(17);
        status.setText("硬件自检\n点按钮开始（首次会弹权限，选允许）");
        root.addView(status, matchWrap());

        root.addView(button("① 测喇叭（1kHz 蜂鸣 2 秒）", new View.OnClickListener() {
            public void onClick(View v) { testSpeaker(); }
        }), matchWrap());

        root.addView(button("② 测麦克风（录 3 秒 → 回放）", new View.OnClickListener() {
            public void onClick(View v) { if (ensurePermissions()) testMic(); }
        }), matchWrap());

        root.addView(button("③ 测摄像头（开/关预览）", new View.OnClickListener() {
            public void onClick(View v) { if (ensurePermissions()) toggleCamera(); }
        }), matchWrap());

        camView = new SurfaceView(this);
        camView.setVisibility(View.GONE);
        camView.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder h) { applyPreview(h); }
            public void surfaceChanged(SurfaceHolder h, int f, int w, int ht) { }
            public void surfaceDestroyed(SurfaceHolder h) { }
        });
        FrameLayout frame = new FrameLayout(this);
        frame.addView(camView, new FrameLayout.LayoutParams(dp(480), dp(360)));
        root.addView(frame, matchWrap());

        setContentView(root);
        ensurePermissions();
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(12);
        return p;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

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
            status.setText(all ? "权限已授权，再点一次按钮" : "权限被拒——去系统设置里手动授权");
        }
    }

    // ---------- ① 喇叭：生成 1kHz 正弦波播放 ----------
    private void testSpeaker() {
        try {
            final int sr = 44100, secs = 2, total = sr * secs;
            final byte[] pcm = new byte[total * 2];
            for (int i = 0; i < total; i++) {
                short v = (short) (Math.sin(2 * Math.PI * 1000 * i / sr) * 12000);
                pcm[i * 2] = (byte) (v & 0xff);
                pcm[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
            }
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.length)
                    .setMode(AudioTrack.MODE_STATIC)
                    .build();
            track.write(pcm, 0, pcm.length);
            track.play();
            status.setText("喇叭响 2 秒（1kHz）——听到即正常");
        } catch (Exception e) {
            status.setText("喇叭失败：" + e.getMessage());
        }
    }

    // ---------- ② 麦克风：录 3 秒再回放 ----------
    private void testMic() {
        try {
            final File f = new File(getCacheDir(), "mictest.3gp");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.setOutputFile(f.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            status.setText("录音中… 3 秒，请对着麦克风说话");
            camView.postDelayed(new Runnable() {
                public void run() {
                    try { recorder.stop(); } catch (Exception ignored) { }
                    try { recorder.release(); } catch (Exception ignored) { }
                    recorder = null;
                    status.setText("回放中… 应听到刚才的声音");
                    try {
                        MediaPlayer mp = new MediaPlayer();
                        mp.setDataSource(f.getAbsolutePath());
                        mp.prepare();
                        mp.start();
                        mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                            public void onCompletion(MediaPlayer m) {
                                status.setText("完成——有回放 = 麦克风和喇叭都通");
                                m.release();
                            }
                        });
                    } catch (Exception e) {
                        status.setText("回放失败：" + e.getMessage());
                    }
                }
            }, 3000);
        } catch (Exception e) {
            status.setText("录音失败：" + e.getMessage());
        }
    }

    // ---------- ③ 摄像头：开/关预览 ----------
    private void toggleCamera() {
        if (camera != null) {
            camera.stopPreview();
            camera.release();
            camera = null;
            camView.setVisibility(View.GONE);
            status.setText("摄像头已关闭");
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
            camView.setVisibility(View.VISIBLE);
            status.setText("摄像头已开——应看到预览；再点一次关闭");
            SurfaceHolder h = camView.getHolder();
            if (h.getSurface() != null && h.getSurface().isValid()) applyPreview(h);
        } catch (Exception e) {
            status.setText("摄像头失败：" + e.getMessage());
            camera = null;
        }
    }

    private void applyPreview(SurfaceHolder h) {
        if (camera == null) return;
        try {
            camera.setPreviewDisplay(h);
            camera.startPreview();
        } catch (Exception e) {
            status.setText("预览失败：" + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (camera != null) { camera.stopPreview(); camera.release(); camera = null; }
        if (recorder != null) { try { recorder.stop(); } catch (Exception ignored) { } recorder.release(); recorder = null; }
    }
}
