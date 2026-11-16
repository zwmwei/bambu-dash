'use strict';
/**
 * 局域网打印机发现
 *
 * 为什么不走 SSDP / mDNS：
 *   实测 P2S 对 SSDP（239.255.255.250:2021）的 M-SEARCH 无响应，
 *   mDNS 也没有广播。这条路在拓竹机器上不通。
 *
 * 实际可用的办法：扫 8883 端口。
 *   局域网模式下打印机必定监听 8883（MQTT over TLS），
 *   而且 TLS 是自签设备证书，特征非常明确：
 *     issuer = "BBL Device CA N7-V2"（BBL = Bambu Lab）
 *     subject CN = 打印机序列号
 *   所以扫到端口后握一次手，既确认了身份，又白捡了序列号。
 *
 * 只探这一个端口，只扫本机所在网段，不做全端口扫描。
 */

const net = require('net');
const tls = require('tls');
const os = require('os');

const MQTT_TLS_PORT = 8883;

/** 本机所有可扫描的 /24 前缀（跳过回环、docker 网桥、VPN 隧道、link-local） */
function localSubnets() {
  const out = [];
  const ifaces = os.networkInterfaces();
  for (const name of Object.keys(ifaces)) {
    // 实测 NAS 上 wg0（WireGuard，10.8.0.1/24）会被误当成局域网多扫一轮，
    // 所以 VPN / 隧道类网卡一律排除。enp3s0-ovs 这种真实网卡要保留。
    if (/^(docker|br-|veth|virbr|lo\b|utun|tun|tap|wg\d|tailscale|zt|ppp|ipsec|ovpn|vpn|nebula|gre\d)/i.test(name)) continue;
    for (const a of ifaces[name] || []) {
      if (a.family !== 'IPv4' || a.internal) continue;
      if (a.address.startsWith('127.') || a.address.startsWith('169.254.')) continue;
      const prefix = a.address.split('.').slice(0, 3).join('.');
      if (!out.includes(prefix)) out.push(prefix);
    }
  }
  // 兜底：网卡特别多的机器最多扫两个网段，避免一轮扫上千个地址
  return out.slice(0, 2);
}

/** 探测单个 IP 的端口是否开放 */
function probePort(ip, port, timeout) {
  return new Promise((resolve) => {
    const sock = new net.Socket();
    let settled = false;
    const done = (open) => {
      if (settled) return;
      settled = true;
      sock.destroy();
      resolve(open);
    };
    sock.setTimeout(timeout);
    sock.once('connect', () => done(true));
    sock.once('timeout', () => done(false));
    sock.once('error', () => done(false));
    try { sock.connect(port, ip); } catch (e) { done(false); }
  });
}

/** 做一次 TLS 握手，取设备证书的关键字段 */
function readCertificate(ip, port, timeout) {
  return new Promise((resolve) => {
    let settled = false;
    const done = (v) => { if (!settled) { settled = true; resolve(v); } };
    let sock;
    try {
      sock = tls.connect({
        host: ip,
        port,
        rejectUnauthorized: false,   // 自签证书，本来就验不过
        timeout
      }, () => {
        let cert = null;
        try { cert = sock.getPeerCertificate(); } catch (e) {}
        sock.destroy();
        if (!cert || !cert.subject) return done(null);
        done({
          cn: String(cert.subject.CN || ''),
          issuer: String(cert.issuer && cert.issuer.CN || ''),
          org: String(cert.subject.O || ''),
          san: String(cert.subjectaltname || ''),
          validTo: String(cert.valid_to || '')
        });
      });
    } catch (e) {
      return done(null);
    }
    sock.once('error', () => done(null));
    sock.setTimeout(timeout, () => { sock.destroy(); done(null); });
  });
}

/** 判断这张证书是否是拓竹设备 */
function looksLikeBambu(cert) {
  if (!cert) return false;
  // 最硬的证据：签发者是 Bambu Lab 的设备 CA
  if (/BBL|Bambu/i.test(cert.issuer)) return true;
  if (/BBL|Bambu/i.test(cert.org)) return true;
  // 退一步看序列号格式：15~16 位大写字母数字
  if (/^[0-9A-Z]{15,16}$/.test(cert.cn)) return true;
  return false;
}

function ipWeight(ip) {
  return ip.split('.').reduce((n, x) => n * 256 + (parseInt(x, 10) || 0), 0);
}

/**
 * 扫描局域网里的拓竹打印机
 * @param {object} opts
 *   prefix       指定网段前缀，如 "192.168.1"；不传则自动取本机网段
 *   timeout      单个地址的连接超时，默认 700ms
 *   concurrency  并发数，默认 64
 *   deadline     整体超时，默认 20s
 */
async function scan(opts) {
  const o = opts || {};
  const timeout = o.timeout || 700;
  const concurrency = o.concurrency || 64;
  const deadline = o.deadline || 20000;
  const startedAt = Date.now();

  const prefixes = o.prefix ? [o.prefix] : localSubnets();
  if (!prefixes.length) {
    return {
      ok: false,
      error: '没有找到可扫描的网段（没有可用的局域网网卡）',
      printers: [],
      ms: Date.now() - startedAt
    };
  }

  const targets = [];
  for (const p of prefixes) {
    for (let i = 1; i <= 254; i++) targets.push(p + '.' + i);
  }

  // 分批并发，避免一次开 254 个 socket 撞到 fd 上限
  const openIps = [];
  for (let i = 0; i < targets.length; i += concurrency) {
    if (Date.now() - startedAt > deadline) break;
    const batch = targets.slice(i, i + concurrency);
    const results = await Promise.all(
      batch.map((ip) => probePort(ip, MQTT_TLS_PORT, timeout))
    );
    results.forEach((isOpen, k) => { if (isOpen) openIps.push(batch[k]); });
  }

  // 开放的端口逐个握手确认身份
  const printers = [];
  await Promise.all(openIps.map(async (ip) => {
    const cert = await readCertificate(ip, MQTT_TLS_PORT, 4000);
    if (!looksLikeBambu(cert)) return;
    printers.push({
      ip,
      port: MQTT_TLS_PORT,
      serial: cert.cn || '',
      issuer: cert.issuer || '',
      validTo: cert.validTo || ''
    });
  }));

  printers.sort((a, b) => ipWeight(a.ip) - ipWeight(b.ip));

  return {
    ok: true,
    printers,
    scanned: targets.length,
    subnets: prefixes,
    ms: Date.now() - startedAt
  };
}

module.exports = { scan, localSubnets, MQTT_TLS_PORT };
