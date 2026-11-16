#!/usr/bin/env bash
# macOS / Linux 启动脚本
set -u
cd "$(dirname "$0")"

echo
echo "  拓竹打印机看板 · 桥接服务"
echo "  ----------------------------------------"
echo

# ---------- 1. 检查 Node.js ----------
if ! command -v node >/dev/null 2>&1; then
  echo "  [错误] 没有找到 Node.js"
  echo
  echo "  请先安装 Node.js 18 或更高版本：https://nodejs.org/zh-cn/download"
  echo "  macOS 也可以：brew install node"
  echo
  exit 1
fi

NODE_MAJOR=$(node -v | sed 's/^v//' | cut -d. -f1)
echo "  Node.js $(node -v)"
if [ "$NODE_MAJOR" -lt 18 ] 2>/dev/null; then
  echo "  [提示] 版本偏低，建议升到 18 或以上，否则可能连不上"
fi

# ---------- 2. 检查依赖 ----------
if [ ! -f bridge/node_modules/ws/package.json ]; then
  echo "  首次运行，正在安装依赖（只需一次，大约 10 秒）..."
  echo
  if ! (cd bridge && npm install --no-audit --no-fund); then
    echo
    echo "  [错误] 依赖安装失败，多半是网络问题。"
    echo "  可以换国内镜像后重试："
    echo "    npm config set registry https://registry.npmmirror.com"
    echo
    exit 1
  fi
  echo
fi

echo "  依赖就绪"
echo
echo "  浏览器会自动打开。手机连同一个 WiFi，访问画面上显示的"
echo "  192.168.x.x:8848 那个地址即可。"
echo
echo "  按 Ctrl+C 停止服务"
echo

# macOS 用 open，Linux 用 xdg-open
if command -v open >/dev/null 2>&1; then
  open "http://localhost:8848" >/dev/null 2>&1 &
elif command -v xdg-open >/dev/null 2>&1; then
  xdg-open "http://localhost:8848" >/dev/null 2>&1 &
fi

exec node bridge/server.js
