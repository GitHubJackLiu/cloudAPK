package com.xiaodu.voice;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * AEC 第二轮实验：围绕「回声参考信号从哪来」做变量矩阵。
 *
 * 第一轮已经查清的事实（MT8167 / 小度 1C）：
 *
 *   1) 只有 MediaRecorder.AudioSource.VOICE_RECOGNITION(6) 会让 HAL 走
 *      AudioALSACaptureHandlerAEC + VoIP 场景（categoryPath =
 *      "Profile,Hands-free no 1mic NR"、bVoIPEnable=1、voip_on=1）。
 *      VOICE_COMMUNICATION(7) 反而走普通 handler + "Handset" 场景，不是 AEC 通路。
 *
 *   2) VoIP 场景的 MagiAEC 参数本来就是全开的
 *      （voip_mode_para uint[1]=224：bit8 AEC=0(on)、bit0 Handfree、
 *        bit1 ES=0(on)、bit10 FirstES=0、bit11 NLP=0、bit14 AES=0）。
 *      所以不是参数没开的问题。
 *
 *   3) 采集侧的 SPELayer 真的起来了（"Start, SWIP ver = 10.2"），
 *      它内部有 mDLInBufferQ / mDLDelayBufferQ —— 下行参考就该从这里进。
 *
 *   4) 但下行播放侧永远是 AudioALSAPlaybackHandlerNormal，
 *      从来没出现 AudioALSAPlaybackHandlerWithSphEn / Play_PCM_With_SpeechEnhance_Routine。
 *      下行 PCM 进不了 SPELayer ⇒ 回声参考从源头就断了 ⇒ AEC 无从下手。
 *
 * 所以这轮要试的是「怎样让下行走进语音增强通路」：
 *   · 采集源换成 6（唯一进 VoIP 场景的源）
 *   · track 与 record 的开启顺序（HAL 建下行 handler 时会看当前有没有 VoIP 采集流）
 *   · 下行是否必须钉在内置扬声器（本机没有 Telecom 服务，setSpeakerphoneOn(true) 是空的）
 *   · 下行 usage 换成 VOICE_ASSISTANCE
 *   · 以及非通信模式 / source=7 两组作为对照
 *
 * 判定标准：
 *   · logcat 里出现 AudioALSAPlaybackHandlerWithSphEn 或 Play_PCM_With_SpeechEnhance_Routine
 *     ⇒ 找到开关了；
 *   · 否则看残余回声电平是否明显低于对照组的 ~-54 dBFS。
 */
public class AecModeTester {

    private static final String TAG = "XzAecMode";
    private static final int TONE_HZ = 1000;
    private static final int TONE_MS = 2000;
    private static final int TONE_AMP = 12000;   // 约 -8.7 dBFS

    public static class Result {
        public String name;
        public int src, usage, mode;
        public boolean trackFirst, prefSpeaker;
        public double floorDb = -120;   // 静默本底
        public double toneDb = -120;    // 放测试音时的残余回声
        public String note = "";
    }

    private final AudioEngine engine;
    private volatile boolean cancel;

    public AecModeTester(AudioEngine e) {
        this.engine = e;
    }

    public void cancel() { cancel = true; }

    /** 一组配置 */
    private static class Cfg {
        final String name; final int src, usage, mode;
        final boolean trackFirst, prefSpeaker;
        Cfg(String name, int src, int usage, int mode, boolean trackFirst, boolean prefSpeaker) {
            this.name = name; this.src = src; this.usage = usage; this.mode = mode;
            this.trackFirst = trackFirst; this.prefSpeaker = prefSpeaker;
        }
    }

    public List<Result> runMatrix(ProgressCb cb) {
        List<Result> out = new ArrayList<>();
        List<Cfg> cases = new ArrayList<>();
        // 主攻：source=6（唯一进 VoIP/AEC 场景的采集源）
        cases.add(new Cfg("P1 · src6 + VOICECOMM + COMM + record先开",
                AudioEngine.SRC_VOICE_RECOG, AudioEngine.USE_VOICE_COMM,
                AudioEngine.MODE_COMM, false, false));
        cases.add(new Cfg("P2 · P1 + 钉内置扬声器",
                AudioEngine.SRC_VOICE_RECOG, AudioEngine.USE_VOICE_COMM,
                AudioEngine.MODE_COMM, false, true));
        cases.add(new Cfg("P3 · P2 + track先开（让HAL先看到VoIP采集流）",
                AudioEngine.SRC_VOICE_RECOG, AudioEngine.USE_VOICE_COMM,
                AudioEngine.MODE_COMM, true, true));
        cases.add(new Cfg("P4 · src6 + VOICE_ASSISTANCE + COMM + 钉扬声器",
                AudioEngine.SRC_VOICE_RECOG, AudioEngine.USE_VOICE_ASSIST,
                AudioEngine.MODE_COMM, false, true));
        cases.add(new Cfg("P5 · src6 + VOICECOMM + NORMAL（脱离通信模式）",
                AudioEngine.SRC_VOICE_RECOG, AudioEngine.USE_VOICE_COMM,
                AudioEngine.MODE_NORMAL, false, false));
        cases.add(new Cfg("E0 · src7 + VOICECOMM + COMM（对照组：已知无AEC通路）",
                AudioEngine.SRC_VOICE_COMM, AudioEngine.USE_VOICE_COMM,
                AudioEngine.MODE_COMM, false, true));

        for (Cfg c : cases) {
            if (cancel) break;
            out.add(runCase(c, cb));
        }
        return out;
    }

    private Result runCase(Cfg c, ProgressCb cb) {
        Result r = new Result();
        r.name = c.name;
        r.src = c.src; r.usage = c.usage; r.mode = c.mode;
        r.trackFirst = c.trackFirst; r.prefSpeaker = c.prefSpeaker;
        try {
            engine.setPath(c.src, c.usage, c.mode);
            engine.setTrackFirst(c.trackFirst);
            engine.setPrefSpeaker(c.prefSpeaker);
            engine.setExclusive(true);
            engine.setCaptureOnly(false);
            if (!engine.start()) {
                r.note = "通路启动失败";
                if (cb != null) cb.onProgress(r);
                return r;
            }
            try {
                Thread.sleep(1500);   // 等 HAL 切到 speech 通路并建立 echo reference

                double f1 = engine.captureToneLevel(700, TONE_HZ);
                double f2 = engine.captureToneLevel(700, TONE_HZ);
                r.floorDb = Math.min(f1, f2);

                r.toneDb = measureEcho();
                if (r.toneDb - r.floorDb < 3) {
                    r.note = "有声电平未明显高于本底 → 测试音没播出来或麦克风没工作";
                } else {
                    r.note = String.format("本底 %.1f → 残余回声 %.1f dBFS",
                            r.floorDb, r.toneDb);
                }
            } finally {
                engine.stop();
                engine.setExclusive(false);
                Thread.sleep(600);
            }
        } catch (Throwable t) {
            Log.e(TAG, "case failed", t);
            r.note = "异常 " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        if (cb != null) cb.onProgress(r);
        Log.i(TAG, String.format("%s | src=%d use=%d mode=%d tf=%d ps=%d | floor=%.1f 残余=%.1f | %s",
                r.name, r.src, r.usage, r.mode, r.trackFirst ? 1 : 0, r.prefSpeaker ? 1 : 0,
                r.floorDb, r.toneDb, r.note == null ? "" : r.note));
        return r;
    }

    /** 放 1kHz 测试音并同期测残余回声的 1kHz 分量 */
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
