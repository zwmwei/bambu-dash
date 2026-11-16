package com.xingxing.bambudash;

/** 桌面验证：启动隧道后用外部播放器（ffmpeg 代替 MediaPlayer）拉流 */
public class TunnelTest {
    public static void main(String[] args) throws Exception {
        String ip = args[0], code = args[1];
        RtspTunnel t = new RtspTunnel(ip, 322, "bblp", code);
        int port = t.start();
        System.out.println("PORT=" + port);
        System.out.println("URL=" + t.localUrl());
        System.out.flush();
        Thread.sleep(600000);
        t.stop();
    }
}
