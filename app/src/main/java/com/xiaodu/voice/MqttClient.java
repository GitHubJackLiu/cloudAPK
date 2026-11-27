package com.xiaodu.voice;

import android.util.Log;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
 * 必需的能力：
 *   CONNECT(带用户名密码 + TLS) → CONNACK
 *   PUBLISH(qos0) → 发控制消息
 *   读服务端 PUBLISH → 解析 JSON
 *   PINGREQ 保活
 */
public class MqttClient {

    private static final String TAG = "XzMqtt";

    private static final byte CONNECT = 1;
    private static final byte CONNACK = 2;
    private static final byte PUBLISH = 3;
    private static final byte PINGREQ = 12;
    private static final byte PINGRESP = 13;
    private static final byte DISCONNECT = 14;

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
            SSLSocket s = (SSLSocket) factory.createSocket();
            s.connect(new InetSocketAddress(host, port), 15000);
            s.setSoTimeout(0);
            s.startHandshake();
            socket = s;
            out = new DataOutputStream(s.getOutputStream());
            in = new DataInputStream(s.getInputStream());

            sendConnect();
            // 等 CONNACK
            s.setSoTimeout(15000);
            int type = in.readUnsignedByte();
            if (type != CONNACK) {
                Log.e(TAG, "expect CONNACK, got " + type);
                close();
                return false;
            }
            in.readUnsignedShort();          // 剩余长度
            in.readUnsignedByte();           // flags
            int rc = in.readUnsignedByte();
            if (rc != 0) {
                Log.e(TAG, "connect refused rc=" + rc);
                close();
                return false;
            }
            s.setSoTimeout(0);
            connected = true;
            readerThread = new Thread(this::readLoop, "xz-mqtt-rx");
            readerThread.start();
            Log.i(TAG, "connected to " + host + ":" + port);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "connect failed: " + t);
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
        byte[] vh = new byte[10];
        vh[0] = CONNECT;
        vh[1] = (byte) (payload.length >>> 8);      // 剩余长度（<128 时高字节为 0）
        vh[2] = (byte) (payload.length & 0xff);
        System.arraycopy(protocolName(), 0, vh, 3, 4);  // "MQTT"
        vh[7] = 4;                                   // protocol level 3.1.1
        vh[8] = (byte) 0xC2;                          // connect flags: username+password+clean
        vh[9] = 60;                                  // keepalive
        out.write(vh);
        out.write(payload);
        out.flush();
    }

    private byte[] protocolName() {
        return new byte[]{'M', 'Q', 'T', 'T'};
    }

    private byte[] buildConnectPayload() throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        writeString(bo, clientId);
        writeString(bo, username);
        writeString(bo, password);
        return bo.toByteArray();
    }

    private void writeString(java.io.ByteArrayOutputStream bo, String s) throws Exception {
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
            // 变长剩余长度：topic(2+n) + payload
            int rem = 2 + t.length + p.length;
            byte[] head = new byte[1 + 4 + rem];
            head[0] = PUBLISH;
            int hl = 0;
            byte[] len = encodeRemLen(rem);
            System.arraycopy(len, 0, head, 1, len.length);
            hl = len.length;
            int idx = 1 + hl;
            head[idx++] = (byte) ((t.length >>> 8) & 0xff);
            head[idx++] = (byte) (t.length & 0xff);
            System.arraycopy(t, 0, head, idx, t.length);
            idx += t.length;
            System.arraycopy(p, 0, head, idx, p.length);
            out.write(head, 0, idx + p.length);
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
                if (type == PUBLISH) {
                    byte[] body = new byte[rem];
                    in.readFully(body);
                    // body: topic(2+n) + [packetId(2) if qos>0] + payload
                    int tlen = ((body[0] & 0xff) << 8) | (body[1] & 0xff);
                    int idx = 2 + tlen;
                    String payload = new String(body, idx, body.length - idx, "UTF-8");
                    if (listener != null) listener.onMessage(payload);
                } else if (type == PINGRESP) {
                    // ignore
                } else if (type == CONNACK) {
                    in.skipBytes(rem);
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
                    out.write(PINGREQ);
                    out.write(0);
                    out.flush();
                } catch (Throwable ignored) { }
            }
        }, "xz-mqtt-ping").start();
    }

    public void disconnect() {
        closed = true;
        connected = false;
        try {
            if (out != null) {
                out.write(DISCONNECT);
                out.write(0);
                out.flush();
            }
        } catch (Throwable ignored) { }
        close();
    }

    private void close() {
        try { if (socket != null) socket.close(); } catch (Throwable ignored) { }
        socket = null;
        out = null;
        in = null;
    }
}
