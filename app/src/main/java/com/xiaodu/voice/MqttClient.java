package com.xiaodu.voice;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 极简 MQTT 3.1.1 客户端（仅实现本项目需要的部分）。
 *
 * 为什么自己写而不是引依赖：
 *   · 设备 1GB RAM / armeabi-v7a，能少一个 jar 就少一个
 *   · 小智服务端只用 CONNECT / PUBLISH / PINGREQ 三种包，收侧甚至不需要订阅
 *     （OTA 下发的 subscribe_topic 是字面量 "null"，由 broker 按 client_id 路由）
 *   · 引第三方 MQTT 库反而要处理它的自动重连与订阅逻辑，与我们「不订阅」的约定冲突
 *
 * 报文格式要点（之前写错过，别再踩）：
 *   · 固定头首字节 = packetType << 4 | flags，不是 packetType 本身
 *       CONNECT=0x10  CONNACK=0x20  PUBLISH(QoS0)=0x30
 *       PINGREQ=0xC0  PINGRESP=0xD0  DISCONNECT=0xE0
 *   · 固定头第二段是「剩余长度」，用可变字节整数（varint，7 bit/字节，
 *     首字节 bit7=1 表示还有后续），不是定长 2 字节
 *   · 剩余长度 = 可变头长度 + 有效载荷长度（CONNECT 的可变头固定 10 字节）
 */
public class MqttClient {

    private static final String TAG = "XzMqtt";

    private static final int PKT_CONNECT    = 0x10;
    private static final int PKT_CONNACK    = 0x20;
    private static final int PKT_PUBLISH    = 0x30;
    private static final int PKT_PINGREQ    = 0xC0;
    private static final int PKT_PINGRESP   = 0xD0;
    private static final int PKT_DISCONNECT = 0xE0;

    /** CONNECT 可变头固定 10 字节 */
    private static final int CONNECT_VH_LEN = 10;

    private final String host;
    private final int port;
    private final String clientId;
    private final String username;
    private final String password;

    private Socket socket;
    private DataOutputStream out;
    private DataInputStream in;
    private Thread readerThread;
    private volatile boolean connected;
    private volatile boolean closed;

    private MsgListener listener;

    public interface MsgListener {
        void onMessage(String payload);
    }

    public MqttClient(String host, String clientId, String username, String password,
                      MsgListener listener) {
        this.host = host;
        this.port = 8883;
        this.clientId = clientId;
        this.username = username;
        this.password = password;
        this.listener = listener;
    }

    public boolean isConnected() { return connected; }

    public boolean connect() {
        try {
            SSLSocketFactory factory = sslFactory();
            // 必须带 SNI：factory.createSocket() 无参版本不设 SNI，
            // 多域名负载均衡收到无名握手包会直接断开。
            // 先建裸 TCP，再升级为 TLS，让 host 作为 SNI 送出。
            Socket raw = new Socket();
            raw.connect(new InetSocketAddress(host, port), 15000);
            raw.setTcpNoDelay(true);
            SSLSocket s = (SSLSocket) factory.createSocket(raw, host, port, true);
            s.setSoTimeout(0);
            s.startHandshake();
            Log.i(TAG, "TLS ok: " + s.getSession().getProtocol()
                    + " " + s.getSession().getCipherSuite());
            socket = s;
            out = new DataOutputStream(s.getOutputStream());
            in = new DataInputStream(s.getInputStream());

            sendConnect();

            // 等 CONNACK：固定头 + varint 剩余长度 + flags + return code
            s.setSoTimeout(15000);
            int type = in.readUnsignedByte();
            if (type != PKT_CONNACK) {
                Log.e(TAG, "expect CONNACK(0x20), got 0x" + Integer.toHexString(type));
                close();
                return false;
            }
            int rem = readRemLen();
            if (rem < 2) {
                Log.e(TAG, "CONNACK 剩余长度异常: " + rem);
                close();
                return false;
            }
            int ackFlags = in.readUnsignedByte();
            int rc = in.readUnsignedByte();
            if (rem > 2) in.skipBytes(rem - 2);
            if (rc != 0) {
                Log.e(TAG, "connect refused rc=" + rc);
                close();
                return false;
            }
            Log.i(TAG, "CONNACK ok, sessionPresent=" + (ackFlags & 1));
            s.setSoTimeout(0);
            connected = true;
            readerThread = new Thread(this::readLoop, "xz-mqtt-rx");
            readerThread.start();
            Log.i(TAG, "connected to " + host + ":" + port + " clientId=" + clientId);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "connect failed: " + t, t);
            close();
            return false;
        }
    }

    private SSLSocketFactory sslFactory() throws Exception {
        // 小智服务端证书为公共 CA 签发，正常校验即可；
        // 但部分自建服使用自签证书，这里放宽以兼容（音频已用 AES-CTR 保护）。
        TrustManager[] tm = new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) { }
            public void checkServerTrusted(X509Certificate[] c, String a) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tm, new java.security.SecureRandom());
        return ctx.getSocketFactory();
    }

    // ---------------- CONNECT ----------------

    private void sendConnect() throws Exception {
        byte[] payload = buildConnectPayload();

        // 可变头（10 字节）：协议名长度(2) + "MQTT"(4) + level(1) + flags(1) + keepalive(2)
        byte[] vh = new byte[CONNECT_VH_LEN];
        vh[0] = 0x00;
        vh[1] = 0x04;                       // 协议名 "MQTT" 长度 = 4
        vh[2] = 'M';
        vh[3] = 'Q';
        vh[4] = 'T';
        vh[5] = 'T';
        vh[6] = 0x04;                       // protocol level 4 = MQTT 3.1.1
        vh[7] = (byte) 0xC2;                // username + password + clean session
        vh[8] = 0x00;                       // keepalive 高字节
        vh[9] = 60;                         // keepalive = 60s

        int remaining = CONNECT_VH_LEN + payload.length;

        out.writeByte(PKT_CONNECT);         // 固定头首字节
        out.write(encodeRemLen(remaining)); // 剩余长度 varint
        out.write(vh);
        out.write(payload);
        out.flush();
        Log.i(TAG, "CONNECT sent, clientId=" + clientId + " remaining=" + remaining);
    }

    private byte[] buildConnectPayload() throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        writeString(bo, clientId);
        writeString(bo, username);
        writeString(bo, password);
        return bo.toByteArray();
    }

    private void writeString(ByteArrayOutputStream bo, String s) throws Exception {
        byte[] b = (s == null ? "" : s).getBytes("UTF-8");
        bo.write((b.length >>> 8) & 0xff);
        bo.write(b.length & 0xff);
        bo.write(b, 0, b.length);
    }

    // ---------------- PUBLISH ----------------

    public void publish(String topic, String payload) {
        if (!connected || out == null) return;
        try {
            byte[] t = topic.getBytes("UTF-8");
            byte[] p = payload.getBytes("UTF-8");
            int rem = 2 + t.length + p.length;      // topic 长度前缀 + topic + 正文
            byte[] len = encodeRemLen(rem);
            int total = 1 + len.length + rem;
            byte[] buf = new byte[total];
            int idx = 0;
            buf[idx++] = (byte) PKT_PUBLISH;        // 0x30 = PUBLISH + QoS0 + no retain
            System.arraycopy(len, 0, buf, idx, len.length);
            idx += len.length;
            buf[idx++] = (byte) ((t.length >>> 8) & 0xff);
            buf[idx++] = (byte) (t.length & 0xff);
            System.arraycopy(t, 0, buf, idx, t.length);
            idx += t.length;
            System.arraycopy(p, 0, buf, idx, p.length);
            idx += p.length;
            out.write(buf, 0, idx);
            out.flush();
        } catch (Throwable e) {
            Log.w(TAG, "publish failed: " + e);
        }
    }

    private byte[] encodeRemLen(int len) {
        byte[] out = new byte[4];
        int i = 0;
        do {
            int d = len % 128;
            len /= 128;
            if (len > 0) d |= 0x80;
            out[i++] = (byte) d;
        } while (len > 0);
        return java.util.Arrays.copyOf(out, i);
    }

    // ---------------- 读循环 ----------------

    private void readLoop() {
        try {
            while (!closed && connected) {
                int type = in.readUnsignedByte();
                int rem = readRemLen();
                if (rem < 0) break;
                int kind = type & 0xF0;         // 高 4 位是报文类型
                if (kind == PKT_PUBLISH) {
                    byte[] body = new byte[rem];
                    in.readFully(body);
                    // body: topic(2+n) + [packetId(2) if qos>0] + payload
                    if (body.length < 2) continue;
                    int tlen = ((body[0] & 0xff) << 8) | (body[1] & 0xff);
                    int qos = (type >> 1) & 0x03;
                    int idx = 2 + tlen + (qos > 0 ? 2 : 0);
                    if (idx > body.length) continue;
                    String payload = new String(body, idx, body.length - idx, "UTF-8");
                    if (listener != null) listener.onMessage(payload);
                } else {
                    if (rem > 0) in.skipBytes(rem);
                }
            }
        } catch (Throwable t) {
            if (!closed) Log.w(TAG, "read loop ended: " + t);
        } finally {
            connected = false;
        }
    }

    private int readRemLen() throws Exception {
        int multiplier = 1;
        int value = 0;
        int b;
        int count = 0;
        do {
            b = in.readUnsignedByte();
            value += (b & 127) * multiplier;
            multiplier *= 128;
            count++;
            if (count > 4) return -1;
        } while ((b & 128) != 0);
        return value;
    }

    // ---------------- 保活 ----------------

    public void startKeepAlive() {
        new Thread(() -> {
            while (!closed && connected) {
                try {
                    Thread.sleep(30000);
                    if (!connected) break;
                    out.writeByte(PKT_PINGREQ);
                    out.writeByte(0);
                    out.flush();
                } catch (Throwable ignored) { }
            }
        }, "xz-mqtt-ping").start();
    }

    public void disconnect() {
        try {
            if (out != null && connected) {
                out.writeByte(PKT_DISCONNECT);
                out.writeByte(0);
                out.flush();
            }
        } catch (Throwable ignored) { }
        closed = true;
        connected = false;
        close();
    }

    private void close() {
        try { if (socket != null) socket.close(); } catch (Throwable ignored) { }
        socket = null;
        out = null;
        in = null;
    }
}
