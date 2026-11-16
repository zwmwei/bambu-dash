package com.xingxing.bambudash;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * RTSPS → 本地明文 RTSP 隧道（带 Digest 认证代理）。
 *
 * 解决的问题
 * ----------
 * 打印机 322 端口只收 TLS 加密的 RTSP，且请求行必须是 rtsps:// scheme；
 * 认证是 HTTP Digest（LIVE555），不是 Basic；/streaming/live/1 还会 301 跳转。
 * Android 自带的 MediaPlayer 只会说明文 rtsp://、且不会发 Digest 的 uri 与
 * 真实地址对齐；WebView 更是完全不支持 RTSP。
 *
 * 做法
 * ----
 *   播放器 → rtsp://127.0.0.1:PORT/...  →  本隧道  →  rtsps://打印机:322/...
 *
 * 隧道自己完成 Digest 握手、跟随 301，播放器看到的永远是干净的 200 响应。
 * 播放器不需要知道访问码，也不用处理挑战。
 *
 * 纯 JDK 实现（不用 android.*），可在桌面 JVM 上直接验证。
 */
public class RtspTunnel {

    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final byte[] CRLFCRLF = {13, 10, 13, 10};

    private final String host;
    private final int port;
    private final String user;
    private final String pass;
    private final String entryPath;

    private ServerSocket listener;
    private volatile boolean stopped = false;
    private volatile Socket client;
    private volatile SSLSocket up;
    private volatile OutputStream upOut;

    // Digest 状态
    private volatile String realm = "";
    private volatile String nonce = null;
    private volatile boolean authed = false;

    // 最近一次客户端请求，用于 401/301 后内部重发
    private volatile String pendMethod;
    private volatile String pendPath;
    private volatile String pendHeaders;
    private volatile String pendBody = "";
    private volatile int retries = 0;
    private int transportCh = 0;

    /** true = 视频走 UDP 直连（默认，延迟低）；false = 强制塞进 TCP 隧道 */
    private boolean preferUdp = true;

    public RtspTunnel(String host, int port, String user, String pass) {
        this(host, port, user, pass, "/streaming/live/1");
    }

    public RtspTunnel(String host, int port, String user, String pass, String path) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.pass = pass;
        this.entryPath = path;
    }

    public int start() throws IOException {
        listener = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(new Runnable() {
            public void run() { acceptLoop(); }
        }, "rtsp-tunnel");
        t.setDaemon(true);
        t.start();
        return listener.getLocalPort();
    }

    public String localUrl() {
        return "rtsp://127.0.0.1:" + listener.getLocalPort() + entryPath;
    }

    public void stop() {
        stopped = true;
        closeQuietly(listener);
        closeQuietly(up);
        closeQuietly(client);
    }

    /* ================= 连接管理 ================= */

    private void acceptLoop() {
        while (!stopped) {
            try {
                final Socket c = listener.accept();
                c.setTcpNoDelay(true);
                closeQuietly(client);
                client = c;
                Thread t = new Thread(new Runnable() {
                    public void run() { serve(c); }
                }, "rtsp-serve");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (stopped) return;
            }
        }
    }

    private void serve(Socket c) {
        SSLSocket s = null;
        try {
            s = connectUpstream();
            up = s;
            upOut = s.getOutputStream();

            final InputStream cin = c.getInputStream();
            final InputStream sin = s.getInputStream();
            final OutputStream cout = c.getOutputStream();

            Thread up0 = new Thread(new Runnable() {
                public void run() {
                    try { pipeClient(cin); } catch (Exception e) { }
                }
            }, "rtsp-c2s");
            up0.setDaemon(true);
            up0.start();

            pipeServer(sin, cout);
        } catch (Exception e) {
            // 播放器断开是常态
        } finally {
            closeQuietly(s);
            closeQuietly(c);
        }
    }

    private SSLSocket connectUpstream() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{TRUST_ALL}, new SecureRandom());
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket();
        s.connect(new InetSocketAddress(host, port), 6000);
        s.setSoTimeout(0);
        s.startHandshake();
        return s;
    }

    /* ================= 客户端 → 打印机 ================= */

    private void pipeClient(InputStream in) throws IOException {
        while (!stopped) {
            byte[] headBytes = readHead(in);
            if (headBytes == null) return;
            String head = new String(headBytes, LATIN1);

            int cl = parseHeaderInt(head, "content-length");
            byte[] body = new byte[cl];
            int got = 0;
            while (got < cl) {
                int r = in.read(body, got, cl - got);
                if (r < 0) break;
                got += r;
            }

            int eol = head.indexOf("\r\n");
            if (eol < 0) continue;
            String reqLine = head.substring(0, eol);
            String[] parts = reqLine.split(" ");
            if (parts.length < 2) continue;

            String method = parts[0];
            String url = parts[1];

            // 只接住发往本隧道的请求
            if (!url.startsWith("rtsp://127.0.0.1:" + listener.getLocalPort())) {
                // 别处地址：原样放行（正常不会有）
                writeUp(head + new String(body, 0, got, LATIN1));
                continue;
            }

            String path = pathOf(url);
            pendMethod = method;
            pendPath = path;
            pendHeaders = stripAuth(head);
            // 默认保留客户端原始的 UDP 协商：实测打印机支持 UDP 传 RTP，
            // 视频直连手机比塞进 TCP 隧道延迟低，也不用伪装传输方式
            // （客户端收到与请求不符的 Transport 会直接报错不播）。
            if (!preferUdp) pendHeaders = forceTcpTransport(pendHeaders);
            pendBody = new String(body, 0, got, LATIN1);
            retries = 0;

            sendUpstream();
        }
    }

    /** 按当前 path 与认证状态构造并发出上游请求 */
    private void sendUpstream() {
        synchronized (RtspTunnel.class) {
            if (upOut == null || pendMethod == null) return;
            StringBuilder sb = new StringBuilder();
            sb.append(pendMethod).append(' ').append(upUrl(pendPath)).append(" RTSP/1.0\r\n");
            sb.append(pendHeaders);
            String digest = buildDigest(pendMethod, upUrl(pendPath));
            if (digest != null) sb.append("Authorization: ").append(digest).append("\r\n");
            sb.append("\r\n");
            if (pendBody.length() > 0) sb.append(pendBody);
            try {
                upOut.write(sb.toString().getBytes(LATIN1));
                upOut.flush();
            } catch (IOException e) { }
        }
    }

    /* ================= 打印机 → 客户端 ================= */

    private void pipeServer(InputStream in, OutputStream out) throws IOException {
        java.io.BufferedInputStream bin = new java.io.BufferedInputStream(in, 65536);
        int localPort = listener.getLocalPort();

        while (!stopped) {
            int c = bin.read();
            if (c < 0) return;

            // TCP 交织的媒体帧：'$' + 通道 + 2 字节长度 + 数据
            if (c == '$') {
                int ch = bin.read();
                int hi = bin.read(), lo = bin.read();
                if (ch < 0 || hi < 0 || lo < 0) return;
                int len = (hi << 8) | lo;
                out.write('$');
                out.write(ch);
                out.write(hi);
                out.write(lo);
                copyN(bin, out, len);
                out.flush();
                continue;
            }

            byte[] headBytes = readHeadPrecededBy(bin, c);
            if (headBytes == null) return;
            String head = new String(headBytes, LATIN1);
            int cl = parseHeaderInt(head, "content-length");
            byte[] body = new byte[cl];
            int got = 0;
            while (got < cl) {
                int r = bin.read(body, got, cl - got);
                if (r < 0) break;
                got += r;
            }
            String bodyStr = new String(body, 0, got, LATIN1);

            int status = statusOf(head);

            // 401：解析 Digest 挑战，内部重发，不惊动播放器
            if (status == 401 && retries < 4) {
                String wa = headerValue(head, "www-authenticate");
                if (wa != null && wa.toLowerCase().indexOf("digest") >= 0) {
                    realm = attr(wa, "realm");
                    nonce = attr(wa, "nonce");
                    authed = true;
                    retries++;
                    sendUpstream();
                    continue;
                }
            }

            // 301/302：跟随重定向，同样内部消化
            if ((status == 301 || status == 302) && retries < 4) {
                String loc = headerValue(head, "location");
                if (loc != null) {
                    String p = pathOf(loc.trim());
                    if (p.length() > 0 && !p.equals(pendPath)) {
                        pendPath = p;
                        retries++;
                        sendUpstream();
                        continue;
                    }
                }
            }

            if (status == 200) retries = 0;

            // 改写响应里的绝对地址，防止播放器绕过隧道
            String headOut = rewriteDown(head, localPort);
            String bodyOut = rewriteDown(bodyStr, localPort);
            byte[] bodyBytes = bodyOut.getBytes(LATIN1);
            if (cl != bodyBytes.length) {
                headOut = headOut.replaceAll("(?i)(\\r\\ncontent-length\\s*:\\s*)\\d+", "$1" + bodyBytes.length);
            }
            out.write(headOut.getBytes(LATIN1));
            out.write(bodyBytes);
            out.flush();
        }
    }

    /* ================= 工具 ================= */

    private String upUrl(String path) {
        return "rtsps://" + host + ":" + port + path;
    }

    /** rtsp://127.0.0.1:1234/streaming/live/1 → /streaming/live/1 */
    private String pathOf(String url) {
        int i = url.indexOf("://");
        if (i < 0) return url;
        int j = url.indexOf('/', i + 3);
        return j < 0 ? "/" : url.substring(j);
    }

    private String rewriteDown(String s, int localPort) {
        if (s == null || s.length() == 0) return s;
        String local = "rtsp://127.0.0.1:" + localPort;
        String q = java.util.regex.Pattern.quote(host);
        s = s.replaceAll("(?i)rtsps?://" + q + ":\\d+", local);
        s = s.replaceAll("(?i)rtsps?://" + q + "(?=[/\\s\\r\\n\"]|$)", local);
        return s;
    }

    /** 去掉客户端的 Authorization（用自己的 Digest 替换） */
    private String stripAuth(String head) {
        int eol = head.indexOf("\r\n");
        if (eol < 0) return head;
        String rest = head.substring(eol + 2);
        StringBuilder sb = new StringBuilder();
        for (String line : rest.split("\r\n")) {
            if (line.length() == 0) continue;
            if (line.toLowerCase().startsWith("authorization:")) continue;
            sb.append(line).append("\r\n");
        }
        return sb.toString();
    }

    /**
     * 强制 TCP 交织传输。
     * 安卓 MediaPlayer 默认请求 UDP（client_port=...），但设备侧 UDP 常被路由器
     * 或防火墙挡掉；改成 interleaved 后音视频走同一条 TCP 连接，穿透性好得多。
     * 服务端响应里会回带 TCP 标记，播放器据此切到 TCP 读帧模式。
     */
    private String forceTcpTransport(String head) {
        StringBuilder sb = new StringBuilder();
        for (String line : head.split("\r\n")) {
            if (line.length() == 0) continue;
            if (line.toLowerCase().startsWith("transport:")) {
                String v = line.toLowerCase();
                if (v.indexOf("/tcp") >= 0) {
                    sb.append(line).append("\r\n");
                } else {
                    int ch = (transportCh & 0xFE);
                    sb.append("Transport: RTP/AVP/TCP;unicast;interleaved=")
                      .append(ch).append('-').append(ch + 1).append("\r\n");
                    transportCh = (transportCh + 2) & 0x7E;
                }
                continue;
            }
            sb.append(line).append("\r\n");
        }
        return sb.toString();
    }

    private String buildDigest(String method, String uri) {
        if (!authed || nonce == null) return null;
        try {
            String ha1 = md5(user + ":" + realm + ":" + pass);
            String ha2 = md5(method + ":" + uri);
            String resp = md5(ha1 + ":" + nonce + ":" + ha2);
            return "Digest username=\"" + user + "\", realm=\"" + realm +
                    "\", nonce=\"" + nonce + "\", uri=\"" + uri +
                    "\", response=\"" + resp + "\"";
        } catch (Exception e) {
            return null;
        }
    }

    private static String md5(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] d = md.digest(s.getBytes(LATIN1));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            String h = Integer.toHexString(b & 0xFF);
            if (h.length() == 1) sb.append('0');
            sb.append(h);
        }
        return sb.toString();
    }

    private static String attr(String header, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)" + key + "\\s*=\\s*\"([^\"]*)\"").matcher(header);
        return m.find() ? m.group(1) : "";
    }

    private static int statusOf(String head) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("RTSP/1\\.0\\s+(\\d{3})").matcher(head);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static int parseHeaderInt(String head, String name) {
        String v = headerValue(head, name);
        if (v == null) return 0;
        try { return Integer.parseInt(v.trim()); } catch (Exception e) { return 0; }
    }

    private static String headerValue(String head, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)\\r\\n" + java.util.regex.Pattern.quote(name) + "\\s*:\\s*([^\\r\\n]*)")
                .matcher(head);
        return m.find() ? m.group(1).trim() : null;
    }

    /** 读一段请求/响应头（含结尾空行） */
    private static byte[] readHead(InputStream in) throws IOException {
        int c = in.read();
        if (c < 0) return null;
        return readHeadPrecededBy(in, c);
    }

    /** 首字节已读，继续读到 \r\n\r\n */
    private static byte[] readHeadPrecededBy(InputStream in, int first) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(first);
        int state = (first == '\r') ? 1 : 0;
        while (true) {
            int c = in.read();
            if (c < 0) return b.size() > 0 ? b.toByteArray() : null;
            b.write(c);
            if ((state == 0 || state == 2) && c == '\r') state++;
            else if ((state == 1 || state == 3) && c == '\n') state++;
            else state = (c == '\r') ? 1 : 0;
            if (state == 4) break;
            if (b.size() > 65536) break;
        }
        return b.toByteArray();
    }

    private static void copyN(InputStream in, OutputStream out, int n) throws IOException {
        byte[] buf = new byte[16384];
        int left = n;
        while (left > 0) {
            int r = in.read(buf, 0, Math.min(buf.length, left));
            if (r < 0) return;
            out.write(buf, 0, r);
            left -= r;
        }
    }

    private static void closeQuietly(Object o) {
        try {
            if (o instanceof Socket) ((Socket) o).close();
            else if (o instanceof ServerSocket) ((ServerSocket) o).close();
        } catch (Exception e) { }
    }

    private static final X509TrustManager TRUST_ALL = new X509TrustManager() {
        public void checkClientTrusted(X509Certificate[] c, String a) { }
        public void checkServerTrusted(X509Certificate[] c, String a) { }
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };

    /** 隧道里发往上行的原始写入（供调试） */
    private void writeUp(String data) {
        try {
            if (upOut != null) upOut.write(data.getBytes(LATIN1));
        } catch (IOException e) { }
    }
}
