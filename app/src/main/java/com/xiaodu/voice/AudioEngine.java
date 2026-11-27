package com.xiaodu.voice;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

/**
 * 原生音频引擎。
 *
 * 设计要点（针对 MT8167 的硬件 AEC）：
 *
 * 1) 采集必须走 VOICE_COMMUNICATION 输入源。
 *    浏览器 getUserMedia 只能拿 MIC 源，framework 层 audio_effects.conf 里
 *    aec/ns/agc 全被注释掉，所以浏览器实测 ERLE 只有 0~4 dB。
 *    改用 MediaRecorder.AudioSource.VOICE_COMMUNICATION 后，
 *    HAL 会切到 AudioALSACaptureHandlerAEC + AudioALSACaptureDataProviderEchoRef，
 *    由 DSP 侧的 Speech AEC 做回声消除。
 *
 * 2) 播放必须与采集处于同一时基。
 *    回声参考（echo reference）靠 DSP 拿到下行播放数据，
 *    采集与播放的通路不一致时 maxDelayUs 会超限，AEC 退化为不生效。
 *    因此播放侧同样用 VOICE_COMMUNICATION 属性 + MODE_IN_COMMUNICATION 模式。
 *
 * 3) 采样率。
 *    上行协议固定 16 kHz，HAL 在 VOICE_COMMUNICATION 通路下会做内部重采样，
 *    这里直接按 16 kHz 申请。
 */
public class AudioEngine {

    private static final String TAG = "XzAudio";

    public static final int SR_UP = 16000;      // 上行采样率（小智协议固定）
    public static final int FRAME_MS = 60;      // 小智协议帧长
    public static final int FRAME_SAMPLES = SR_UP * FRAME_MS / 1000;   // 960
    public static final int FRAME_BYTES = FRAME_SAMPLES * 2;          // 1920

    /** 采集输入源 */
    public static final int SRC_VOICE_COMM = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
    public static final int SRC_VOICE_RECOG = MediaRecorder.AudioSource.VOICE_RECOGNITION;
    public static final int SRC_MIC         = MediaRecorder.AudioSource.MIC;

    /** 播放用途 */
    public static final int USE_VOICE_COMM = AudioAttributes.USAGE_VOICE_COMMUNICATION;
    public static final int USE_MEDIA      = AudioAttributes.USAGE_MEDIA;

    /** 音频模式 */
    public static final int MODE_NORMAL    = AudioManager.MODE_NORMAL;
    public static final int MODE_COMM      = AudioManager.MODE_IN_COMMUNICATION;

    private AudioManager audioManager;
    private AudioRecord record;
    private AudioTrack track;
    private Thread readThread;
    private Thread writeThread;
    private volatile boolean running;
    private volatile boolean exclusive;          // AEC 实验期间独占 record
    private volatile boolean captureOnly;       // 只开采集，不开下行 track
    private volatile int muted;             // 1=暂停上行（半双工）
    private volatile int level;             // 采集电平 0~100
    private int captureSource = SRC_VOICE_COMM;
    private int playUsage = USE_VOICE_COMM;
    private int audioMode = MODE_COMM;
    private int speakerGain = 80;

    /** 上行 PCM 回调（原始 16-bit 小端，16k 单声道） */
    public interface PcmSink {
        void onPcm(byte[] data, int len);
    }

    /** 下行 PCM 投递 */
    public interface PcmSource {
        byte[] take();
    }

    private PcmSink sink;
    private PcmSource source;

    public AudioEngine(AudioManager am) {
        this.audioManager = am;
    }

    public void setSink(PcmSink s) { this.sink = s; }
    public void setSource(PcmSource s) { this.source = s; }
    public void setSpeakerGain(int pct) { this.speakerGain = Math.max(0, Math.min(100, pct)); }

    /** AEC 实验期间独占采集通道 */
    public void setExclusive(boolean e) { this.exclusive = e; }

    /**
     * 只开采集不开下行 track。
     * AEC 实验时若同时存在两个 AudioTrack，HAL 的 echo reference 可能绑到
     * 错误的那个，测出来的 ERLE 就没有意义了。
     */
    public void setCaptureOnly(boolean c) { this.captureOnly = c; }

    public int getLevel() { return level; }
    public boolean isRunning() { return running; }

    /** 半双工：TTS 播放期间暂停上行 */
    public void setMuted(boolean m) { this.muted = m ? 1 : 0; }
    public boolean isMuted() { return muted != 0; }

    /** 配置采集源 / 播放用途 / 音频模式，用于 AEC 通路对比实验 */
    public void setPath(int captureSource, int playUsage, int audioMode) {
        this.captureSource = captureSource;
        this.playUsage = playUsage;
        this.audioMode = audioMode;
    }

    public int getCaptureSource() { return captureSource; }
    public int getPlayUsage() { return playUsage; }
    public int getAudioMode() { return audioMode; }

    /**
     * 启动采集与播放。
     * 顺序有讲究：先设 mode，再开 AudioRecord，最后开 AudioTrack。
     * 如果先放音再开采集，HAL 可能来不及建立 echo reference 通路。
     */
    public synchronized boolean start() {
        if (running) return true;
        try {
            audioManager.setMode(audioMode);
            audioManager.setSpeakerphoneOn(audioMode == MODE_COMM);
        } catch (Throwable t) {
            Log.w(TAG, "setMode failed: " + t);
        }

        int minBuf = AudioRecord.getMinBufferSize(
                SR_UP, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = FRAME_BYTES * 4;
        int bufSize = Math.max(minBuf, FRAME_BYTES * 8);

        try {
            record = new AudioRecord(captureSource, SR_UP,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
        } catch (Throwable t) {
            Log.e(TAG, "AudioRecord create failed", t);
            return false;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized");
            record.release();
            record = null;
            return false;
        }

        try {
            record.startRecording();
        } catch (Throwable t) {
            Log.e(TAG, "startRecording failed", t);
            record.release();
            record = null;
            return false;
        }

        int outBuf = Math.max(FRAME_BYTES * 8,
                AudioTrack.getMinBufferSize(SR_UP, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT));
        if (!captureOnly) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(playUsage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setSampleRate(SR_UP)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build();
            try {
                track = new AudioTrack(attrs, fmt, outBuf,
                        AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            } catch (Throwable t) {
                Log.e(TAG, "AudioTrack create failed", t);
                stopRecordOnly();
                return false;
            }
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack not initialized");
                track.release();
                track = null;
                stopRecordOnly();
                return false;
            }
            track.play();
        }

        running = true;
        readThread = new Thread(this::readLoop, "xz-audio-read");
        writeThread = new Thread(this::writeLoop, "xz-audio-write");
        readThread.setPriority(Thread.MAX_PRIORITY);
        writeThread.setPriority(Thread.MAX_PRIORITY);
        readThread.start();
        writeThread.start();
        Log.i(TAG, "started src=" + captureSource + " use=" + playUsage + " mode=" + audioMode);
        return true;
    }

    public synchronized void stop() {
        running = false;
        if (readThread != null) {
            try { readThread.join(400); } catch (InterruptedException ignored) { }
            readThread = null;
        }
        if (writeThread != null) {
            try { writeThread.join(400); } catch (InterruptedException ignored) { }
            writeThread = null;
        }
        stopRecordOnly();
        if (track != null) {
            try { track.stop(); } catch (Throwable ignored) { }
            try { track.release(); } catch (Throwable ignored) { }
            track = null;
        }
        try {
            audioManager.setMode(AudioManager.MODE_NORMAL);
        } catch (Throwable ignored) { }
        Log.i(TAG, "stopped");
    }

    private void stopRecordOnly() {
        if (record != null) {
            try { record.stop(); } catch (Throwable ignored) { }
            try { record.release(); } catch (Throwable ignored) { }
            record = null;
        }
    }

    /** 上行采集循环 */
    private void readLoop() {
        byte[] buf = new byte[FRAME_BYTES];
        while (running) {
            AudioRecord r = record;
            if (r == null) break;
            // 独占模式：AEC 实验期间由 captureToneLevel 自己读 record，
            // 这里必须让开，否则两个线程抢同一个 AudioRecord，
            // 采到的数据会被随机瓜分，Goertzel 量出来的残余回声全是噪声。
            if (exclusive) {
                try { Thread.sleep(10); } catch (InterruptedException ignored) { return; }
                continue;
            }
            int read = r.read(buf, 0, buf.length);
            if (read <= 0) continue;
            if (muted != 0) continue;            // 半双工：TTS 期间丢弃
            // 计算电平（峰值）
            int mx = 0;
            for (int i = 0; i + 1 < read; i += 2) {
                int v = (buf[i] & 0xff) | (buf[i + 1] << 8);
                if (v < 0) v = -v;
                if (v > mx) mx = v;
            }
            level = Math.min(100, mx * 100 / 32768);
            PcmSink s = sink;
            if (s != null) {
                try {
                    s.onPcm(buf, read);
                } catch (Throwable t) {
                    Log.e(TAG, "sink error", t);
                }
            }
        }
    }

    /** 下行播放循环 */
    private void writeLoop() {
        while (running) {
            PcmSource s = source;
            byte[] data = (s == null) ? null : s.take();
            if (data == null || data.length == 0) {
                try { Thread.sleep(5); } catch (InterruptedException ignored) { return; }
                continue;
            }
            AudioTrack t = track;
            if (t == null) break;
            int off = 0;
            while (off < data.length && running) {
                int n = t.write(data, off, data.length - off);
                if (n <= 0) break;
                off += n;
            }
        }
    }

/**
     * 用引擎自己的下行 AudioTrack 播放一段 PCM 测试音。
     *
     * 为什么不用另开 track：AEC 的 echo reference 由 HAL 绑定在
     * 「采集所用 AudioRecord」与「播放所用 AudioTrack」这一对对象上。
     * 另开 track 会让 HAL 拿不到正确的参考，测出来的 ERLE 就没有意义。
     * 真实通话时采集与播放也是走引擎这一对对象，所以这里保持一致。
     *
     * @param toneHz  频率
     * @param ms      时长
     * @param amplitude 幅度（int16）
     */
    public void playToneOnEngineTrack(int toneHz, int ms, int amplitude) {
        AudioTrack t = track;
        if (t == null) {
            Log.e(TAG, "playToneOnEngineTrack: no engine track (captureOnly?)");
            return;
        }
        try {
            int total = SR_UP * ms / 1000;
            byte[] bytes = new byte[total * 2];
            for (int i = 0; i < total; i++) {
                short v = (short) (Math.sin(2 * Math.PI * toneHz * i / SR_UP) * amplitude);
                bytes[i * 2] = (byte) (v & 0xff);
                bytes[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
            }
            int off = 0;
            while (off < bytes.length && running) {
                int n = t.write(bytes, off, Math.min(FRAME_BYTES * 4, bytes.length - off));
                if (n <= 0) break;
                off += n;
                Thread.sleep(5);
            }
            Log.i(TAG, "playToneOnEngineTrack done: " + off + "/" + bytes.length + " bytes");
        } catch (Throwable e) {
            Log.e(TAG, "playToneOnEngineTrack failed", e);
        }
    }

    /**
     * 播放一段 PCM 测试音（用于 AEC 通路对比实验）。
     * toneHz=1000, ms=2000 生成标准 1kHz 正弦，幅度固定，便于用 Goertzel 量化残余回声。
     *
     * 注意两点：
     *   1) AudioTrack 没有 wait(long)（那是 Object 的监视器方法，未加锁会抛
     *      IllegalMonitorStateException），这里用 Thread.sleep。
     *   2) 用 MODE_STREAM 而不是 MODE_STATIC：MODE_STATIC 在本机上 track
     *      初始化失败（state != STATE_INITIALIZED），测试音根本播不出去。
     */
    public void playTone(int toneHz, int ms, int amplitude) {
        AudioTrack t = null;
        try {
            int total = SR_UP * ms / 1000;
            short[] pcm = new short[total];
            for (int i = 0; i < total; i++) {
                pcm[i] = (short) (Math.sin(2 * Math.PI * toneHz * i / SR_UP) * amplitude);
            }
            byte[] bytes = new byte[total * 2];
            for (int i = 0; i < total; i++) {
                bytes[i * 2] = (byte) (pcm[i] & 0xff);
                bytes[i * 2 + 1] = (byte) ((pcm[i] >> 8) & 0xff);
            }
            int bufSize = Math.max(bytes.length * 2, FRAME_BYTES * 8);
            t = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(playUsage)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    new AudioFormat.Builder()
                            .setSampleRate(SR_UP)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    bufSize,
                    AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (t.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "playTone: track not initialized (usage=" + playUsage + ")");
                return;
            }
            t.play();
            // 分块写入，边写边播；写完再等播放缓冲走完
            int off = 0;
            while (off < bytes.length) {
                int n = t.write(bytes, off, Math.min(FRAME_BYTES * 4, bytes.length - off));
                if (n <= 0) break;
                off += n;
                Thread.sleep(10);
            }
            long waitMs = (long) bytes.length / 2L / SR_UP * 1000L + 400;
            Thread.sleep(waitMs);
        } catch (Throwable e) {
            Log.e(TAG, "playTone failed", e);
        } finally {
            if (t != null) {
                try { t.stop(); } catch (Throwable ignored) { }
                try { t.release(); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * Goertzel 算法：只测单一频点能量，比 FFT 轻量，适合设备端实时判定。
     * 返回该频点在 buffer 中的幅度（相对满量程 0~1）。
     */
    public static double goertzel(short[] data, int len, int freq, int sr) {
        double k = 0.5 + (double) freq * len / sr;
        double omega = 2 * Math.PI * k / len;
        double coeff = 2 * Math.cos(omega);
        double s0 = 0, s1 = 0, s2 = 0;
        for (int i = 0; i < len; i++) {
            s0 = data[i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        double real = s1 - s2 * Math.cos(omega);
        double imag = s2 * Math.sin(omega);
        double mag = Math.sqrt(real * real + imag * imag) / len;
        return mag * 2.0;
    }

    /**
     * 采一段 PCM 并返回其 1kHz 分量幅度（dBFS，满量程 0 dB）。
     * 这是 ERLE 测量的「残余回声」读数端。
     */
    public double captureToneLevel(int ms, int toneHz) {
        final int want = SR_UP * ms / 1000;
        short[] buf = new short[want];
        AudioRecord r = record;
        if (r == null) return -120;
        int got = 0;
        long deadline = System.currentTimeMillis() + ms + 500;
        while (got < want && System.currentTimeMillis() < deadline) {
            int n = r.read(buf, got, want - got);
            if (n <= 0) break;
            got += n;
        }
        if (got < want / 2) return -120;
        double amp = goertzel(buf, want, toneHz, SR_UP);
        // goertzel 返回的是 int16 幅度值，除以满量程才是 dBFS
        return 20 * Math.log10(Math.max(amp / 32768.0, 1e-9));
    }
}
