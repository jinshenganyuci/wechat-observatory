# WeChat Observatory

## 友链

- [LINUX DO 社区](https://linux.do/)
## 项目意义
其实就是因为逆向出来的ipad协议、车载协议等等，容易风控被下线 而且不能再次扫码登录 只能使用wx app 才做了这个项目。这样就算被风控也能继续使用微信、机器人 直接控制的wxapp 就不需要再去扫码登陆其它东西。最重要的是被冻结了依然能识别指令，只是不能回复消息。
## 截图

### Web 管理台

![API Key 与设备管理](docs/images/readme-web-admin-api-key-status.png)

![实时面板 1](docs/images/readme-web-admin-dashboard-1.png)

![实时面板 2](docs/images/readme-web-admin-dashboard-2.png)

### Android / LSPosed 模块

![微信网关模块设置](docs/images/readme-mobile-gateway-settings.jpg)

![模块列表](docs/images/readme-mobile-modules.jpg)

### 小米设备解锁

如果你手上是小米机型，先把解锁这一步走通，给你们指一条明路：[Linuxoid-cn/Mi8G3-Unlocker](https://github.com/Linuxoid-cn/Mi8G3-Unlocker)。

WeChat Observatory（微信看台）是一个通过 Android LSPosed 模块接入微信的网关控制台。手机模块负责在微信进程内观察消息、同步联系人、执行发送；服务端负责 API Key 身份、当前微信账号绑定、消息持久化、出站队列和 Web 管理台。

此 fork 的 `0.1.23-wechat8079` 包含微信 Android **8.0.79 / 3200 arm64** 适配，已对目标 APK 做 DexSQL 静态核验。保留 8.0.74/3120、8.0.78/3180 的历史账号映射，其他版本明确拒绝注册。

从 0.1.18 起，由微信进程在自己的私有目录中保存网关配置，设置入口位于微信设置 → Observatory 网关。无需打开模块、目录授权或关联启动；模块启动页仅提供使用说明。从 0.1.17 或更早版本升级需在微信重新填写一次地址与 API Key；卸载前可在微信点“停用并删除配置”。详见 [升级说明](docs/compatibility/wechat-8079-0118.md)。

0.1.21 以 0.1.18 的发送流程为基线，保留发送记录与历史安装折叠，以及身份读取和真实心跳的小范围修复；移除亮屏重连等额外恢复策略。详见 [精简版说明](docs/compatibility/wechat-8079-0121.md)。

0.1.22 保留上述发送流程，新增默认关闭的 **Oplus 息屏保护**，适配已核验的一加 PJZ110 Android 16 固件及电池应用版本。它处理已实测的后台冻结、关闭 socket 和夜间自动断网；未知固件继续执行原厂策略。45 分 15 秒模拟未接电息屏测试保持在线，35 分钟后的文件传输助手消息约 1 秒收到发送成功回执。此结果不代表无限息屏或所有设备的保证，详见 [适配范围、测试与耗电影响](docs/compatibility/oplus-realtime-candidate.md)。

**服务端与模块需要使用兼容版本。** 上游 main `31853e9` 不支持本模块要求的账户会话协议；只有更新 APK 仍无法使用。升级步骤、验证范围、日志与签名说明见 [8.0.79 兼容性说明](docs/compatibility/wechat-8079.md)。

0.1.23 新增 [文件通知 API](docs/file-send.md)：上传普通文件后，沿现有出站队列发送到微信群或文件传输助手，支持幂等请求、发送状态与私有暂存清理。单文件上限 25 MiB，文件发送适配限微信 8.0.79/3200；图片、视频发送另行适配。

```text
微信消息 -> LSPosed 模块 -> 网关入库和实时推送 -> Web 管理台
Web 手动发送 -> 网关出站队列/WebSocket -> LSPosed 模块 -> 微信
```

## 功能

- API Key 管理：生成、停用、启用、删除。删除 key 后对应模块会被注销。
- 模块注册：模块自动识别当前微信 `wxid`，不需要用户手动填写。
- 当前账号隔离：切换微信后，同一个 API Key 更新当前绑定，旧账号消息不会混到当前聊天列表。
- 通讯录同步：支持好友、群聊、文件传输助手。
- 消息观测：文本、图片、语音、视频、文件等消息类型均可记录；入站附件以元数据或占位文本展示。
- 通知发送：API 与 Web 管理台发送文本、普通文件；手机模块从队列取任务并调用微信发送。文件成功以原生附件完成为准，结果不明时不自动重发。
- 实时刷新：Web 管理台通过 SSE/WebSocket/轮询组合展示实时状态和消息。
- Docker 部署：提供单机 Docker Compose 示例和 k3s 参考配置。

## 项目结构

```text
cmd/bridge                 网关服务入口
cmd/bridge-db              数据库创建、迁移、种子和检查工具
internal/bridge            HTTP API、模块注册、事件、出站队列、Web 静态资源
internal/storage/mysql     MySQL 表结构和持久化实现
android-module             LSPosed 微信模块
web/admin                  React + shadcn 风格管理台源码
internal/bridge/admin_dist Web 管理台构建后的嵌入资源
deploy/docker              Docker Compose 部署示例
deploy/k3s                 k3s 部署参考
docs                       详细文档
```

## 快速开始

### 1. 准备环境

- Go 1.24.2+
- Node.js 20+ 和 npm
- MySQL 8+，生产建议使用 MySQL
- Android Studio / Android SDK，用于构建 LSPosed 模块
- 已 Root 的 Android 手机、LSPosed、微信
- 微信 Android `8.0.79 / 3200 arm64`（本次静态验证目标）

### 2. 启动服务端

本地内存配置示例：

```powershell
$env:BRIDGE_HTTP_ADDR=":8088"
$env:BRIDGE_ADMIN_PASSWORD="change-this-password"
$env:BRIDGE_DEVICE_ADMIN_PASSWORD="separate-device-admin-password"
$env:BRIDGE_DEFAULT_DEVICE="phone-a"
$env:BRIDGE_DEVICES="phone-a||wechat-phone|5s"
$env:BRIDGE_API_KEYS="dev_key_001|phone-a|Test Phone"
$env:BRIDGE_MYSQL_DSN=""
go run ./cmd/bridge
```

打开管理台：

```text
http://127.0.0.1:8088/admin/
```

首次输入管理密码后，浏览器会记住登录；再次打开或刷新页面会自动连接。工作台集中展示会话和发送记录，设备连接详情与 API 管理使用独立导航。断线后会自动重连，退出登录会清除本浏览器保存的密码。

只管理设备和 API Key、完全不读取消息的独立页面：

```text
http://127.0.0.1:8088/device
```

生产环境请务必设置强密码：

```text
BRIDGE_ADMIN_PASSWORD=your-strong-admin-password
BRIDGE_DEVICE_ADMIN_PASSWORD=your-separate-device-admin-password
```

### 3. 使用 MySQL

```powershell
$env:BRIDGE_MYSQL_DSN="wechat:change-me@tcp(127.0.0.1:3306)/app_wechat_observatory?charset=utf8mb4&parseTime=True&loc=Local"
$env:BRIDGE_MYSQL_AUTO_MIGRATE="false"
go run ./cmd/bridge-db -create-database -migrate -seed -check
go run ./cmd/bridge
```

长运行服务建议保持：

```text
BRIDGE_MYSQL_AUTO_MIGRATE=false
```

迁移通过 `cmd/bridge-db` 显式执行，避免重启服务时误覆盖真实手机注册出来的当前 `wxid`。

### 4. 构建 Web 管理台

```powershell
cd web/admin
npm install
npm run build

cd ../device
npm install
npm run build
```

构建产物会分别写入 `internal/bridge/admin_dist` 和
`internal/bridge/device_dist`，由 Go 服务直接嵌入。

### 5. 构建 Android 模块

仓库包含 Gradle 8.9 Wrapper；使用 JDK 17 和 Android SDK 35：

```powershell
cd android-module
.\gradlew.bat :app:assembleDebug
```

安装 APK 后，在 LSPosed 中启用模块并勾选微信作用域，然后重启手机或重启微信。

仅使用已适配固件且需要“Oplus 息屏保护”时，额外勾选机主侧的 **系统框架** 和 **电池（com.oplus.battery）** 作用域，重启手机，再在微信网关设置中启用该选项。它可能增加息屏耗电；模块更新涉及这些系统作用域时也需重启手机。

### 6. 手机模块配置

在 Web 管理台生成 API Key，进入 **微信设置 → Observatory 网关**，填写：

- 你自己的 Observatory 服务地址（无默认服务器）
- Web 管理台生成的 API Key

不要手动填写 `wxid`。模块会在微信进程内自动识别当前登录微信。

保存后立即应用配置。覆盖安装新 APK 后需强行停止并重开微信一次，以加载新模块代码。模块注册成功后，Web 管理台的模块状态应显示为“就绪”。

## Docker 部署

本仓库的 `deploy/docker/docker-compose.yml` 是包含外部 `pd-wechat-gateway` 项目的联动栈，不能在仅克隆本仓库时直接整栈启动。

独立部署可在仓库根目录构建 Observatory 镜像，然后在你已有的 Compose 中替换 Observatory 与迁移服务的镜像，保留原数据库卷、网络与环境变量：

```bash
docker build -t wechat-observatory:0.1.14-wechat8079 .
```

从旧 main 升级必须先备份并执行数据库迁移，见 [本版本升级说明](docs/compatibility/wechat-8079.md)。不要用旧服务端镜像搭配新 APK。

## 常用 API

管理 API 使用 `X-Bridge-Password`：

```bash
curl -H "X-Bridge-Password: your-admin-password" http://127.0.0.1:8088/api/modules/status
curl -H "X-Bridge-Password: your-admin-password" http://127.0.0.1:8088/api/api-keys
```

模块 API 使用 API Key：

- `POST /module/register`
- `POST /webhook/lsposed/message`
- `POST /module/contacts/snapshot`
- `GET /module/outbox/ws`
- `POST /module/outbox/poll`
- `POST /module/outbox/ack`

完整协议见 [docs/api.md](docs/api.md) 和 [docs/module-contract.md](docs/module-contract.md)。

## 安全和合规提醒

本项目需要 Root、LSPosed 和对微信进程的 Hook。使用前请确认你理解并接受以下风险：

- 可能违反微信或相关服务的使用条款。
- Hook、自动化发送和异常行为可能带来账号风控风险。
- 服务端管理台必须放在可信网络后，并设置强管理密码。
- API Key 等同于手机模块访问凭证，泄露后应立即停用或删除。

更多发布前检查见 [docs/security.md](docs/security.md) 和 [docs/open-source-checklist.md](docs/open-source-checklist.md)。

## 验证

```powershell
go test ./...
cd web/admin
npm run build
cd ../..
cd android-module
.\gradlew.bat :app:assembleDebug
```

如果当前检出没有 `gradlew.bat`，请用 Android Studio 打开 `android-module` 构建，或先为项目补充 Gradle Wrapper。

## 文档

- [快速开始](docs/quick-start.md)
- [部署指南](docs/deployment.md)
- [Android 模块使用](docs/android-module.md)
- [API 说明](docs/api.md)
- [架构说明](docs/architecture.md)
- [模块协议](docs/module-contract.md)
- [安全说明](docs/security.md)
- [开源发布清单](docs/open-source-checklist.md)

## 许可证

本项目使用 MIT License。你可以自由使用、复制、修改、分发、商用或私有使用，只需要保留许可证和免责声明。
