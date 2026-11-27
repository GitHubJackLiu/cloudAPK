package com.xiaodu.voice;

import android.util.Log;

/**
 * Opus 编解码。
 *
 * 设备情况（实测）：
 *   · 系统 /system/lib/libopus.so 导出完整编码器符号
 *     （opus_encoder_create / opus_encode / opus_encoder_ctl 均在）
 *   · 但系统 MediaCodec 只有 OMX.google.opus.decoder，没有编码器
 *     → 不能走 MediaCodec 路线
 *   · NDK 自带 opus 稳定 API（API 21+），可用 externalNativeBuild 直接链系统库，
 *     不需要在 APK 里塞 .so
 *
 * 第一版（Phase 1）只做 AEC 通路验证，音频协议还没接上，
 * 这里先留空实现并明确报错，避免出现「连上了但不说话」的迷惑现象。
 * 第二版用 JNI 接系统 libopus 补齐。
 */
public class OpusCodec {

    private static final String TAG = "XzOpus";

    private OpusCodec() { }

    /** 是否具备编解码能力 */
    public static boolean available() {
        return false;
    }

    /**
     * 编码一帧 PCM16（16k 单声道）→ Opus。
     * @param pcm      PCM16 小端数据
     * @param samples  采样点数（960 = 60ms）
     */
    public static byte[] encode(byte[] pcm, int samples) {
        Log.w(TAG, "Opus encoder not linked yet (Phase 1: AEC 通路验证阶段)");
        return null;
    }

    /**
     * 解码一帧 Opus → PCM16。
     * @param outRate  输出采样率（服务端 hello 里给的，多为 24000）
     * @param outSamples 输出采样点数
     */
    public static byte[] decode(byte[] opus, int outRate, int outSamples) {
        Log.w(TAG, "Opus decoder not linked yet (Phase 1: AEC 通路验证阶段)");
        return null;
    }
}
