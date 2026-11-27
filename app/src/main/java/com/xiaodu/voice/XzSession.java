package com.xiaodu.voice;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 小智协议层（server.py 的 XzSession 的 Java 版）。
 *
 * 拓扑：
 *   Java 原生 --MQTT/TLS 8883--> 小智控制面
 *             └--UDP + AES-128-CTR--> 小智音频面
 *
 * 协议要点（对齐 xiaozhi-esp32 v3 / py-xiaozhi）：
 *   · MQTT 不订阅任何 topic —— OTA 下发的 subscribe_topic 就是 "null" 字面量，
 *     表示由 broker 按 client_id 路由；自己乱订阅会被踢下线。
 *   · hello 发到 publish_topic("device-server")，version=3 transport=udp，
 *     服务端回 hello 里带 udp{server,port,key,nonce}。
 *   · 音频包 = 16 字节包头 + AES-CTR 密文；包头本身就是 CTR 的 nonce，
 *     布局 type(1) flags(1) len(2B BE) ssrc(4) ts(4) seq(4B BE)。
 */
public class XzSession {

    private static final String TAG = "XzSession";

    private static final int XZ_UP_RATE = 16000;
    private static final int XZ_UP_MS = 60;
    private static final int XZ_UP_SAMPLES = XZ_UP_RATE * XZ_UP_MS / 1000;   // 960

    private final MainActivity act;
    private final Settings settings;
    private final AudioEngine audio;
    private Bridge bridge;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile String sessionId = "";
    private volatile String publishTopic = "device-server";
    private volatile int downRate = 24000;
    private volatile boolean paused = false;      // 半双工：TTS 期间停上行
    private volatile boolean armed = false;       // 是否处于「在听」状态
    private final Object lock = new Object();

    private MqttClient mqtt;
    private DatagramSocket udp;
    private String udpServer;
    private int udpPort;
    private byte[] aesKey = new byte[16];
    private String nonceHex = "";
    private long seq = 0;
    private volatile int upCount = 0;      // 已发送上行帧数（诊断用）
    private volatile int downCount = 0;    // 已收到下行帧数（诊断用）

    /** 下行 PCM 队列（UDP 线程 → 播放线程） */
    private final java.util.concurrent.LinkedBlockingQueue<byte[]> downQ =
            new java.util.concurrent.LinkedBlockingQueue<>(64);

    public XzSession(MainActivity act, Settings settings, AudioEngine audio) {
        this.act = act;
        this.settings = settings;
        this.audio = audio;
        audio.setSink(this::onMicPcm);
        audio.setSource(() -> {
            try {
                return downQ.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return null;
            }
        });
    }

    public boolean isRunning() { return running.get(); }

    public void setBridge(Bridge b) { this.bridge = b; }

    /**
     * 仅在音频编解码就绪时才自动连小智。
     * Phase 1 Opus 未链接，自动连接只会弹验证码面板、干扰 AEC 实验，
     * 所以这里做能力门控；Phase 2 Opus 接通后自动放行。
     */
    public void startIfReady() {
        if (!OpusCodec.available()) {
            Log.i(TAG, "audio codec not ready, skip auto-connect (Phase 1)");
            return;
        }
        start();
    }

    // ---------------- 生命周期 ----------------

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        new Thread(this::run, "xz-session").start();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        try {
            if (mqtt != null) {
                mqtt.disconnect();
                mqtt = null;
            }
        } catch (Throwable ignored) { }
        try {
            if (udp != null) {
                udp.close();
                udp = null;
            }
        } catch (Throwable ignored) { }
        downQ.clear();
    }

    // ---------------- 主流程 ----------------

    private void run() {
        try {
            emit("net", "{\"text\":\"正在请求 OTA…\"}");
            JSONObject ota = otaReady();
            if (ota == null) {
                emit("error", "{\"text\":\"OTA 失败\"}");
                return;
            }
            JSONObject mq = ota.optJSONObject("mqtt");
            if (mq == null || mq.optString("client_id").isEmpty()) {
                emit("error", "{\"text\":\"服务端没下发 MQTT 配置\"}");
                return;
            }
            String publishTopic = mq.optString("publish_topic", "device-server");
            this.publishTopic = publishTopic;
            emit("net", "{\"text\":\"连接小智…\"}");

            mqtt = new MqttClient(
                    mq.optString("endpoint"),
                    mq.optString("client_id"),
                    mq.optString("username"),
                    mq.optString("password"),
                    this::onMqttJson);
            if (!mqtt.connect()) {
                emit("error", "{\"text\":\"MQTT 连接失败\"}");
                return;
            }

            JSONObject hello = new JSONObject();
            hello.put("type", "hello");
            hello.put("version", 3);
            hello.put("transport", "udp");
            JSONObject ap = new JSONObject();
            ap.put("format", "opus");
            ap.put("sample_rate", XZ_UP_RATE);
            ap.put("channels", 1);
            ap.put("frame_duration", XZ_UP_MS);
            hello.put("audio_params", ap);
            JSONObject feat = new JSONObject();
            feat.put("mcp", true);
            hello.put("features", feat);
            mqtt.publish(publishTopic, hello.toString());

            // 等服务端回 hello（带 udp 参数）
            long deadline = System.currentTimeMillis() + 12000;
            while (System.currentTimeMillis() < deadline && udpServer == null) {
                Thread.sleep(100);
            }
            if (udpServer == null) {
                emit("error", "{\"text\":\"音频通道协商超时\"}");
                return;
            }

            // UDP 音频面
            udp = new DatagramSocket();
            new Thread(this::udpRx, "xz-udp-rx").start();
            new Thread(this::udpTx, "xz-udp-tx").start();
            new Thread(this::dbgLoop, "xz-dbg").start();

            // 采集
            if (!audio.start()) {
                emit("error", "{\"text\":\"音频启动失败\"}");
                return;
            }
            // 自检音：确认「引擎 track → 扬声器」这条物理通路本身是通的。
            // 诊断期保留，跑通后删掉。
            new Thread(() -> {
                try { Thread.sleep(600); } catch (InterruptedException ignored) { }
                audio.playToneOnEngineTrack(1000, 300, 9000);
            }, "xz-beep").start();
            armed = true;
            sendListen("start");
            emit("ready", "{\"mode\":\"xiaozhi\",\"downRate\":" + downRate + "}");
        } catch (Throwable t) {
            Log.e(TAG, "session failed", t);
            emit("error", "{\"text\":\"" + t.getClass().getSimpleName() + ": "
                    + String.valueOf(t.getMessage()).replace('"', '\'') + "\"}");
        }
    }

    // ---------------- OTA + 激活 ----------------

    /**
     * 拉取 OTA，直到服务端不再返回 activation.code（即设备已绑定）。
     * 未绑定时把 6 位验证码显示给用户，并用 hmac_key 对 challenge 签名上报。
     */
    private JSONObject otaReady() throws Exception {
        String shown = "";
        for (int attempt = 0; attempt < 400; attempt++) {
            if (!running.get()) return null;
            JSONObject d = otaPost();
            if (d == null) {
                Thread.sleep(1500);
                continue;
            }
            JSONObject act2 = d.optJSONObject("activation");
            String code = act2 == null ? "" : act2.optString("code", "");
            if (code.isEmpty()) {
                if (!shown.isEmpty()) emit("activate", "{\"state\":\"done\"}");
                return d;
            }
            if (!code.equals(shown)) {
                shown = code;
                emit("activate", "{\"state\":\"wait\",\"code\":\"" + code + "\"}");
            }
            String challenge = act2.optString("challenge", "");
            if (!challenge.isEmpty()) {
                String rs = activatePost(challenge, code);
                if ("activated".equals(rs)) {
                    emit("activate", "{\"state\":\"done\"}");
                    continue;                       // 立刻重拉 OTA 拿真 token
                }
            }
            Thread.sleep(3000);
        }
        return null;
    }

    private JSONObject otaPost() {
        HttpURLConnection c = null;
        try {
            String dev = settings.getDeviceId();
            JSONObject body = new JSONObject();
            body.put("version", 2);
            body.put("language", "zh-CN");
            body.put("mac_address", dev);
            body.put("uuid", settings.getClientId());
            JSONObject app = new JSONObject();
            app.put("name", "xiaozhi");
            app.put("version", "1.0.0");
            app.put("elf_sha256", settings.getHmacKey());
            body.put("application", app);
            JSONObject board = new JSONObject();
            board.put("type", "bread-compact-wifi");
            board.put("name", "xiaozhi");
            board.put("ip", settings.localIp());
            board.put("mac", dev);
            body.put("board", board);
            JSONObject proto = new JSONObject();
            JSONObject feat = new JSONObject();
            feat.put("mcp", true);
            proto.put("features", feat);
            body.put("protocol", proto);

            String url = settings.getOtaUrl();
            if (!url.endsWith("/")) url += "/";
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Device-Id", dev);
            c.setRequestProperty("Client-Id", settings.getClientId());
            c.setRequestProperty("Activation-Version", "2");
            c.setRequestProperty("Serial-Number", settings.getSerialNumber());
            c.setRequestProperty("User-Agent", "bread-compact-wifi/xiaozhi-1.0.0");
            c.setRequestProperty("Accept-Language", "zh-CN");
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.close();
            int code = c.getResponseCode();
            String resp = readAll(code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream());
            if (code >= 200 && code < 300) return new JSONObject(resp);
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "otaPost: " + t);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * 激活 v2：用 hmac_key 对 challenge 签名，POST 到 {ota}/activate。
     * 返回 activated / pending / error。
     */
    private String activatePost(String challenge, String code) {
        HttpURLConnection c = null;
        try {
            String sig = Settings.hmacSha256Hex(settings.getHmacKey(), challenge);
            JSONObject payload = new JSONObject();
            JSONObject pl = new JSONObject();
            pl.put("algorithm", "hmac-sha256");
            pl.put("serial_number", settings.getSerialNumber());
            pl.put("challenge", challenge);
            pl.put("hmac", sig);
            payload.put("Payload", pl);

            String url = settings.getOtaUrl();
            if (!url.endsWith("/")) url += "/";
            url += "activate";
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Activation-Version", "2");
            c.setRequestProperty("Serial-Number", settings.getSerialNumber());
            c.setRequestProperty("Device-Id", settings.getDeviceId());
            c.setRequestProperty("Client-Id", settings.getClientId());
            c.setRequestProperty("User-Agent", "bread-compact-wifi/xiaozhi-1.0.0");
            OutputStream os = c.getOutputStream();
            os.write(payload.toString().getBytes("UTF-8"));
            os.close();
            int code2 = c.getResponseCode();
            if (code2 == 200) {
                settings.setActivationStatus(true);
                return "activated";
            }
            if (code2 == 202) return "pending";
            return "error";
        } catch (Throwable t) {
            Log.w(TAG, "activatePost: " + t);
            return "error";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String readAll(java.io.InputStream is) throws Exception {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    // ---------------- MQTT 下行 JSON ----------------

    private void onMqttJson(String payload) {
        try {
            JSONObject m = new JSONObject(payload);
            String t = m.optString("type");
            // 诊断：把服务端原始报文打出来（截断），排查字段名/取值问题
            Log.i(TAG, "rx<" + t + "> " + (payload.length() > 400
                    ? payload.substring(0, 400) + "…" : payload));
            if ("hello".equals(t)) {
                JSONObject udpObj = m.optJSONObject("udp");
                if (udpObj != null) {
                    udpServer = udpObj.optString("server");
                    udpPort = udpObj.optInt("port");
                    String key = udpObj.optString("key");
                    nonceHex = udpObj.optString("nonce");
                    try {
                        aesKey = hexToBytes(key);
                    } catch (Exception ignored) { }
                    Log.i(TAG, "hello.udp server=" + udpServer + ":" + udpPort
                            + " enc=" + udpObj.optString("encryption")
                            + " keyLen=" + (aesKey == null ? -1 : aesKey.length)
                            + " nonce=" + (nonceHex == null ? "null" : nonceHex.length() + "ch"));
                } else {
                    Log.w(TAG, "hello 里没有 udp 对象！");
                }
                sessionId = m.optString("session_id");
                JSONObject ap = m.optJSONObject("audio_params");
                if (ap != null) downRate = ap.optInt("sample_rate", 24000);
                Log.i(TAG, "hello.ok session=" + sessionId + " downRate=" + downRate);
                return;
            }
            if ("tts".equals(t)) {
                String state = m.optString("state");
                if ("start".equals(state)) {
                    if (settings.isHalfDuplex()) {
                        paused = true;
                        audio.setMuted(true);
                    }
                } else if ("stop".equals(state)) {
                    // 半双工：等功放余音过去再开麦；两种模式都要补 listen start
                    final boolean half = settings.isHalfDuplex();
                    new Thread(() -> {
                        try { Thread.sleep(half ? 300 : 150); } catch (InterruptedException ignored) { }
                        paused = false;
                        audio.setMuted(false);
                        sendListen("start");
                    }).start();
                }
                JSONObject o = new JSONObject();
                o.put("state", state);
                o.put("text", m.optString("text"));
                emit("tts", o.toString());
                return;
            }
            if ("stt".equals(t) || "llm".equals(t) || "intent".equals(t)
                    || "system".equals(t) || "mcp".equals(t) || "goodbye".equals(t)
                    || "alert".equals(t)) {
                JSONObject o = new JSONObject();
                o.put("text", m.optString("text", m.optString("emotion")));
                o.put("emotion", m.optString("emotion"));
                emit(t, o.toString());
                if ("goodbye".equals(t)) {
                    armed = false;
                }
                return;
            }
        } catch (Throwable ex) {
            Log.w(TAG, "onMqttJson: " + ex);
        }
    }

    private void sendListen(String state) {
        if (mqtt == null) return;
        if ("stop".equals(state)) armed = false;
        else armed = true;
        try {
            JSONObject o = new JSONObject();
            o.put("type", "listen");
            o.put("state", state);
            o.put("session_id", sessionId);
            if ("start".equals(state)) o.put("mode", "auto");
            mqtt.publish(publishTopic, o.toString());
        } catch (Throwable t) {
            Log.w(TAG, "sendListen: " + t);
        }
    }

    // ---------------- UDP 音频面 ----------------

    private void udpRx() {
        byte[] buf = new byte[4096];
        int lastSeq = 0;
        while (running.get() && udp != null) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                udp.receive(p);
                byte[] data = p.getData();
                int len = p.getLength();
                rxTotal++;
                if (rxTotal <= 3) {
                    Log.i(TAG, "rxpkt#" + rxTotal + " len=" + len + " hdr="
                            + hexOf(data, Math.min(16, len)));
                }
                if (len < 16) { rxCandDrop++; continue; }
                if ((data[0] & 0xff) != 0x01) {
                    rxCandDrop++;
                    if (rxBadType < 5) {
                        Log.w(TAG, "rx 非音频包头 type=0x"
                                + Integer.toHexString(data[0] & 0xff)
                                + " len=" + len + " hdr=" + hexOf(data, 16));
                        rxBadType++;
                    }
                    continue;
                }
                int plen = ((data[2] & 0xff) << 8) | (data[3] & 0xff);
                int seq = ((data[12] & 0xff) << 24) | ((data[13] & 0xff) << 16)
                        | ((data[14] & 0xff) << 8) | (data[15] & 0xff);
                lastSeqSeen = seq;
                if (plen <= 0 || 16 + plen > len) {
                    rxBadLen++;
                    if (rxBadLen < 5) {
                        Log.w(TAG, "rx 长度不符 plen=" + plen + " len=" + len
                                + " seq=" + seq);
                    }
                    continue;
                }
                // 只丢完全重复的包；乱序也照播，宁可轻微错序也别整段静音
                if (seq == lastSeq) { rxSeqDrop++; continue; }
                lastSeq = seq;
                byte[] plain = AesCtr.decrypt(aesKey, Arrays.copyOfRange(data, 0, 16),
                        Arrays.copyOfRange(data, 16, 16 + plen));
                if (plain == null) { rxDecNull++; continue; }
                byte[] pcm = OpusCodec.decode(plain);
                if (pcm == null) {
                    rxOpNull++;
                    if (rxOpNull < 5) {
                        Log.w(TAG, "opus decode 失败 err=" + OpusCodec.lastError
                                + " plen=" + plen + " plain0=" + hexOf(plain, 8));
                    }
                    continue;
                }
                rxOk++;
                downCount++;
                if (!downQ.offer(pcm)) downQ.poll();   // 拥塞丢最老的
            } catch (Throwable t) {
                if (!running.get()) break;
                rxErr++;
            }
        }
    }

    /** 诊断计数器 */
    private volatile int rxTotal, rxCandDrop, rxBadType, rxBadLen, rxSeqDrop;
    private volatile int rxDecNull, rxOpNull, rxOk, rxErr;
    private volatile int lastSeqSeen = -1;

    private static String hexOf(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n && i < b.length; i++) {
            sb.append(String.format("%02x", b[i] & 0xff));
        }
        return sb.toString();
    }

    /** 每 2 秒把链路各段计数打一遍，定位「包到没到/解码成不成/写没写进去」 */
    private void dbgLoop() {
        while (running.get()) {
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
            if (!running.get()) break;
            Log.i(TAG, "dbg udp=" + rxTotal + " dropType=" + rxCandDrop
                    + "/" + rxBadType + " badLen=" + rxBadLen + " dup=" + rxSeqDrop
                    + " decNull=" + rxDecNull + " opNull=" + rxOpNull
                    + " ok=" + rxOk + " err=" + rxErr + " seq=" + lastSeqSeen
                    + " q=" + downQ.size() + " wrBlk=" + audio.getWrittenBlocks()
                    + " wrKB=" + (audio.getWrittenBytes() / 1024)
                    + " lastN=" + OpusCodec.lastSamples);
        }
    }

    private void udpTx() {
        while (running.get() && udp != null) {
            try {
                // 上行音频由 onMicPcm 回调直接编码发送（见 sendUpstream），
                // 这里只做保活与异常检查，避免另开一条取帧通道造成时序错乱。
                Thread.sleep(20);
            } catch (Throwable t) {
                if (!running.get()) break;
                Log.w(TAG, "udpTx: " + t);
            }
        }
    }

    /**
     * 小智 UDP 音频包 nonce（必须凑满 16 字节，AES-CTR 的 IV 长度是硬要求）：
     *   前 2B  = nonce_hex[0:4]
     *   包长   = %04x (2B)
     *   中间 8B = nonce_hex[8:24]
     *   序号   = %08x (4B)
     * 合计 4+4+16+8 = 32 个 hex 字符 = 16 字节。
     * 注意 substring 的下标是「hex 字符」不是「字节」。
     */
    private byte[] buildAudioNonce(String nonceHex, int audioLen, long sequence) {
        StringBuilder sb = new StringBuilder(32);
        sb.append(hexSlice(nonceHex, 0, 4));
        sb.append(String.format("%04x", audioLen));
        sb.append(hexSlice(nonceHex, 8, 24));
        sb.append(String.format("%08x", sequence));
        return hexToBytes(sb.toString());
    }

    /** 取 hex 串的 [from,to) 字符区间；越界或为 null 时补足同样长度的 0 */
    private static String hexSlice(String s, int from, int to) {
        int want = to - from;
        if (s == null || s.length() < to) {
            StringBuilder z = new StringBuilder(want);
            for (int i = 0; i < want; i++) z.append('0');
            return z.toString();
        }
        return s.substring(from, to);
    }

    private static String pad(String s, int from, int to) {
        if (s == null) return "00000000";
        if (s.length() < to) return String.format("%08x", 0);
        return s.substring(from, to);
    }

    private static byte[] hexToBytes(String hex) {
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    // ---------------- 上行 PCM ----------------

    /**
     * 上行累积缓冲。
     * AudioRecord.read() 只保证「不超过请求长度」，实际可能返回半帧甚至更少，
     * 而 Opus 要求每次编码的采样数严格等于帧长（960）。所以这里必须自己
     * 攒够一帧再编码，否则会出现编码失败或帧边界错位。
     */
    private final byte[] upBuf = new byte[XZ_UP_SAMPLES * 2 * 4];
    private int upFill = 0;

    /**
     * 采集回调：PCM16 → Opus → AES-CTR → UDP。
     * 这一整段都在原生线程内闭环，不经过 JS/WebSocket。
     */
    private void onMicPcm(byte[] data, int len) {
        if (!running.get() || udp == null) return;
        // 密钥还没握手完成时不要往上发：encode+加密都用不了，
        // 每 60ms 刷一条异常日志只会把 logcat 淹掉。
        if (aesKey == null || aesKey.length != 16
                || nonceHex == null || nonceHex.length() < 32) return;
        if (paused) {
            // 半双工暂停期间丢弃积压，否则恢复开麦时会把 TTS 期间的
            // 陈旧音频当成用户说话发上去。
            upFill = 0;
            return;
        }
        try {
            int off = 0;
            while (off < len) {
                int room = upBuf.length - upFill;
                int n = Math.min(room, len - off);
                System.arraycopy(data, off, upBuf, upFill, n);
                upFill += n;
                off += n;
                if (upFill >= XZ_UP_SAMPLES * 2) {
                    sendUpstream(upBuf, XZ_UP_SAMPLES * 2);
                    // 帧长固定，剩余不足一帧的留到下次
                    upFill -= XZ_UP_SAMPLES * 2;
                    if (upFill > 0) {
                        System.arraycopy(upBuf, XZ_UP_SAMPLES * 2, upBuf, 0, upFill);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "onMicPcm: " + t);
        }
    }

    /** 编码并发送一帧 */
    private void sendUpstream(byte[] pcm, int len) throws Exception {
        byte[] opus = OpusCodec.encode(pcm, XZ_UP_SAMPLES);
        if (opus == null) return;
        long s;
        synchronized (lock) {
            seq = (seq + 1) & 0xFFFFFFFFL;
            s = seq;
        }
        byte[] nonce = buildAudioNonce(nonceHex, opus.length, s);
        byte[] body = AesCtr.encrypt(aesKey, nonce, opus);
        if (body == null) {
            Log.w(TAG, "encrypt returned null (key/iv="
                    + (aesKey == null ? -1 : aesKey.length) + "/"
                    + (nonce == null ? -1 : nonce.length) + ")");
            return;
        }
        byte[] packet = new byte[16 + body.length];
        packet[0] = 0x01;
        packet[1] = 0x00;
        packet[2] = (byte) ((body.length >> 8) & 0xff);
        packet[3] = (byte) (body.length & 0xff);
        // ssrc(4) ts(4) 保持 0，服务端不校验
        System.arraycopy(nonce, 0, packet, 0, 16);
        System.arraycopy(body, 0, packet, 16, body.length);
        udp.send(new DatagramPacket(packet, packet.length,
                InetAddress.getByName(udpServer), udpPort));
        upCount++;
    }

    // ---------------- 工具 ----------------

    private void emit(String type, String json) {
        if (bridge != null) {
            bridge.emit(type, json);
        } else {
            Log.w(TAG, "emit dropped (no bridge): " + type);
        }
    }
}
