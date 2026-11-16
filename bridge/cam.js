'use strict';
/**
 * 摄像头通道：P2S 内置摄像头走 RTSPS 322（TLS），
 * 浏览器不能直接拉 RTSP，这里用 ffmpeg 转成 MJPEG。
 *
 *   /api/cam.jpg    抓单帧（APK / WebView 用轮询）
 *   /api/cam.mjpg   MJPEG 长连接（普通浏览器 <img> 直接能播）
 *
 * 实测 P2S 是 1920x1080 @ 30fps，比 P1S 的 0.5fps 强得多。
 *
 * ── 关于 -tls_verify（踩过的坑）────────────────────────────────
 * 打印机用的是自签证书，必须不校验。但两个 ffmpeg 版本的行为是相反的：
 *   · ffmpeg 5.x（Debian 12 自带 5.1）：不认 -tls_verify 这个输入选项，
 *     写了直接报 "Option tls_verify not found" 退出（表现为摄像头 504）；
 *     但它默认就不校验证书，不写正好。
 *   · ffmpeg 6+（Windows 上的 9.x）：认这个选项，但默认改成校验证书了，
 *     不写会报 "Peer certificate failed verification"。
 * 所以按主版本号自适应，另外抓帧失败时再看一眼错误信息兜底。
 */
const { spawn, spawnSync } = require('child_process');

let FFMPEG;          // undefined=未探测, ''=不可用, 其他=路径
let FFMAJOR = -1;    // -1=未探测, 0=没解析出来, N=主版本号
let TLS_OFF = null;  // null=未决定, true=需要 -tls_verify 0, false=不能加

function findFfmpeg() {
  if (FFMPEG !== undefined) return FFMPEG;
  const cands = [
    'ffmpeg',
    process.env.FFMPEG_PATH,
    'C:/Users/river/AppData/Local/Microsoft/WinGet/Packages/Gyan.FFmpeg_Microsoft.Winget.Source_8wekyb3d8bbwe/ffmpeg-9.0.2-full_build/bin/ffmpeg.exe',
    'C:/ffmpeg/bin/ffmpeg.exe',
    '/usr/bin/ffmpeg',
    '/usr/local/bin/ffmpeg',
    '/opt/homebrew/bin/ffmpeg'
  ].filter(Boolean);

  for (const c of cands) {
    try {
      const r = spawnSync(c, ['-version'], { timeout: 5000, stdio: 'ignore' });
      if (r.status === 0) { FFMPEG = c; return c; }
    } catch (e) { /* 继续试下一个 */ }
  }
  FFMPEG = '';
  return '';
}

/** ffmpeg 主版本号；解析不出来返回 0，nightly(N-xxx) 当作很新 */
function ffMajor() {
  if (FFMAJOR >= 0) return FFMAJOR;
  const exe = findFfmpeg();
  if (!exe) return (FFMAJOR = 0);
  try {
    const r = spawnSync(exe, ['-version'], { timeout: 5000, encoding: 'utf8' });
    const s = (r.stdout || '') + (r.stderr || '');
    const m = /ffmpeg version (\d+)\./.exec(s);
    if (m) return (FFMAJOR = parseInt(m[1], 10));
    if (/ffmpeg version N-/.test(s)) return (FFMAJOR = 999);
  } catch (e) { /* 落到 0 */ }
  return (FFMAJOR = 0);
}

function tlsArgs() {
  if (TLS_OFF === null) TLS_OFF = ffMajor() >= 6;
  return TLS_OFF ? ['-tls_verify', '0'] : [];
}

function available() { return findFfmpeg() !== ''; }

function rtspUrl(cfg) {
  const code = encodeURIComponent(cfg.code);
  return `rtsps://bblp:${code}@${cfg.ip}:322/streaming/live/1`;
}

function commonArgs(cfg) {
  return [
    '-rtsp_transport', 'tcp',
    ...tlsArgs(),
    '-i', rtspUrl(cfg)
  ];
}

/** 抓单帧，返回 JPEG Buffer（遇到 tls_verify 不兼容时自动去掉参数重试一次） */
function snapshot(cfg, width) {
  return shot(cfg, width).catch((e) => {
    if (TLS_OFF && /tls_verify/i.test(e.message)) {
      TLS_OFF = false;
      return shot(cfg, width);
    }
    throw e;
  });
}

function shot(cfg, width) {
  return new Promise((resolve, reject) => {
    const exe = findFfmpeg();
    if (!exe) return reject(new Error('未找到 ffmpeg'));

    const ff = spawn(exe, [
      ...commonArgs(cfg),
      '-frames:v', '1',
      '-vf', `scale=${width || 960}:-2`,
      '-q:v', '4',
      '-f', 'image2pipe', '-vcodec', 'mjpeg',
      'pipe:1'
    ], { windowsHide: true });

    const bufs = [];
    let errBuf = '';
    let settled = false;
    const done = (err, buf) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      err ? reject(err) : resolve(buf);
    };

    ff.stdout.on('data', d => bufs.push(d));
    ff.stderr.on('data', (d) => {          // 必须消费，否则管道写满会卡住
      if (errBuf.length < 8192) errBuf += d.toString();
    });
    ff.on('error', e => done(e));
    ff.on('close', () => {
      const b = Buffer.concat(bufs);
      if (b.length) return done(null, b);
      // 把 ffmpeg 的原话带出来，不然只剩一句「无画面」很难查
      const tail = errBuf.trim().split('\n').slice(-3).join(' | ');
      done(new Error('摄像头无画面' + (tail ? '：' + tail : '')));
    });

    const timer = setTimeout(() => {
      try { ff.kill('SIGKILL'); } catch (e) {}
      done(new Error('抓帧超时'));
    }, 12000);
  });
}

/** MJPEG 长连接：把 ffmpeg 输出的连续 JPEG 按帧切给浏览器 */
function stream(cfg, req, res, opts) {
  const exe = findFfmpeg();
  if (!exe) { res.writeHead(503); res.end('ffmpeg 不可用'); return null; }

  const o = opts || {};
  res.writeHead(200, {
    'Content-Type': 'multipart/x-mixed-replace; boundary=frame',
    'Cache-Control': 'no-cache, no-store, must-revalidate',
    Pragma: 'no-cache',
    Connection: 'close'
  });

  const ff = spawn(exe, [
    ...commonArgs(cfg),
    '-vf', `scale=${o.width || 640}:-2`,
    '-r', String(o.fps || 12),
    '-q:v', '7',
    '-f', 'mjpeg',
    'pipe:1'
  ], { windowsHide: true });

  const SOI = Buffer.from([0xFF, 0xD8]);
  const EOI = Buffer.from([0xFF, 0xD9]);
  let buf = Buffer.alloc(0);

  ff.stdout.on('data', (chunk) => {
    buf = Buffer.concat([buf, chunk]);
    // 防御：单帧异常巨大时清空，避免内存失控
    if (buf.length > 8 * 1024 * 1024) { buf = Buffer.alloc(0); return; }

    for (;;) {
      const s = buf.indexOf(SOI);
      if (s < 0) { buf = Buffer.alloc(0); break; }
      if (s > 0) buf = buf.subarray(s);
      const e = buf.indexOf(EOI, 2);
      if (e < 0) break;                     // 帧还没收全，等下一块

      const jpg = buf.subarray(0, e + 2);
      buf = buf.subarray(e + 2);
      try {
        res.write(`--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpg.length}\r\n\r\n`);
        res.write(jpg);
        res.write('\r\n');
      } catch (err) { break; }
    }
  });

  let errBuf = '';
  ff.stderr.on('data', (d) => {
    if (errBuf.length < 8192) errBuf += d.toString();
  });

  const kill = () => { try { ff.kill('SIGKILL'); } catch (e) {} };
  req.on('close', kill);
  req.on('aborted', kill);
  res.on('close', kill);
  ff.on('close', () => {
    if (errBuf) console.log('[cam] ffmpeg: ' + errBuf.trim().split('\n').slice(-2).join(' | '));
    try { res.end(); } catch (e) {}
  });
  ff.on('error', () => { try { res.end(); } catch (e) {} });

  return { kill };
}

module.exports = { available, snapshot, stream, findFfmpeg };
