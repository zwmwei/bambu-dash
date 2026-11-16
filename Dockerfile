# Bambu P2S 看板 · 桥接服务
#
# 镜像里要有 ffmpeg：打印机的摄像头是 RTSPS(TLS 322)，
# 浏览器不能直接播，桥接服务靠 ffmpeg 转成 MJPEG。
#
#   docker build -t bambu-dash .
#   docker run -d --net=host -e PRINTER_IP=... bambu-dash
#

# 基础镜像地址可替换。国内有些环境的 docker.io 加速源是坏的（飞牛内置的
# docker.fnnas.com 会返回 401），这时用镜像站的完整地址绕过：
#   --build-arg BASE_IMAGE=docker.m.daocloud.io/library/node:20-slim
ARG BASE_IMAGE=node:20-slim
FROM ${BASE_IMAGE}

# 国内网络拉 apt 包慢的话，构建时传 --build-arg APT_MIRROR=mirrors.aliyun.com
# （docker-compose.yml 里已经默认带上了，国外环境删掉那行即可）
ARG APT_MIRROR=

ENV TZ=Asia/Shanghai \
    PORT=8848 \
    NODE_ENV=production

# ffmpeg 用到 Debian 自带的 TLS 后端，能连打印机的自签证书
# tzdata 让容器日志走北京时间
RUN if [ -n "$APT_MIRROR" ]; then \
      sed -i "s|deb.debian.org|$APT_MIRROR|g" /etc/apt/sources.list.d/debian.sources || true; \
    fi \
 && apt-get update \
 && apt-get install -y --no-install-recommends ffmpeg ca-certificates tzdata \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# 依赖先装：以后只改代码，这一层能直接命中缓存
COPY bridge/package.json bridge/package-lock.json ./bridge/
RUN cd bridge && npm ci --omit=dev && npm cache clean --force

COPY bridge ./bridge
COPY web ./web

EXPOSE 8848

# 用 node 自带的 fetch 探活，不额外装 curl
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8848)+'/api/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "bridge/server.js"]
