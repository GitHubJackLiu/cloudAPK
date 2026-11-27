package com.xiaodu.voice;

import android.media.AudioManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * AEC 通路对比实验。
 *
 * 背景：此前用浏览器 getUserMedia 测过三条通路，ERLE 只有 0~4 dB，
 * 结论是「AEC 不可用」。但那个结论只覆盖了浏览器的 echoCancellation 开关，
 * 走的是 framework AudioEffect，而本机 audio_effects.conf 里 aec/ns/agc 全被注释，
 * 属于「通路选错」而非「硬件不支持」。
 *
 * 本机 MT8167 的 DSP 侧其实有完整 Speech AEC（audio.primary.mt8167.so 里的
 * AudioALSACaptureHandlerAEC / AudioALSACaptureDataProviderEchoRef）。
 * 要用它，必须让采集与播放同时处在 VOICE_COMMUNICATION 通路。
 *
 * 测量方法（重要）：
 *   跨通路比较绝对电平是不可靠的 —— 不同 input source 有不同的增益与 AGC，
 *   实测 VOICE_COMMUNICATION 通路的底噪比 MIC 通路高 30 dB。
 *   所以改成【组内自比】：
 *     · 播放 1kHz 测试音时采到的 1kHz 分量 = 「有声电平」
 *     · 静默时采到的 1kHz 分量      = 「无电平」（本底）
 *     · ERLE = 有声电平 − 无声电平
 *   同一条通路内部前后对比，增益差异被消掉，剩下的就是 AEC 的抑制量。
 *   数值越大说明回声被压得越干净。
 *
 * 通路矩阵：
 *   A. MIC        + MEDIA      + NORMAL   （= 此前浏览器等效通路，预期 ERLE≈0）
 *   B. MIC        + VOICE_COMM + COMM
 *   C. VOICE_COMM + VOICE_COMM + NORMAL
 *   D. VOICE_COMM + VOICE_COMM + COMM     （= 目标：DSP Speech AEC 全开）
 */
public class AecTester {

    private static final String TAG = "XzAec";
    private static final int TONE_HZ = 1000;
    private static final int TONE_MS = 2000;
    private static final int TONE_AMP = 12000;   // 约 -8.7 dBFS，留足余量避免削顶

    public static class Result {
        public String name;
        public int src, usage, mode;
        public double floorDb;      // 静默时 1kHz 分量（dBFS）
        public double toneDb;       // 放测试音时 1kHz 分量（dBFS）
        public double erle;         // toneDb - floorDb
        public String note;
    }

    private final AudioEngine engine;
    private final AudioManager am;
    private volatile boolean cancel;

    public AecTester(AudioEngine e, AudioManager am) {
        this.engine = e;
        this.am = am;
    }

    public void cancel() { cancel = true; }

    /** 跑完整矩阵 */
    public List<Result> runMatrix(final ProgressCb cb) {
        List<Result> out = new ArrayList<>();
        String[][] cases = {
                // 基线：MIC 采集，HAL 走普通 handler，没有语音 AEC
                {"A · MIC+MEDIA+NORMAL（基线·等效浏览器）", "null"},
                // MIC 采集 + 语音模式播放
                {"B · MIC+VOICECOMM+COMM", "null"},
                // 语音采集，HAL 走 AUDIO_SOURCE_VOICE_COMMUNICATION 分支
                {"C · VOICECOMM+VOICECOMM+NORMAL", "null"},
                // 语音采集 + 语音播放 + 通信模式（目标）
                {"D · VOICECOMM+VOICECOMM+COMM", "null"},
                // 语音采集 + 语音播放 + 通信模式，再跑一遍（复验稳定性）
                {"D2 · VOICECOMM+VOICECOMM+COMM（复验）", "null"},
                // VOICE_RECOGNITION 也是语音系（API 6）
                {"F · VOICERECOGN+VOICECOMM+COMM", "null"},
        };
        int[] srcs = {AudioEngine.SRC_MIC, AudioEngine.SRC_MIC,
                AudioEngine.SRC_VOICE_COMM, AudioEngine.SRC_VOICE_COMM,
                AudioEngine.SRC_VOICE_COMM, AudioEngine.SRC_VOICE_RECOG};
        int[] uses = {AudioEngine.USE_MEDIA, AudioEngine.USE_VOICE_COMM,
                AudioEngine.USE_VOICE_COMM, AudioEngine.USE_VOICE_COMM,
                AudioEngine.USE_VOICE_COMM, AudioEngine.USE_VOICE_COMM};
        int[] modes = {AudioEngine.MODE_NORMAL, AudioEngine.MODE_COMM,
                AudioEngine.MODE_NORMAL, AudioEngine.MODE_COMM,
                AudioEngine.MODE_COMM, AudioEngine.MODE_COMM};

        for (int i = 0; i < cases.length; i++) {
            if (cancel) break;
            Result r = runCase(cases[i][0], srcs[i], uses[i], modes[i], cb);
            out.add(r);
        }
        return out;
    }

    /**
     * 跑单个通路组合，组内自比测 ERLE。
     */
    private Result runCase(String name, int src, int usage, int mode, ProgressCb cb) {
        Result r = new Result();
        r.name = name;
        r.src = src;
        r.usage = usage;
        r.mode = mode;

        try {
            engine.setPath(src, usage, mode);
            // 独占采集：readLoop 必须让开，否则会瓜分 captureToneLevel 读到的数据
            engine.setExclusive(true);
            // 注意：不设 captureOnly —— 引擎必须同时开自己的 AudioTrack，
            // HAL 的 echo reference 绑定在「采集的 AudioRecord + 引擎的 AudioTrack」
            // 这一对对象上，另开 track 拿不到正确的参考。
            if (!engine.start()) {
                r.note = "通路启动失败";
                engine.setExclusive(false);
                return r;
            }
            try {
                Thread.sleep(1500);   // 等 HAL 切到 speech 通路并建立 echo reference

                // ---- ① 无声本底：连续采两段，确认通路确实在工作 ----
                double f1 = engine.captureToneLevel(700, TONE_HZ);
                double f2 = engine.captureToneLevel(700, TONE_HZ);
                r.floorDb = Math.min(f1, f2);   // 取更安静的一段做本底

                // ---- ② 放 1kHz，同期采集残余回声 ----
                // 采集在主测试线程，播放在独立线程并发进行
                final double[] echo = new double[1];
                Thread player = new Thread(new Runnable() {
                    public void run() { engine.playToneOnEngineTrack(TONE_HZ, TONE_MS, TONE_AMP); }
                });
                player.start();
                Thread.sleep(350);                       // 等播放真正开始
                echo[0] = engine.captureToneLevel(TONE_MS - 700, TONE_HZ);
                player.join(6000);

                r.toneDb = echo[0];
                r.erle = r.toneDb - r.floorDb;

                // 若有声电平不高于本底，说明根本没采到测试音（播放失败或麦克风没在工作）
                if (r.erle < 3) {
                    r.note = "有声电平未明显高于本底 → 回声极弱或测试音没播出来";
                } else {
                    r.note = String.format("本底 %.1f → 有声 %.1f dBFS", r.floorDb, r.toneDb);
                }            } finally {
                engine.stop();
                engine.setExclusive(false);
                Thread.sleep(500);
            }
        } catch (Throwable t) {
            Log.e(TAG, "case failed", t);
            r.note = "异常 " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        if (cb != null) cb.onProgress(name, r);
        Log.i(TAG, String.format("%s | floor=%.1f tone=%.1f ERLE=%.1f | %s",
                name, r.floorDb, r.toneDb, r.erle, r.note == null ? "" : r.note));
        return r;
    }

    public interface ProgressCb {
        void onProgress(String name, Result r);
    }
}
