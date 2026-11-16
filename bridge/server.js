#!/usr/bin/env node
/**
 * 拓竹（Bambu Lab）打印机看板 · 局域网桥接服务
 *
 * 作用：打印机只开放 MQTT over TLS (8883)，浏览器无法直接连，
 *       这里做 MQTT -> WebSocket 的中转，同时托管看板页面。
 *
 * 状态与控制命令在各机型之间是通用的（P1/X1/P2/A/H2 都用同一套 topic）；
 * 唯一有实质差异的是摄像头通道，见 cam.js 里的 MODEL_CAM。
 *
 * 用法：
 *   node server.js              # 正常模式
 *   node server.js --mock       # 模拟数据模式（无需打印机）
 *   PORT=8848 PRINTER_IP=... PRINTER_CODE=... PRINTER_MODEL=P2S node server.js
 */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const url = require('url');
const dgram = require('dgram');
const os = require('os');

const mqtt = require('mqtt');
const { WebSocketServer } = require('ws');
const cam = require('./cam');

const PORT = parseInt(process.env.PORT || '8848', 10);
const WEB_DIR = path.join(__dirname, '..', 'web');
const MOCK = process.argv.includes('--mock');

/**
 * 服务端预置的打印机凭据（NAS / Docker 部署用）。
 * 配了这些环境变量后，前端不需要再填 IP 和访问码，打开就是连好的。
 * 访问码只留在服务端，不下发给浏览器。
 */
const PRESET = {
  ip: (process.env.PRINTER_IP || '').trim(),
  code: (process.env.PRINTER_CODE || '').trim(),
  serial: (process.env.PRINTER_SERIAL || '').trim(),
  // 型号只影响显示名和摄像头通道，留空按未指定处理
  model: (process.env.PRINTER_MODEL || '').trim().toUpperCase()
};

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.webmanifest': 'application/manifest+json'
};

/* ------------------------------------------------------------------ */
/* 打印机会话                                                          */
/* ------------------------------------------------------------------ */
class PrinterSession {
  constructor(cfg) {
    this.cfg = cfg;
    this.client = null;
    this.serial = (cfg.serial || '').trim() || null;
    this.clients = new Set();
    this.lastData = 0;
    this.connected = false;
    this.retry = 0;
    this.timer = null;
    this.stopped = false;
  }

  attach(ws) {
    this.clients.add(ws);
    if (this.lastPayload) send(ws, this.lastPayload);
    send(ws, { type: 'state', online: this.connected });
  }

  detach(ws) { this.clients.delete(ws); }

  broadcast(obj) {
    const s = JSON.stringify(obj);
    for (const c of this.clients) {
      if (c.readyState === 1) c.send(s);
    }
  }

  start() {
    this.stopped = false;
    this.broadcast({ type: 'log', message: `连接 ${this.cfg.ip}:8883 …` });

    const opts = {
      host: this.cfg.ip,
      port: 8883,
      protocol: 'mqtts',
      username: 'bblp',
      password: this.cfg.code,
      rejectUnauthorized: false,      // 打印机用的是自签证书
      servername: this.cfg.ip,
      connectTimeout: 9000,
      reconnectPeriod: 0,             // 自己控制重连节奏
      clientId: 'bambuDash' + Math.random().toString(16).slice(2, 10),
      clean: true
    };

    this.client = mqtt.connect(opts);

    this.client.on('connect', () => {
      this.connected = true;
      this.retry = 0;
      this.broadcast({ type: 'state', online: true });
      this.broadcast({ type: 'log', message: 'MQTT 已连接' });

      // 注意：MQTT 规定 PUBLISH 的 topic 不能含通配符，所以只能订阅通配，
      // 未知序列号时先订阅 device/# ，从 topic 里反解出来。
      const topic = this.serial ? `device/${this.serial}/report` : 'device/#';
      this.client.subscribe(topic, { qos: 0 }, (err) => {
        if (err) this.broadcast({ type: 'error', message: '订阅失败：' + err.message });
        else this.broadcast({ type: 'log', message: '已订阅 ' + topic });
      });

      // 已知序列号才能触发全量推送
      if (this.serial) this.pushall();
      this.warmup = setInterval(() => {
        if (Date.now() - this.lastData > 8000) this.pushall();
      }, 6000);
      setTimeout(() => clearInterval(this.warmup), 24000);
    });

    this.client.on('message', (topic, buf) => {
      const m = /^device\/([^/]+)\/report$/.exec(topic);
      if (!m) return;
      const serial = m[1];
      if (!this.serial) {
        this.serial = serial;
        this.broadcast({ type: 'log', message: '识别到序列号 ' + serial });
        this.pushall();
      }
      let data;
      try { data = JSON.parse(buf.toString('utf8')); } catch (e) { return; }
      if (!data.print) return;

      const p = data.print;
      // 只转发状态推送，ack 类消息忽略（避免把 ack 当状态）
      const isStatus = p.command === 'push_status' || p.command === undefined ||
        typeof p.gcode_state === 'string' || typeof p.mc_percent === 'number';
      if (!isStatus) {
        // 命令回执：把「校验失败」翻译清楚，让前端知道是开发者模式没开
        if (p.command && p.result !== undefined) {
          const verifyFail = p.err_code === 84033543 || /verify failed/i.test(p.reason || '');
          this.broadcast({
            type: 'ack', cmd: p.command, ok: p.result === 'success',
            err: p.err_code || 0, reason: p.reason || '', verifyFail
          });
        }
        return;
      }

      this.lastData = Date.now();
      const payload = { type: 'status', serial, print: p };
      this.lastPayload = payload;
      this.broadcast(payload);
    });

    this.client.on('error', (err) => {
      this.broadcast({ type: 'error', message: 'MQTT 错误：' + err.message });
    });

    this.client.on('close', () => {
      this.connected = false;
      this.broadcast({ type: 'state', online: false });
      if (!this.stopped) this.scheduleReconnect();
    });
  }

  scheduleReconnect() {
    clearTimeout(this.timer);
    const delay = Math.min(15000, 2000 * Math.pow(1.6, this.retry++));
    this.timer = setTimeout(() => {
      if (this.stopped) return;
      this.broadcast({ type: 'log', message: `${Math.round(delay / 1000)}s 后重连…` });
      this.teardown();
      this.start();
    }, delay);
  }

  pushall() {
    if (!this.client || !this.client.connected) return;
    if (!this.serial) return;   // 序列号未知时无法发布（topic 不能带通配符）
    this.client.publish(`device/${this.serial}/request`, JSON.stringify({
      pushing: { sequence_id: '0', command: 'pushall' }
    }));
  }

  command(obj) {
    if (!this.client || !this.client.connected || !this.serial) {
      this.broadcast({ type: 'error', message: '未连接，命令未发送' });
      return;
    }
    this.client.publish(`device/${this.serial}/request`, JSON.stringify(obj));
  }

  teardown() {
    this.stopped = true;
    clearTimeout(this.timer);
    clearInterval(this.warmup);
    if (this.client) {
      try { this.client.removeAllListeners(); this.client.end(true); } catch (e) {}
      this.client = null;
    }
    this.connected = false;
  }
}

/* ------------------------------------------------------------------ */
/* 模拟会话（--mock 或前端演示用）                                      */
/* ------------------------------------------------------------------ */
class MockSession {
  constructor() {
    this.clients = new Set();
    this.p = 42;
    this.timer = setInterval(() => this.tick(), 1200);
  }
  attach(ws) { this.clients.add(ws); send(ws, { type: 'state', online: true }); this.emit(); }
  detach(ws) { this.clients.delete(ws); }
  broadcast(obj) {
    const s = JSON.stringify(obj);
    for (const c of this.clients) if (c.readyState === 1) c.send(s);
  }
  emit() { this.broadcast(this.status()); }
  command() { }
  teardown() { clearInterval(this.timer); }
  tick() {
    this.p = Math.min(100, this.p + 0.25);
    if (this.p >= 100) this.p = 0;
    this.emit();
  }
  status() {
    const t = Date.now() / 1000;
    return {
      type: 'status', serial: 'MOCK0000000001',
      print: {
        command: 'push_status',
        gcode_state: this.p >= 100 ? 'FINISH' : 'RUNNING',
        mc_percent: Math.round(this.p),
        mc_remaining_time: Math.round((100 - this.p) * 1.8),
        layer_num: Math.round(this.p / 100 * 268),
        total_layer_num: 268,
        nozzle_temper: 219.6 + Math.sin(t / 3) * 1.2,
        nozzle_target_temper: 220,
        bed_temper: 59.8 + Math.sin(t / 5) * 0.4,
        bed_target_temper: 60,
        chamber_temper: 31.5 + Math.sin(t / 9) * 0.6,
        subtask_name: 'Benchy_' + (PRESET.model || 'P2S') + '_0.2mm_PLA.3mf',
        wifi_signal: '-49dBm',
        print_error: 0,
        spd_lvl: 2,
        cooling_fan_speed: '70',
        machine_type: PRESET.model || 'P2S',
        ams: {
          tray_now: '0',
          ams: [{
            humidity: '3', temp: '28.0',
            tray: [
              { tray_type: 'PLA', tray_color: 'E8E8E8FF', remain: 82 },
              { tray_type: 'PETG', tray_color: '1A6BD8FF', remain: 46 },
              { tray_type: 'PLA', tray_color: 'D8342BFF', remain: 15 },
              { tray_type: 'PLA-CF', tray_color: '2B2B2BFF', remain: 67 }
            ]
          }]
        }
      }
    };
  }
}

/* ------------------------------------------------------------------ */
/* WebSocket 消息处理                                                  */
/* ------------------------------------------------------------------ */
function send(ws, obj) {
  if (ws.readyState === 1) ws.send(JSON.stringify(obj));
}

let session = null;

function getSession(cfg) {
  if (MOCK) {
    if (!session) session = new MockSession();
    return session;
  }
  // 只是换了型号：摄像头通道跟着变，但 MQTT 连接没必要断掉重建
  if (session && session.cfg && session.cfg.ip === cfg.ip && session.cfg.code === cfg.code &&
      (session.cfg.serial || '') === (cfg.serial || '')) {
    if (cfg.model) session.cfg.model = cfg.model;
    return session;
  }
  if (session) session.teardown();
  session = new PrinterSession(cfg);
  session.start();
  return session;
}

/* ------------------------------------------------------------------ */
/* SSDP 发现                                                            */
/* ------------------------------------------------------------------ */
function discover(timeoutMs = 4000) {
  return new Promise((resolve) => {
    const found = new Map();
    const sock = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    const msg = Buffer.from(
      'M-SEARCH * HTTP/1.1\r\n' +
      'HOST: 239.255.255.250:2021\r\n' +
      'MAN: "ssdp:discover"\r\n' +
      'MX: 3\r\n' +
      'ST: urn:bambu.com:device:Printer:1\r\n\r\n'
    );

    sock.on('message', (buf, rinfo) => {
      const txt = buf.toString('utf8');
      if (!/bambu|3DPrinter/i.test(txt)) return;
      const get = (k) => {
        const m = new RegExp('^' + k + ':[ \\t]*(.+)$', 'mi').exec(txt);
        return m ? m[1].trim() : '';
      };
      const serial = get('USN') || get('DevSerial\\.bambu\\.com');
      found.set(rinfo.address, {
        ip: rinfo.address,
        name: get('DevName\\.bambu\\.com') || get('DevName') || 'Bambu 打印机',
        model: get('DevModel\\.bambu\\.com') || get('DevModel') || '',
        serial: serial.replace(/^uuid:/i, '').replace(/:.*$/, '')
      });
    });
    sock.on('error', () => { });
    sock.bind(() => {
      try { sock.setBroadcast(true); } catch (e) {}
      try { sock.send(msg, 0, msg.length, 2021, '239.255.255.250'); } catch (e) {}
      setTimeout(() => {
        const arr = Array.from(found.values());
        try { sock.close(); } catch (e) {}
        resolve(arr);
      }, timeoutMs);
    });
  });
}

/* ------------------------------------------------------------------ */
/* HTTP 服务                                                           */
/* ------------------------------------------------------------------ */
const server = http.createServer(async (req, res) => {
  const u = url.parse(req.url, true);

  if (u.pathname === '/api/discover') {
    const list = await discover();
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ ok: true, printers: list }));
    return;
  }

  // 前端启动时问这里：服务端有没有预置凭据，能不能直接连
  if (u.pathname === '/api/config') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({
      ok: true,
      mock: MOCK,
      cam: cam.available(),
      autoConnect: !!PRESET.ip,      // 有 IP 就能连（访问码由服务端兜底）
      ip: PRESET.ip,
      serial: PRESET.serial,
      model: PRESET.model            // 空字符串 = 未指定，前端用下拉的默认值
    }));
    return;
  }

  if (u.pathname === '/api/health') {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({
      ok: true, mock: MOCK, cam: cam.available(), model: PRESET.model,
      camChannel: cam.channelOf(session && session.cfg),
      session: session ? { serial: session.serial, online: session.connected, ip: session.cfg && session.cfg.ip } : null
    }));
    return;
  }

  // 摄像头：单帧快照（APK / WebView 走这条）
  if (u.pathname === '/api/cam.jpg') {
    const cfg = session && session.cfg;
    if (!cfg || !cfg.ip) { res.writeHead(503, { 'Content-Type': 'text/plain' }); res.end('未连接打印机'); return; }
    try {
      const jpg = await cam.snapshot(cfg, parseInt(u.query.w || '960', 10));
      res.writeHead(200, { 'Content-Type': 'image/jpeg', 'Cache-Control': 'no-store' });
      res.end(jpg);
    } catch (e) {
      const code = e.code === 'CAM_UNSUPPORTED' ? 501 : 504;
      res.writeHead(code, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('摄像头抓帧失败：' + e.message);
    }
    return;
  }

  // 摄像头：MJPEG 流（普通浏览器 <img> 直接播）
  if (u.pathname === '/api/cam.mjpg') {
    const cfg = session && session.cfg;
    if (!cfg || !cfg.ip) { res.writeHead(503, { 'Content-Type': 'text/plain' }); res.end('未连接打印机'); return; }
    cam.stream(cfg, req, res, { width: parseInt(u.query.w || '640', 10), fps: parseInt(u.query.fps || '12', 10) });
    return;
  }

  // 静态文件
  let p = decodeURIComponent(u.pathname);
  if (p === '/' || p === '') p = '/index.html';
  const file = path.normalize(path.join(WEB_DIR, p));
  if (!file.startsWith(WEB_DIR)) { res.writeHead(403); res.end('forbidden'); return; }

  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('404');
      return;
    }
    res.writeHead(200, {
      'Content-Type': MIME[path.extname(file).toLowerCase()] || 'application/octet-stream',
      'Cache-Control': 'no-cache'
    });
    res.end(data);
  });
});

const wss = new WebSocketServer({ server, path: '/ws' });

wss.on('connection', (ws) => {
  let sess = null;

  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); } catch (e) { return; }

    if (msg.type === 'connect') {
      // 前端没填访问码时，用服务端预置的（NAS 部署场景）
      const ip = (msg.ip || PRESET.ip || '').trim();
      const code = (msg.code || PRESET.code || '').trim();
      const serial = (msg.serial || PRESET.serial || '').trim();
      const model = (msg.model || PRESET.model || '').trim().toUpperCase();
      if (!ip || !code) {
        send(ws, { type: 'error', message: '缺少 IP 或访问码' });
        return;
      }
      sess = getSession({ ip, code, serial, model });
      sess.attach(ws);
    } else if (msg.type === 'command') {
      if (sess) sess.command(msg.payload);
    }
  });

  ws.on('close', () => { if (sess) sess.detach(ws); });
  ws.on('error', () => { if (sess) sess.detach(ws); });
});

/* ------------------------------------------------------------------ */
function localIPs() {
  const out = [];
  const nets = os.networkInterfaces();
  for (const k of Object.keys(nets)) {
    for (const n of nets[k] || []) {
      if (n.family === 'IPv4' && !n.internal) out.push(n.address);
    }
  }
  return out;
}

server.listen(PORT, '0.0.0.0', () => {
  const ips = localIPs();

  // 预置了凭据就主动连上：这样摄像头接口和 /api/health 不依赖浏览器先连
  if (PRESET.ip && !MOCK) {
    try { getSession({ ip: PRESET.ip, code: PRESET.code, serial: PRESET.serial, model: PRESET.model }); } catch (e) {}
  }

  console.log('');
  console.log('  ╭──────────────────────────────────────────────╮');
  console.log('  │  拓竹打印机看板 · 桥接服务                    │');
  console.log('  ╰──────────────────────────────────────────────╯');
  console.log('');
  console.log('  本机访问   http://localhost:' + PORT);
  ips.forEach((ip) => console.log('  手机访问   http://' + ip + ':' + PORT));
  console.log('');
  console.log('  模式       ' + (MOCK ? '模拟数据（--mock）' : '真实打印机 MQTT/TLS 8883'));
  console.log('  机型       ' + (PRESET.model ? PRESET.model + '（摄像头通道 ' + cam.channelOf({ model: PRESET.model }) + '）'
    : '未指定（页面里可选，摄像头按 rtsps 尝试）'));
  console.log('  页面目录   ' + WEB_DIR);
  console.log('  预置凭据   ' + (PRESET.ip
    ? PRESET.ip + '（访问码' + (PRESET.code ? '已配置' : '缺失') + '，打开页面自动连接）'
    : '无（前端手动填写）'));
  console.log('  摄像头     ' + (cam.available() ? 'ffmpeg 就绪' : '未找到 ffmpeg，实时画面不可用'));
  console.log('');
  console.log('  按 Ctrl+C 停止');
  console.log('');
});
