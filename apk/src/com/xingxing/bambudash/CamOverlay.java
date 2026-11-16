package com.xingxing.bambudash;

import android.content.Context;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 摄像头全屏播放层。
 *
 * 视频来自本机 RtspTunnel 提供的明文 RTSP，MediaPlayer 负责解码——
 * 这样不用自己写 RTSP/RTP 解包和 H.264 解码，省下几百行且更稳。
 */
public class CamOverlay extends FrameLayout implements SurfaceHolder.Callback {

    private final AspectSurfaceView surface;
    private final TextView tip;
    private final TextView close;

    private RtspTunnel tunnel;
    private MediaPlayer player;
    private String pendingUrl;
    private volatile int session = 0;

    public CamOverlay(Context c) {
        super(c);
        setBackgroundColor(Color.BLACK);
        setVisibility(GONE);
        setClickable(true);

        surface = new AspectSurfaceView(c);
        surface.getHolder().addCallback(this);
        LayoutParams lp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        addView(surface, lp);

        tip = new TextView(c);
        tip.setTextColor(0xFFB8C6D4);
        tip.setTextSize(13);
        tip.setGravity(Gravity.CENTER);
        LayoutParams tlp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER;
        addView(tip, tlp);

        close = new TextView(c);
        close.setText("✕");
        close.setTextColor(Color.WHITE);
        close.setTextSize(15);
        close.setGravity(Gravity.CENTER);
        close.setBackgroundColor(0x66000000);
        LayoutParams clp = new LayoutParams(dp(40), dp(40));
        clp.gravity = Gravity.TOP | Gravity.RIGHT;
        addView(close, clp);
        close.setOnClickListener(new OnClickListener() {
            public void onClick(View v) { hide(); }
        });
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    /** 打开摄像头：ip 是打印机的，code 是访问码 */
    public void open(final String ip, String code) {
        hideOnlyPlayer();
        setVisibility(VISIBLE);
        tip.setText("连接摄像头…");

        // 画面是 16:9，横过来看更实用；看板本身仍是竖屏
        setActivityOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);

        final String accessCode = code;
        final int mySession = ++session;

        // 先探一下 322 端口：IP 填错（比如填成了 NAS 地址）时立刻说明白，
        // 否则 MediaPlayer 只会干等十几秒再丢一个 (1/-2147483648) 的玄学错误码。
        new Thread(new Runnable() {
            @Override
            public void run() {
                String why = probe(ip, 322);
                if (mySession != session) return;          // 用户已经关掉了
                if (why != null) {
                    setTip("连不上摄像头（" + ip + ":322）\n" + why +
                            "\n\n若状态正常说明 IP 没错，请确认「局域网模式」已允许实况。");
                    return;
                }
                setTip("握手…");
                try {
                    tunnel = new RtspTunnel(ip, 322, "bblp", accessCode);
                    tunnel.start();
                    pendingUrl = tunnel.localUrl();
                } catch (Exception e) {
                    setTip("启动失败：" + e.getMessage());
                    return;
                }
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (mySession != session) return;
                        if (surface.getHolder().getSurface() != null
                                && surface.getHolder().getSurface().isValid()) {
                            playPending();
                        }
                    }
                });
            }
        }, "cam-probe").start();
    }

    /** 返回 null 表示端口通，否则返回一句人话原因 */
    private static String probe(String ip, int port) {
        java.net.Socket s = null;
        try {
            s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress(ip, port), 1800);
            return null;
        } catch (java.net.SocketTimeoutException e) {
            return "端口没有响应，可能不是这台设备的地址";
        } catch (Exception e) {
            return "端口被拒绝，这个 IP 大概不是打印机";
        } finally {
            try { if (s != null) s.close(); } catch (Exception ignored) { }
        }
    }

    private void setTip(final String msg) {
        post(new Runnable() {
            @Override
            public void run() { tip.setText(msg); }
        });
    }

    public void hide() {
        hideOnlyPlayer();
        setVisibility(GONE);
        setActivityOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    private void setActivityOrientation(int o) {
        try {
            if (getContext() instanceof android.app.Activity) {
                ((android.app.Activity) getContext()).setRequestedOrientation(o);
            }
        } catch (Exception ignored) { }
    }

    public boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    private void hideOnlyPlayer() {
        session++;                 // 让还在探测中的线程作废
        pendingUrl = null;
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        if (tunnel != null) {
            try { tunnel.stop(); } catch (Exception ignored) { }
            tunnel = null;
        }
    }

    private void playPending() {
        final String url = pendingUrl;
        if (url == null) return;
        pendingUrl = null;

        try {
            if (player != null) {
                try { player.release(); } catch (Exception ignored) { }
            }
            player = new MediaPlayer();
            player.setSurface(surface.getHolder().getSurface());
            player.setDataSource(url);
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer mp) {
                    mp.start();
                    tip.setText("");
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    tip.setText("播放失败（" + what + " / " + extra + "）\n"
                            + "端口是通的，多为访问码不对或打印机忙\n"
                            + "可先在电脑上开网页版对比一下");
                    return true;
                }
            });
            player.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                public boolean onInfo(MediaPlayer mp, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) tip.setText("缓冲中…");
                    else if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) tip.setText("");
                    return false;
                }
            });
            player.prepareAsync();
        } catch (Exception e) {
            tip.setText("播放失败：" + e.getMessage());
        }
    }

    /* ---- SurfaceHolder.Callback ---- */

    public void surfaceCreated(SurfaceHolder h) {
        if (pendingUrl != null) playPending();
    }

    public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }

    public void surfaceDestroyed(SurfaceHolder h) { }
}
