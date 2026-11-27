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
 * 测法（与此前浏览器 aec3.html 保持一致，便于对比）：
 *   1) 放一段 1kHz 正弦（远场模拟「小智在说话」）
 *   2) 同期采集
 *   3) 用 Goertzel 取采集信号的 1kHz 分量幅度 = 残余回声
 *   4) ERLE = 参考电平 - 残余电平（dB）
 *      参考电平 = 同样音量下、AEC 不介入时的 1kHz 分量
 *
 * 通路矩阵：
 *   A. MIC      + MEDIA      + NORMAL   （= 此前浏览器等效通路，预期 ERLE≈0）
 *   B. MIC      + VOICE_COMM + COMM
 *   C. VOICE_COMM + VOICE_COMM + NORMAL
 *   D. VOICE_COMM + VOICE_COMM + COMM     （= 目标：DSP Speech AEC 全开）
 */
public class AecTester {

    private static final String TAG = "XzAec";
    private static final int TONE_HZ = 1000;
    private static final int TONE_MS = 2000;
    private static final int TONE_AMP = 8000;   // 约 -12 dBFS，留足余量避免削顶

    public static class Result {
        public String name;
        public int src, usage, mode;
        public double refDb;      // AEC 不介入时的 1kHz 参考电平
        public double echoDb;     // 残余回声电平
        public double erle;
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

    /** 跑完整矩阵，返回每一项的结果 */
    public List<Result> runMatrix(final ProgressCb cb) {
        List<Result> out = new ArrayList<>();
        // 参考基线：MIC 采集 + MEDIA 播放 + NORMAL —— 此通路没有 AEC，
        // 采到的就是「未消除的回声」，作为 ERLE 的参考电平。
        Result ref = runCase("A · MIC+MEDIA+NORMAL（基线·无AEC）",
                AudioEngine.SRC_MIC, AudioEngine.USE_MEDIA,
                AudioEngine.MODE_NORMAL, null, cb);
        out.add(ref);

        String[][] cases = {
                {"B · MIC+VOICECOMM+COMM", "mic_comm"},
                {"C · VOICECOMM+VOICECOMM+NORMAL", "vc_normal"},
                {"D · VOICECOMM+VOICECOMM+COMM（目标）", "vc_comm"},
        };
        int[] srcs = {AudioEngine.SRC_MIC, AudioEngine.SRC_VOICE_COMM, AudioEngine.SRC_VOICE_COMM};
        int[] uses = {AudioEngine.USE_VOICE_COMM, AudioEngine.USE_VOICE_COMM, AudioEngine.USE_VOICE_COMM};
        int[] modes = {AudioEngine.MODE_COMM, AudioEngine.MODE_NORMAL, AudioEngine.MODE_COMM};

        for (int i = 0; i < cases.length; i++) {
            if (cancel) break;
            Result r = runCase(cases[i][0], srcs[i], uses[i], modes[i], ref, cb);
            out.add(r);
        }
        return out;
    }

    /**
     * 跑单个通路组合。
     * ref 非空时用它当 ERLE 参考；为 null 则本次结果只作为参考基线。
     */
    private Result runCase(String name, int src, int usage, int mode,
                           Result ref, ProgressCb cb) {
        Result r = new Result();
        r.name = name;
        r.src = src;
        r.usage = usage;
        r.mode = mode;

        try {
            // 先建立通路（HAL 需要时间切到 speech 通路并建立 echo reference）
            engine.setPath(src, usage, mode);
            // 独占采集：readLoop 必须让开，否则会瓜分 captureToneLevel 读到的数据
            engine.setExclusive(true);
            // 只开采集：避免与下行 track 争抢 echo reference 绑定
            engine.setCaptureOnly(true);
            if (!engine.start()) {
                r.note = "通路启动失败";
                engine.setExclusive(false);
                engine.setCaptureOnly(false);
                return r;
            }
            try {
                Thread.sleep(1200);   // 等通路稳定

                // 采一段「无人说话」的底噪，确认通路确实在工作
                double floor = engine.captureToneLevel(600, TONE_HZ);
                r.note = "底噪 " + String.format("%.1f", floor) + " dBFS";

                // 放 1kHz，同期采集残余回声
                // 采集线程与放音并发：captureToneLevel 读的是 record，
                // 播放在另一个线程进行，用 sleep 对齐。
                final double[] echo = new double[1];
                Thread player = new Thread(() -> engine.playTone(TONE_HZ, TONE_MS, TONE_AMP));
                player.start();
                Thread.sleep(300);                    // 等播放真正开始
                echo[0] = engine.captureToneLevel(TONE_MS - 600, TONE_HZ);
                player.join(4000);

                r.echoDb = echo[0];
                if (ref != null) {
                    r.refDb = ref.refDb;
                    r.erle = ref.refDb - r.echoDb;
                } else {
                    // 基线自身：ref = 自己的残余回声
                    r.refDb = r.echoDb;
                    r.erle = 0;
                }
            } finally {
                engine.stop();
                engine.setExclusive(false);
                engine.setCaptureOnly(false);
                Thread.sleep(400);
            }
        } catch (Throwable t) {
            Log.e(TAG, "case failed", t);
            r.note = "异常 " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        if (cb != null) cb.onProgress(name, r);
        Log.i(TAG, String.format("%s | ref=%.1f echo=%.1f ERLE=%.1f | %s",
                name, r.refDb, r.echoDb, r.erle, r.note == null ? "" : r.note));
        return r;
    }

    public interface ProgressCb {
        void onProgress(String name, Result r);
    }
}
