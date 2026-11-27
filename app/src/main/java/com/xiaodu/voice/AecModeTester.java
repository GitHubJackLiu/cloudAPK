package com.xiaodu.voice;

import android.media.AudioManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * AEC 决定性实验：用 HAL 的 aec_mode 开关做同配置 A/B 对照。
 *
 * 前面那版「跨通路矩阵」的结论只能说明不同采集源的增益不同，
 * 没法回答真正的问题：**MTK 的硬件 AEC 到底有没有在抑制回声**。
 * 所以这里换一种测法：
 *
 *   固定同一套通路（VOICE_COMMUNICATION + VOICE_COMMUNICATION + MODE_IN_COMMUNICATION），
 *   只切 HAL 的 aec_mode 开关，其他一切不变，再测同一段 1kHz 残余回声：
 *
 *        ERLE = 残余电平(aec_mode=false) − 残余电平(aec_mode=true)
 *
 * 这样增益、AGC、NR 全部被控住了，差值就是 AEC 本身的抑制量。
 * 反序再测一遍（AEC 先开再关）用于排除「开关切换需要时间重新收敛」带来的顺序偏差。
 *
 * 开关来源：audio.primary.mt8167.so 的 AudioALSACaptureHandlerAEC::setParameters
 * 认 "aec_mode" = true/false，HAL 内部会置 mForceAECRec 并重新打开采集句柄。
 * 日志侧证据：logcat 出现 "app request aec_mode=true" / "mForceAECRec=1" /
 * "open AEC even record is already start"。
 */
public class AecModeTester {

    private static final String TAG = "XzAecMode";
    private static final int TONE_HZ = 1000;
    private static final int TONE_MS = 2000;
    private static final int TONE_AMP = 12000;   // 约 -8.7 dBFS

    public static class Result {
        public String name;
        public double floorDb = -120;   // 静默本底
        public double rawDb = -120;     // aec_mode=false 时的残余回声
        public double aecDb = -120;     // aec_mode=true  时的残余回声
        public double erleFwd = 0;      // 正序：raw − aec
        public double erleRev = 0;      // 反序：aec − raw
        public double erleAvg = 0;
        public double testEnableDb = -120;   // 叠加 AECREC_TEST_ENABLE=1 后的残余回声
        public String readBack = "";      // getParameters("aec_mode") 读回值
        public String note = "";
    }

    private final AudioEngine engine;
    private final AudioManager am;
    private volatile boolean cancel;

    public AecModeTester(AudioEngine e, AudioManager am) {
        this.engine = e;
        this.am = am;
    }

    public void cancel() { cancel = true; }

    /**
     * 跑一次完整 A/B。
     * 一次大约 25~35 秒。
     */
    public Result run(ProgressCb cb) {
        Result r = new Result();
        r.name = "A/B · VOICECOMM+VOICECOMM+COMM · aec_mode 开关对照";

        try {
            engine.setPath(AudioEngine.SRC_VOICE_COMM,
                    AudioEngine.USE_VOICE_COMM, AudioEngine.MODE_COMM);
            engine.setExclusive(true);
            engine.setCaptureOnly(false);   // 引擎必须自己开下行 track，echo reference 才绑得住
            if (!engine.start()) {
                r.note = "通路启动失败";
                if (cb != null) cb.onProgress(r);
                return r;
            }
            try {
                Thread.sleep(1500);   // 等 HAL 切到 speech 通路并建立 echo reference

                // ---- 本底 ----
                double f1 = engine.captureToneLevel(700, TONE_HZ);
                double f2 = engine.captureToneLevel(700, TONE_HZ);
                r.floorDb = Math.min(f1, f2);

                // ---- 正序：先关后开 ----
                engine.setHalAecMode(false);
                Thread.sleep(1200);
                double raw = measureEcho();
                r.rawDb = raw;

                engine.setHalAecMode(true);
                Thread.sleep(1200);
                double aec = measureEcho();
                r.aecDb = aec;
                r.erleFwd = raw - aec;

                // ---- 反序：先开后关（排除切换收敛造成的顺序偏差）----
                double aec2 = measureEcho();       // 保持 ON，再测一次看稳定性
                engine.setHalAecMode(false);
                Thread.sleep(1200);
                double raw2 = measureEcho();
                r.erleRev = aec2 - raw2;

                r.erleAvg = (r.erleFwd + r.erleRev) / 2.0;

                // ---- 读回参数，确认 HAL 收到了 ----
                r.readBack = engine.getHalParam("aec_mode");

                // ---- 再叠加 AECREC_TEST_ENABLE=1 这条独立路径 ----
                engine.setHalAecMode(true);
                engine.setHalParam("AECREC_TEST_ENABLE=1");
                Thread.sleep(1200);
                r.testEnableDb = measureEcho();
                Log.i(TAG, "AECREC_TEST_ENABLE=1 level="
                        + String.format("%.1f", r.testEnableDb));

                if (r.erleAvg > 6) {
                    r.note = String.format("AEC 生效：残余回声 %.1f → %.1f dBFS，抑制 %.1f dB",
                            r.rawDb, r.aecDb, r.erleAvg);
                } else {
                    r.note = String.format("抑制量仅 %.1f dB —— AEC 未真正工作", r.erleAvg);
                }
            } finally {
                engine.stop();
                engine.setExclusive(false);
                Thread.sleep(400);
            }
        } catch (Throwable t) {
            Log.e(TAG, "run failed", t);
            r.note = "异常 " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        if (cb != null) cb.onProgress(r);
        Log.i(TAG, String.format(
                "A/B | floor=%.1f raw=%.1f aec=%.1f erleFwd=%.1f erleRev=%.1f avg=%.1f | %s",
                r.floorDb, r.rawDb, r.aecDb, r.erleFwd, r.erleRev, r.erleAvg,
                r.note == null ? "" : r.note));
        return r;
    }

    /**
     * 放 1kHz 测试音并同期测残余回声的 1kHz 分量。
     * 采集在当前线程，播放在独立线程并发进行。
     */
    private double measureEcho() throws InterruptedException {
        final double[] echo = new double[1];
        Thread player = new Thread(new Runnable() {
            public void run() {
                engine.playToneOnEngineTrack(TONE_HZ, TONE_MS, TONE_AMP);
            }
        });
        player.start();
        Thread.sleep(350);
        echo[0] = engine.captureToneLevel(TONE_MS - 700, TONE_HZ);
        player.join(6000);
        return echo[0];
    }

    public interface ProgressCb {
        void onProgress(Result r);
    }
}
