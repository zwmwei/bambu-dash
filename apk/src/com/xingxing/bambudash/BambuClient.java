package com.xingxing.bambudash;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Random;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 极简 MQTT 3.1.1 over TLS 客户端，专为拓竹打印机局域网模式定制。
 * 只做看板需要的三件事：CONNECT、SUBSCRIBE、解析 PUBLISH。
 *
 * 注意：本类刻意不依赖任何 Android API（Handler/Log 都不用），
 * 这样可以拿到桌面 JVM 上直接连真机做协议验证。
 */
public class BambuClient {

    public interface Sink {
        void onJson(String json);   // 交给 WebView 的 JSON 字符串
    }

    private final String ip;
    private final String accessCode;
    private String serial;
    private final Sink sink;

    private volatile boolean stopped = false;
    private volatile SSLSocket sock;
    private DataInputStream in;
    private OutputStream out;
    private Thread worker;
    private int packetId = 1;

    private volatile long lastEmit = 0;

    public BambuClient(String ip, String code, String serial, Sink sink) {
        this.ip = ip;
        this.accessCode = code;
        this.serial = serial == null ? "" : serial.trim();
        this.sink = sink;
    }

    /* ---------------- 对外 ---------------- */

    public void start() {
        stopped = false;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "bambu-mqtt");
        worker.setDaemon(true);
        worker.start();
    }

    public void stop() {
        stopped = true;
        closeQuietly();
        if (worker != null) worker.interrupt();
    }

    public void publishCommand(final String json) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String t = serial != null && serial.length() > 0
                            ? "device/" + serial + "/request"
                            : "device/+/request";
                    publish(t, json, 0);
                } catch (Exception e) {
                    emitError("命令发送失败：" + e.getMessage());
                }
            }
        }).start();
    }

    /* ---------------- 主循环：连接 -> 订阅 -> 读取 -> 断线重连 ---------------- */

    private void loop() {
        int attempt = 0;
        while (!stopped) {
            try {
                connect();
                attempt = 0;
                emit("{\"type\":\"state\",\"online\":true}");
                subscribe();
                pushAll();
                startKeepAlive();
                readLoop();
            } catch (Exception e) {
                if (stopped) break;
                // 连接异常：由 emitError 通知前端
                emit("{\"type\":\"state\",\"online\":false}");
                emitError(prettyError(e));
            } finally {
                closeQuietly();
            }
            if (stopped) break;
            attempt++;
            long delay = Math.min(15000L, 1500L * (long) Math.pow(1.6, Math.min(attempt, 5)));
            try { Thread.sleep(delay); } catch (InterruptedException ie) { break; }
        }
    }

    private String prettyError(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (m.contains("Connection refused")) return "打印机拒绝连接，确认已开启「局域网模式」";
        if (m.contains("timed out") || m.contains("ETIMEDOUT")) return "连接超时，检查 IP 是否在同一局域网";
        if (m.contains("Trust anchor") || m.contains("handshake")) return "TLS 握手失败，检查访问码是否正确";
        return m;
    }

    private void connect() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) { }
            @Override public void checkServerTrusted(X509Certificate[] c, String a) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, new SecureRandom());

        SSLSocketFactory f = ctx.getSocketFactory();
        SSLSocket s = (SSLSocket) f.createSocket();
        s.connect(new InetSocketAddress(ip, 8883), 9000);
        try { s.setEnabledProtocols(new String[]{"TLSv1.2"}); } catch (Exception ignored) { }
        s.setSoTimeout(0);
        s.startHandshake();

        sock = s;
        in = new DataInputStream(s.getInputStream());
        out = s.getOutputStream();

        // ---- CONNECT ----
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeStr(b, "MQTT");
        b.write(4);           // protocol level 3.1.1
        b.write(0xC2);        // username | password | cleanSession
        b.write(0); b.write(60);  // keepalive 60s
        writeStr(b, "bambuDash" + Integer.toHexString(new Random().nextInt(0xFFFF)));
        writeStr(b, "bblp");
        writeStr(b, accessCode);
        send(0x10, b.toByteArray());

        Packet p = readPacket();
        if (p == null || (p.header & 0xF0) != 0x20) throw new IOException("CONNACK 异常");
        int rc = p.body.length > 1 ? (p.body[1] & 0xFF) : 0xFF;
        if (rc == 4) throw new IOException("访问码不正确");
        if (rc == 5) throw new IOException("未授权，检查访问码");
        if (rc != 0) throw new IOException("连接被拒绝 (code " + rc + ")");
    }

    private void subscribe() throws IOException {
        // MQTT 的 PUBLISH topic 不能带通配符，所以未知序列号时只能订阅 device/#
        // 再从收到的 topic 里反解序列号。
        String topic = serial.length() > 0 ? "device/" + serial + "/report" : "device/#";
        int id = nextId();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(id >> 8); b.write(id & 0xFF);
        writeStr(b, topic);
        b.write(0);   // QoS 0
        send(0x82, b.toByteArray());
    }

    private void pushAll() throws IOException {
        // 必须有精确序列号才能发布
        if (serial.length() == 0) return;
        publish("device/" + serial + "/request",
                "{\"pushing\":{\"sequence_id\":\"0\",\"command\":\"pushall\"}}", 0);
    }

    private void startKeepAlive() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!stopped && sock != null && !sock.isClosed()) {
                    try {
                        Thread.sleep(25000);
                        if (stopped || sock == null || sock.isClosed()) break;
                        send(0xC0, new byte[0]);
                    } catch (Exception e) {
                        break;
                    }
                }
            }
        }, "bambu-ping");
        t.setDaemon(true);
        t.start();
    }

    private void readLoop() throws IOException {
        while (!stopped) {
            Packet p = readPacket();
            if (p == null) break;
            int type = p.header & 0xF0;

            if (type == 0x30) {           // PUBLISH
                handlePublish(p);
            } else if (type == 0x90) {    // SUBACK
                // 订阅已确认
            }
        }
    }

    private void handlePublish(Packet p) throws IOException {
        if (p.body.length < 2) return;
        int tl = ((p.body[0] & 0xFF) << 8) | (p.body[1] & 0xFF);
        if (p.body.length < 2 + tl) return;
        String topic = new String(p.body, 2, tl, "UTF-8");
        int off = 2 + tl;
        int qos = (p.header >> 1) & 0x03;
        if (qos > 0) off += 2;                       // packetId
        if (off >= p.body.length) return;
        String payload = new String(p.body, off, p.body.length - off, "UTF-8");

        // device/{serial}/report
        if (!topic.startsWith("device/") || !topic.endsWith("/report")) return;
        String ser = topic.substring(7, topic.length() - 7);
        if (serial.length() == 0 && ser.length() > 0) {
            serial = ser;
            try { pushAll(); } catch (IOException ignored) { }
        }

        JSONObject root;
        try { root = new JSONObject(payload); } catch (Exception e) { return; }
        JSONObject print = root.optJSONObject("print");
        if (print == null) return;

        String cmd = print.optString("command", "");
        boolean isStatus = cmd.equals("push_status") || cmd.length() == 0
                || print.has("gcode_state") || print.has("mc_percent");
        if (!isStatus) return;

        String json = "{\"type\":\"status\",\"serial\":" + JSONObject.quote(serial)
                + ",\"print\":" + print.toString() + "}";
        emitThrottled(json);
    }

    /* ---------------- 数据推送（节流到 400ms） ---------------- */

    private void emitThrottled(String json) {
        long now = System.currentTimeMillis();
        if (now - lastEmit < 400) return;
        lastEmit = now;
        sink.onJson(json);
    }

    private void emit(String json) {
        sink.onJson(json);
    }

    private void emitError(String msg) {
        try {
            emit("{\"type\":\"error\",\"message\":" + JSONObject.quote(msg) + "}");
        } catch (Exception ignored) { }
    }

    /* ---------------- MQTT 报文编解码 ---------------- */

    private static class Packet {
        int header;
        byte[] body;
        Packet(int h, byte[] b) { header = h; body = b; }
    }

    private synchronized int nextId() {
        packetId = packetId % 65535 + 1;
        return packetId;
    }

    private synchronized void send(int header, byte[] body) throws IOException {
        if (out == null) throw new IOException("未连接");
        out.write(header);
        int len = body.length;
        do {
            int d = len % 128;
            len /= 128;
            if (len > 0) d |= 0x80;
            out.write(d);
        } while (len > 0);
        out.write(body);
        out.flush();
    }

    private void publish(String topic, String payload, int qos) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        writeStr(b, topic);
        if (qos > 0) { int id = nextId(); b.write(id >> 8); b.write(id & 0xFF); }
        b.write(payload.getBytes("UTF-8"));
        send(0x30 | (qos << 1), b.toByteArray());
    }

    private Packet readPacket() throws IOException {
        int h = in.read();
        if (h < 0) throw new EOFException("连接已关闭");
        int mult = 1, len = 0, b;
        do {
            b = in.read();
            if (b < 0) throw new EOFException("连接已关闭");
            len += (b & 0x7F) * mult;
            mult *= 128;
            if (mult > 128 * 128 * 128 * 128) throw new IOException("报文长度非法");
        } while ((b & 0x80) != 0);

        byte[] body = new byte[len];
        in.readFully(body);
        return new Packet(h, body);
    }

    private static void writeStr(ByteArrayOutputStream b, String s) throws IOException {
        byte[] d = s.getBytes("UTF-8");
        b.write((d.length >> 8) & 0xFF);
        b.write(d.length & 0xFF);
        b.write(d);
    }

    private void closeQuietly() {
        try { if (out != null) { send(0xE0, new byte[0]); } } catch (Exception ignored) { }
        try { if (sock != null) sock.close(); } catch (Exception ignored) { }
        sock = null; in = null; out = null;
    }
}
