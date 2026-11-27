/*
 * opus 的 JNI 薄封装。
 *
 * 设计要点：
 *   · 编解码器句柄用 long 回传给 Java，Java 侧持有，不在这里做单例。
 *   · 解码器直接按 16kHz 创建。Opus 解码器支持任意输出采样率
 *     （8/12/16/24/48k），所以服务端用 24k 编码的流也能直接解成 16k，
 *     省掉一层重采样，也避免 AudioTrack 又要重建。
 *   · 帧长：上行 16k/60ms = 960 采样；下行 24k 编码的 60ms 帧解成
 *     16k 后同样是 960 采样。
 */
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <android/log.h>
#include <opus.h>

#define TAG "XzOpusJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

JNIEXPORT jlong JNICALL
Java_com_xiaodu_voice_OpusCodec_nativeEncoderCreate(JNIEnv *env, jclass c,
                                                    jint sampleRate, jint channels,
                                                    jint application) {
    int err = 0;
    OpusEncoder *enc = opus_encoder_create(sampleRate, channels, application, &err);
    if (err != OPUS_OK || enc == NULL) {
        LOGE("encoder_create failed: %d", err);
        return 0;
    }
    /* 上行语音：24kbps 对 16k 单声道足够，且比默认 32k 省一半带宽。 */
    opus_encoder_ctl(enc, OPUS_SET_BITRATE(24000));
    opus_encoder_ctl(enc, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(enc, OPUS_SET_PACKET_LOSS_PERC(10));
    opus_encoder_ctl(enc, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(enc, OPUS_SET_COMPLEXITY(8));
    return (jlong) (intptr_t) enc;
}

JNIEXPORT jint JNICALL
Java_com_xiaodu_voice_OpusCodec_nativeEncode(JNIEnv *env, jclass c, jlong handle,
                                             jbyteArray pcm, jint samples,
                                             jbyteArray out, jint maxBytes) {
    OpusEncoder *enc = (OpusEncoder *) (intptr_t) handle;
    if (enc == NULL) return -1;

    jsize pcmLen = (*env)->GetArrayLength(env, pcm);
    if (pcmLen < samples * 2) return -2;

    jbyte *p = (*env)->GetByteArrayElements(env, pcm, NULL);
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    if (p == NULL || o == NULL) {
        if (p) (*env)->ReleaseByteArrayElements(env, pcm, p, JNI_ABORT);
        if (o) (*env)->ReleaseByteArrayElements(env, out, o, JNI_ABORT);
        return -3;
    }

    int n = opus_encode(enc, (const opus_int16 *) p, samples,
                        (unsigned char *) o, maxBytes);

    (*env)->ReleaseByteArrayElements(env, pcm, p, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, o, 0);

    if (n < 0) {
        LOGE("opus_encode failed: %d", n);
        return n;
    }
    return n;
}

JNIEXPORT jlong JNICALL
Java_com_xiaodu_voice_OpusCodec_nativeDecoderCreate(JNIEnv *env, jclass c,
                                                    jint sampleRate, jint channels) {
    int err = 0;
    OpusDecoder *dec = opus_decoder_create(sampleRate, channels, &err);
    if (err != OPUS_OK || dec == NULL) {
        LOGE("decoder_create failed: %d", err);
        return 0;
    }
    return (jlong) (intptr_t) dec;
}

JNIEXPORT jint JNICALL
Java_com_xiaodu_voice_OpusCodec_nativeDecode(JNIEnv *env, jclass c, jlong handle,
                                             jbyteArray opusData, jint len,
                                             jbyteArray out, jint outSamples) {
    OpusDecoder *dec = (OpusDecoder *) (intptr_t) handle;
    if (dec == NULL) return -1;

    jbyte *d = (*env)->GetByteArrayElements(env, opusData, NULL);
    jbyte *o = (*env)->GetByteArrayElements(env, out, NULL);
    if (d == NULL || o == NULL) {
        if (d) (*env)->ReleaseByteArrayElements(env, opusData, d, JNI_ABORT);
        if (o) (*env)->ReleaseByteArrayElements(env, out, o, JNI_ABORT);
        return -3;
    }

    int n = opus_decode(dec, (const unsigned char *) d, len,
                        (opus_int16 *) o, outSamples, 0);

    (*env)->ReleaseByteArrayElements(env, opusData, d, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, out, o, 0);

    if (n < 0) {
        LOGE("opus_decode failed: %d", n);
        return n;
    }
    return n;
}

JNIEXPORT void JNICALL
Java_com_xiaodu_voice_OpusCodec_nativeEncoderDestroy(JNIEnv *env, jclass c, jlong handle) {
    OpusEncoder *enc = (OpusEncoder *) (intptr_t) handle;
    if (enc) opus_encoder_destroy(enc);
}

JNIEXPORT void JNICALL
Java_com_xiaodu_voice_OpusCodec_nativeDecoderDestroy(JNIEnv *env, jclass c, jlong handle) {
    OpusDecoder *dec = (OpusDecoder *) (intptr_t) handle;
    if (dec) opus_decoder_destroy(dec);
}
