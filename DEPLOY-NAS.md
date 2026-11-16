# 部署到飞牛 NAS（fnOS）

飞牛 fnOS 基于 Debian 12，自带 Docker，所以这套看板可以整包搬过去。
搬过去的好处很实在：**NAS 7×24 开机、功耗低、不影响电脑睡眠**，看板从此常驻。

---

## 一、动手前的三件事

| 检查项 | 怎么做 |
|---|---|
| NAS 装了 Docker | 应用中心 → 搜「Docker」→ 安装（装完桌面会有 Docker 图标） |
| 开了 SSH | 设置 → 系统设置 → SSH → 打开（命令行部署要用；只用图形界面可跳过） |
| NAS 和打印机同网段 | 打印机是 `192.168.1.x`，NAS 也应该是同一段 |

> 打印机 IP 建议在路由器上做**静态绑定**，否则重启后 IP 变了看板就连不上。

---

## 二、把文件放到 NAS 上

在 NAS 上建个目录，例如共享文件夹 `docker` 下的 `bambu-dash`：

```
/vol1/1000/docker/bambu-dash/
├── Dockerfile
├── docker-compose.yml
├── .env                 ← 下一步自己建的，存凭据，不入库
├── bridge/
└── web/
```

**怎么传**：两种都行。

- **直接克隆**（NAS 能连 GitHub，最省事）：
  ```bash
  cd /vol1/1000/docker
  git clone https://github.com/zwmwei/bambu-dash.git
  ```
- **从 Windows 拷**：资源管理器地址栏输 `\\NAS的IP`（或 `\\NAS主机名`），
  找到 `docker` 共享，把整个 `bambu-dash` 文件夹拖进去。

只需 `Dockerfile`、`docker-compose.yml`、`.env.example`、`bridge/`、`web/` 这几样，
`apk/`、`docs/`、`node_modules/` 都不用传（`.dockerignore` 也会挡掉）。

---

## 三、填上你的打印机信息

复制一份环境变量文件，把凭据写进去：

```bash
cd /vol1/1000/docker/bambu-dash
cp .env.example .env
vi .env        # 或者用 nano
```

`.env` 里就三行：

```bash
PRINTER_IP=192.168.1.100     # 打印机在局域网里的 IP
PRINTER_CODE=xxxxxxxx        # 8 位访问码
PRINTER_SERIAL=              # 可选，填了启动更快，不填也能自动识别
```

- `PRINTER_IP`：打印机屏幕 → 设置 → 网络
- `PRINTER_CODE`：同一页往下，「局域网模式(LAN Only)」那一块
- `PRINTER_SERIAL`：序列号，可选

`.env` 已经被 `.gitignore` 排除，不会进仓库；访问码只留在 NAS 上，
**不会下发到浏览器**。

配好之后，**浏览器打开看板就是已经连上的状态**，不用再输 IP 和密码。

---

## 四、启动

### 方式 A：SSH 命令行（推荐，最直观）

```bash
cd /vol1/1000/docker/bambu-dash
docker compose up -d --build
```

第一次要构建镜像（装 ffmpeg + node 依赖），视网络情况 3～10 分钟。看到这些就好了：

```
  本机访问   http://localhost:8848
  手机访问   http://192.168.1.x:8848
  模式       真实打印机 MQTT/TLS 8883
  预置凭据   192.168.1.100（访问码已配置，打开页面自动连接）
  摄像头     ffmpeg 就绪
```

看日志：`docker compose logs -f`

### 方式 B：飞牛 Docker 图形界面

1. 桌面打开「Docker」→ 左侧「项目」→「新建」
2. **项目路径**选到 `/vol1/1000/docker/bambu-dash`（这步很关键，`build: .` 要在这个目录里找源码）
3. 把 `docker-compose.yml` 的内容粘进去
4. 点「构建 / 部署」

图形界面里如果找不到「项目」入口，说明 Docker 应用版本偏旧，用方式 A 就行。

---

## 五、验证

浏览器打开 `http://NAS的IP:8848`，或者直接跑这两条：

```bash
# 服务是否正常：cam 应该是 true，online 应该是 true
curl http://NAS的IP:8848/api/health

# 摄像头能否出图（存成一张 jpg）
curl -o test.jpg http://NAS的IP:8848/api/cam.jpg
```

正常返回类似：

```json
{"ok":true,"mock":false,"cam":true,
 "session":{"serial":"0123AB4C5678901","online":true,"ip":"192.168.1.100"}}
```

---

## 六、几个需要知道的点

### 为什么用 host 网络

`network_mode: host` 让容器直接复用 NAS 的网络，有两个原因：

1. 「自动发现打印机」走 SSDP 的 UDP 广播（239.255.255.250:2021），
   Docker 默认的 bridge 网络收不到局域网广播应答，发现按钮会一直转圈；
2. 摄像头和打印机的连接在 host 网络下最省事。

host 模式下**不需要写端口映射**，容器直接占用宿主机 8848。
如果 8848 被别的服务占了，改 `PORT` 环境变量（同时记得改访问地址）。

### 换成 bridge 网络行不行

行，但要改成：

```yaml
    # 删掉 network_mode: host
    ports:
      - "8848:8848"
```

代价是「自动发现」按钮失效，必须手填打印机 IP —— 状态、控制、摄像头都照常工作。

### 家里网段不同 / NAS 有多网卡

如果 NAS 有多个网卡或在 VLAN 里，容器可能从错误的网卡出去。
排查第一步永远是 `docker compose logs`，看那行「连接 192.168.1.100:8883 …」后面有没有报错。

---

## 七、常见问题

| 现象 | 原因与处理 |
|---|---|
| 构建报 `401 Unauthorized` on `docker.fnnas.com` | 飞牛内置的镜像加速源不可用，见下面「拉不到基础镜像」 |
| `cam` 是 false | 镜像里 ffmpeg 没装上，重新 `docker compose build --no-cache` |
| `online` 一直是 false | IP 错了 / 打印机没开机 / NAS 不同网段 / 访问码填错 |
| 页面一直显示「演示模式」 | `/api/config` 没拿到预置配置：检查 compose 里 `PRINTER_IP` 是否为空 |
| 摄像头 503「未连接打印机」 | 容器还没连上打印机，等几秒；或 compose 里的 IP 不对 |
| 摄像头 504「摄像头无画面」 | 先看返回的正文，里面带了 ffmpeg 的原话，见下面「ffmpeg 版本差异」 |
| 端口占用起不来 | 改 `PORT`（如 8849），访问地址跟着变 |
| 构建时 apt 卡住 | 检查 `APT_MIRROR` 那行；海外环境删掉它走官方源 |
| `permission denied ... docker.sock` | 当前用户不在 docker 组，命令前加 `sudo`，或执行 `sudo usermod -aG docker $USER` 后重新登录 |

### 拉不到基础镜像（飞牛特有）

飞牛的 `/etc/docker/daemon.json` 里默认配了 `https://docker.fnnas.com` 作为加速源，
这个源在某些情况下会对 manifest 请求返回 **401 Unauthorized**，构建第一步就失败：

```
failed to resolve source metadata for docker.io/library/node:20-slim:
unexpected status from HEAD request to https://docker.fnnas.com/...: 401 Unauthorized
```

**不用去改 daemon.json**（那会影响 NAS 上所有容器）。项目里已经用另一种方式绕开：
`docker-compose.yml` 把基础镜像指向了镜像站的完整地址，不走 docker.io：

```yaml
      args:
        BASE_IMAGE: docker.m.daocloud.io/library/node:20-slim
```

实测这条路径拉取只要 20 秒左右。想换别的镜像站，改这一行就行。

### ffmpeg 版本差异（摄像头 504）

打印机是自签证书，必须不校验 TLS。但两个 ffmpeg 版本默认值相反：

| 版本 | `-tls_verify 0` | 默认校验证书 |
|---|---|---|
| 5.x（Debian 12 自带 5.1） | **不认这个参数**，报 `Option tls_verify not found` 直接退出 | 否 |
| 6+（Windows 上的 9.x） | 认 | 是 |

所以 `bridge/cam.js` 按 `ffmpeg -version` 的主版本号自适应：
5.x 不加参数，6+ 加 `-tls_verify 0`；万一判断错了，抓帧失败时还会读一遍
ffmpeg 的报错原话，遇到 `tls_verify` 相关错误就自动去掉参数重试一次。

**如果你看到 504，先看接口返回的正文**——现在它会把 ffmpeg 的最后几行日志带出来，
比单纯一句「无画面」好查得多。

---

## 八、日常维护

```bash
# 看状态
docker ps | grep bambu-dash

# 看日志（最后 100 行）
docker compose logs --tail=100

# 重启
docker compose restart

# 改了代码后重新构建
docker compose up -d --build

# 彻底删掉（含容器）
docker compose down
```

`restart: unless-stopped` 已经配好，NAS 重启后容器会自动起来，不用手动干预。

---

## 九、和手机上 APK 的关系

两者是**各自独立**的：

- **网页看板（这个）**：跑在 NAS 上，任何设备浏览器打开就能看，多台设备同时看互不影响
- **APK**：装手机上，自己直连打印机，**不依赖 NAS**

所以 NAS 挂了或者你在外面，手机 APK 照样能用；反过来也一样。
