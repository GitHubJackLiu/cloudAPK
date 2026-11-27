package com.xiaodu.voice;

import android.util.Log;

/**
 * Opus 编解码（NDK 静态编译 opus 1.3.1，见 app/src/main/cpp/）。
 *
 * 为什么自带一份 opus 而不是链系统库：
 *   · 系统 /system/lib/libopus.so 确实导出了完整编码器符号，
 *     但它不在 /system/etc/public.libraries.txt 白名单里，
 *     targetSdk>=24 的 app 直接 dlopen 会被 linker namespace 拒绝。
 *   · 自带源码静态链接，无版本/符号解析风险，且只编 armeabi-v7a 一档。
 *
 * 采样率约定：
 *   · 上行编码 16000Hz / 单声道 / 60ms 帧（960 采样），与采集一致。
 *   · 下行解码器也建在 16000Hz —— Opus 解码器支持任意标准输出采样率，
 *     服务端 24k 编码的流照样能直接解成 16k，省掉一层重采样，
 *     播放端 AudioTrack 也不用按 24k 重建。
 */
public class OpusCodec {

    private static final String TAG = "XzOpus";

    /** 上行/播放统一用 16k */
    public static final int RATE = 16000;
    public static final int FRAME_MS = 60;
    public static final int FRAME_SAMPLES = RATE * FRAME_MS / 1000;   // 960

    /** 一帧 opus 的最大字节数，60ms 帧留足余量 */
    private static final int MAX_PACKET = 1500;

    private static boolean loaded = false;
    private static long enc = 0;
    private static long dec = 0;
    private static final byte[] encOut = new byte[MAX_PACKET];
    private static final byte[] decOut = new byte[FRAME_SAMPLES * 2 * 2];

    static {
        try {
            System.loadLibrary("xzopus");
            loaded = true;
        } catch (Throwable t) {
            Log.e(TAG, "loadLibrary(xzopus) failed: " + t);
        }
    }

    private OpusCodec() { }

    /** 是否具备编解码能力 */
    public static synchronized boolean available() {
        if (!loaded) return false;
        if (enc == 0) {
            enc = nativeEncoderCreate(RATE, 1, 2048 /* OPUS_APPLICATION_VOIP */);
            if (enc == 0) return false;
        }
        if (dec == 0) {
            dec = nativeDecoderCreate(RATE, 1);
            if (dec == 0) return false;
        }
        return true;
    }

    /**
     * 编码一帧 PCM16（16k 单声道）。
     * @param pcm     PCM16 小端
     * @param samples 采样点数（960）
     * @return opus 数据，失败返回 null
     */
    public static synchronized byte[] encode(byte[] pcm, int samples) {
        if (!available()) return null;
        int n = nativeEncode(enc, pcm, samples, encOut, MAX_PACKET);
        if (n <= 0) return null;
        byte[] out = new byte[n];
        System.arraycopy(encOut, 0, out, 0, n);
        return out;
    }

    /**
     * 解码一帧 opus → PCM16（16k 单声道，最多 960 采样）。
     * 失败返回 null。
     */
    public static synchronized byte[] decode(byte[] opus) {
        if (!available() || opus == null) return null;
        int n = nativeDecode(dec, opus, opus.length, decOut, FRAME_SAMPLES);
        if (n <= 0) return null;
        byte[] out = new byte[n * 2];
        System.arraycopy(decOut, 0, out, 0, n * 2);
        return out;
    }

    /** 释放编解码器（退出时调用） */
    public static synchronized void release() {
        if (enc != 0) { nativeEncoderDestroy(enc); enc = 0; }
        if (dec != 0) { nativeDecoderDestroy(dec); dec = 0; }
    }

    // ---------------- native ----------------

    private static native long nativeEncoderCreate(int sampleRate, int channels, int application);
    private static native int nativeEncode(long handle, byte[] pcm, int samples,
                                           byte[] out, int maxBytes);
    private static native long nativeDecoderCreate(int sampleRate, int channels);
    private static native int nativeDecode(long handle, byte[] opusData, int len,
                                           byte[] out, int outSamples);
    private static native void nativeEncoderDestroy(long handle);
    private static native void nativeDecoderDestroy(long handle);
}
