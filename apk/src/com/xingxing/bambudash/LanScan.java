package com.xingxing.bambudash;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 局域网扫描：找出同网段里开着 8883 端口的拓竹打印机。
 *
 * 为什么不用 SSDP
 * ---------------
 * 在 P2S 上实测过：组播、单播 M-SEARCH 都没有任何响应，所以发现只能靠扫端口。
 *
 * 为什么读证书
 * ------------
 * 拓竹打印机的 TLS 证书把序列号写在了 CN 里（issuer 是 "BBL Device CA"），
 * 所以只要握手一次，IP + 序列号就能一起拿到，用户不用再去抄 SN。
 *
 * 纯 JDK 实现，不依赖 Android API，方便在桌面上先验证再打进 APK。
 */
public class LanScan {

    public interface Cb {
        /** done/total 是进度，hit 非空表示刚发现一台 */
        void onProgress(int done, int total, JSONObject hit);

        void onDone(JSONObject result);
    }

    private static final int PORT = 8883;
    private static final int CONNECT_TIMEOUT = 700;   // TCP 连接超时
    private static final int TLS_TIMEOUT = 1800;      // 握手超时（读证书）
    private static final int CONCURRENCY = 64;        // 并发探测数
    private static final int MAX_SUBNETS = 2;         // 有线 + 无线一般就两个

    /** 虚拟网卡名（VPN / 容器 / 隧道），扫了也是浪费时间 */
    private static final String SKIP_IF = "(?i)^(wg\\d|tailscale|zt|ppp|ipsec|ovpn|tun\\d|tap\\d|vpn|nebula|gre\\d|docker|br-|veth|vmnet|utun|lo)";

    /* ---------------- 对外入口 ---------------- */

    public static void scan(final Cb cb) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    cb.onDone(doScan(cb));
                } catch (Exception e) {
                    JSONObject err = new JSONObject();
                    try {
                        err.put("ok", false);
                        err.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                    } catch (Exception ignored) { }
                    cb.onDone(err);
                }
            }
        }, "lan-scan");
        t.setDaemon(true);
        t.start();
    }

    /* ---------------- 主流程 ---------------- */

    private static JSONObject doScan(Cb cb) throws Exception {
        long t0 = System.currentTimeMillis();
        Set<String> prefixes = localSubnets();
        JSONObject out = new JSONObject();

        if (prefixes.isEmpty()) {
            out.put("ok", false);
            out.put("error", "没有拿到本机 IP —— 请确认手机已连上 WiFi（不是只开了流量）");
            return out;
        }

        final List<String> targets = new ArrayList<String>();
        String self = "";
        for (String p : prefixes) {
            for (int h = 1; h <= 254; h++) {
                String ip = p + "." + h;
                if (ip.equals(ownIp)) { self = ip; continue; }
                targets.add(ip);
            }
        }
        final int total = targets.size();
        final AtomicInteger done = new AtomicInteger(0);
        final List<JSONObject> found = Collections.synchronizedList(new ArrayList<JSONObject>());

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        for (final String ip : targets) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    JSONObject hit = probe(ip);
                    if (hit != null) {
                        found.add(hit);
                        cb.onProgress(done.incrementAndGet(), total, hit);
                    } else {
                        int d = done.incrementAndGet();
                        if (d % 16 == 0 || d == total) cb.onProgress(d, total, null);
                    }
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        JSONArray printers = new JSONArray();
        synchronized (found) {
            for (JSONObject o : found) printers.put(o);
        }
        JSONArray subs = new JSONArray();
        for (String p : prefixes) subs.put(p + ".*");

        out.put("ok", true);
        out.put("ms", System.currentTimeMillis() - t0);
        out.put("scanned", total);
        out.put("subnets", subs);
        out.put("self", self);
        out.put("printers", printers);
        return out;
    }

    /* ---------------- 单台探测 ---------------- */

    private static String ownIp = "";

    private static JSONObject probe(String ip) {
        Socket raw = null;
        SSLSocket ssl = null;
        try {
            raw = new Socket();
            raw.connect(new InetSocketAddress(ip, PORT), CONNECT_TIMEOUT);
            raw.setSoTimeout(TLS_TIMEOUT);

            ssl = (SSLSocket) SSL_CTX.getSocketFactory()
                    .createSocket(raw, ip, PORT, true);
            ssl.setSoTimeout(TLS_TIMEOUT);
            ssl.startHandshake();

            String cn = "", issuer = "";
            try {
                java.security.cert.Certificate[] chain = ssl.getSession().getPeerCertificates();
                if (chain != null && chain.length > 0 && chain[0] instanceof X509Certificate) {
                    X509Certificate c = (X509Certificate) chain[0];
                    cn = shortName(c.getSubjectX500Principal().getName());
                    issuer = shortName(c.getIssuerX500Principal().getName());
                }
            } catch (Exception ignored) { }

            // 只认拓竹：证书 issuer 是 BBL Device CA，subject CN 就是序列号
            boolean bambu = issuer.toUpperCase().contains("BBL")
                    || issuer.toUpperCase().contains("BAMBU")
                    || cn.matches("[0-9A-Z]{12,20}");

            JSONObject o = new JSONObject();
            o.put("ip", ip);
            o.put("serial", cn);
            o.put("issuer", issuer);
            o.put("bambu", bambu);
            if (!bambu) {
                // 8883 开着但不是拓竹：有可能是别的设备，仍列出但标记出来
                o.put("serial", "");
            }
            return o;
        } catch (Exception e) {
            return null;    // 端口不通 —— 绝大多数地址走这里
        } finally {
            try { if (ssl != null) ssl.close(); } catch (Exception ignored) { }
            try { if (raw != null) raw.close(); } catch (Exception ignored) { }
        }
    }

    /** "CN=22E8BJ5A2101929, OU=..., O=..." → "22E8BJ5A2101929" */
    private static String shortName(String dn) {
        if (dn == null) return "";
        for (String part : dn.split(",")) {
            String s = part.trim();
            if (s.regionMatches(true, 0, "CN=", 0, 3)) return s.substring(3).trim();
        }
        return "";
    }

    /* ---------------- 本机网段 ---------------- */

    private static Set<String> localSubnets() throws Exception {
        Set<String> set = new LinkedHashSet<String>();
        Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
        while (ifs != null && ifs.hasMoreElements()) {
            NetworkInterface ni = ifs.nextElement();
            if (!ni.isUp() || ni.isLoopback()) continue;
            String name = ni.getName() == null ? "" : ni.getName();
            if (name.matches(SKIP_IF)) continue;
            if (hasVirtualMac(ni)) continue;   // 虚拟网卡常带虚拟 MAC（全 0）
            Enumeration<InetAddress> addrs = ni.getInetAddresses();
            while (addrs.hasMoreElements()) {
                InetAddress a = addrs.nextElement();
                if (!(a instanceof Inet4Address)) continue;
                if (a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                byte[] b = a.getAddress();
                String prefix = (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF);
                if (ownIp.length() == 0) ownIp = a.getHostAddress();
                if (set.size() < MAX_SUBNETS) set.add(prefix);
            }
        }
        return set;
    }

    private static boolean hasVirtualMac(NetworkInterface ni) {
        try {
            byte[] mac = ni.getHardwareAddress();
            // 新版本 Android 对应用隐藏 MAC（返回 null），不能当成虚拟网卡，否则真网卡也被跳过
            if (mac == null) return false;
            for (byte m : mac) if (m != 0) return false;
            return true;                                 // 全 0 的 MAC 基本可以确定是虚拟网卡
        } catch (Exception e) {
            return false;
        }
    }

    private static final SSLContext SSL_CTX;
    static {
        SSLContext c = null;
        try {
            c = SSLContext.getInstance("TLS");
            c.init(null, new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] x, String a) { }
                @Override public void checkServerTrusted(X509Certificate[] x, String a) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new java.security.SecureRandom());
        } catch (Exception e) { /* 不会发生 */ }
        SSL_CTX = c;
    }
}
