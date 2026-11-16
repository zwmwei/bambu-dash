// 生成应用图标（无第三方依赖，手写 PNG 编码）
const zlib = require('zlib');
const fs = require('fs');
const path = require('path');

const CRC_TABLE = (() => {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xFF] ^ (c >>> 8);
  return (c ^ 0xFFFFFFFF) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const t = Buffer.from(type, 'ascii');
  const body = Buffer.concat([t, data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body), 0);
  return Buffer.concat([len, body, crc]);
}

function encodePNG(w, h, rgba) {
  const sig = Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;   // bit depth
  ihdr[9] = 6;   // RGBA
  ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;

  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    rgba.copy(raw, y * (w * 4 + 1) + 1, y * w * 4, (y + 1) * w * 4);
  }
  const idat = zlib.deflateSync(raw, { level: 9 });
  return Buffer.concat([sig, chunk('IHDR', ihdr), chunk('IDAT', idat), chunk('IEND', Buffer.alloc(0))]);
}

function draw(size) {
  const SS = 4;                       // 超采样倍数，抗锯齿
  const N = size * SS;
  const buf = Buffer.alloc(N * N * 4);

  const cx = N / 2, cy = N / 2;
  const radius = N * 0.19;            // 圆角矩形半径
  const ringR = N * 0.30;             // 进度环半径
  const ringW = N * 0.062;
  const arcDeg = 268;                 // 环的弧度

  const BG = [14, 19, 24];
  const GREEN = [0, 193, 106];
  const GREEN2 = [0, 224, 122];
  const WHITE = [233, 239, 246];

  function inRoundedRect(x, y) {
    const l = N * 0.055, r = N - l, t = l, b = N - l;
    if (x < l || x > r || y < t || y > b) return false;
    const rx = Math.min(x - l, r - x), ry = Math.min(y - t, b - y);
    if (rx >= radius || ry >= radius) return true;
    const dx = radius - rx, dy = radius - ry;
    return dx * dx + dy * dy <= radius * radius;
  }

  for (let y = 0; y < N; y++) {
    for (let x = 0; x < N; x++) {
      const i = (y * N + x) * 4;
      if (!inRoundedRect(x, y)) { buf[i + 3] = 0; continue; }

      const dx = x - cx, dy = y - cy;
      const d = Math.sqrt(dx * dx + dy * dy);

      // 环角度：从正上方顺时针
      let ang = Math.atan2(dy, dx) + Math.PI / 2;
      if (ang < 0) ang += Math.PI * 2;
      const deg = ang * 180 / Math.PI;

      let col = BG.slice();

      if (Math.abs(d - ringR) <= ringW / 2 && deg <= arcDeg) {
        const k = Math.min(1, Math.max(0, deg / arcDeg));
        col = [
          Math.round(GREEN[0] + (GREEN2[0] - GREEN[0]) * k),
          Math.round(GREEN[1] + (GREEN2[1] - GREEN[1]) * k),
          Math.round(GREEN[2] + (GREEN2[2] - GREEN[2]) * k)
        ];
      } else if (d <= N * 0.105) {
        col = WHITE;
      } else if (d <= N * 0.075) {
        col = GREEN;
      }

      buf[i] = col[0]; buf[i + 1] = col[1]; buf[i + 2] = col[2]; buf[i + 3] = 255;
    }
  }

  // 下采样
  const out = Buffer.alloc(size * size * 4);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      let r = 0, g = 0, b = 0, a = 0;
      for (let sy = 0; sy < SS; sy++) {
        for (let sx = 0; sx < SS; sx++) {
          const i = ((y * SS + sy) * N + (x * SS + sx)) * 4;
          const al = buf[i + 3] / 255;
          r += buf[i] * al; g += buf[i + 1] * al; b += buf[i + 2] * al; a += al;
        }
      }
      const n = SS * SS;
      const o = (y * size + x) * 4;
      const av = a / n;
      out[o + 3] = Math.round(av * 255);
      if (a > 0) {
        out[o] = Math.round(r / a);
        out[o + 1] = Math.round(g / a);
        out[o + 2] = Math.round(b / a);
      }
    }
  }
  return encodePNG(size, size, out);
}

const dest = process.argv[2] || '.';
const dirs = {
  'mipmap-mdpi': 48,
  'mipmap-hdpi': 72,
  'mipmap-xhdpi': 96,
  'mipmap-xxhdpi': 144,
  'mipmap-xxxhdpi': 192
};
for (const d of Object.keys(dirs)) {
  const p = path.join(dest, d);
  fs.mkdirSync(p, { recursive: true });
  fs.writeFileSync(path.join(p, 'ic_launcher.png'), draw(dirs[d]));
}
// 预览大图
fs.writeFileSync(path.join(dest, 'icon-preview.png'), draw(512));
console.log('icons generated');
