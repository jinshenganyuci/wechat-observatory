# 文件通知 API

`0.1.23-wechat8079` 将文件作为微信附件发送到群聊或文件传输助手。需要匹配的新版服务端、MySQL、可写的私有文件目录和微信 **8.0.79 / 3200 arm64**。旧模块继续收发文本，不会领取文件任务。

## 上传并创建发送任务

先通过 `GET /api/modules/status` 获取设备当前的 `device_wxid` 与 `account_generation`，通过 `GET /api/module-contacts` 获取群的 `wxid`。群标识以 `@chatroom` 结尾；测试时使用 `filehelper`。

```bash
curl --fail-with-body \
  -H "X-Bridge-Password: $OBSERVATORY_ADMIN_PASSWORD" \
  -H 'Idempotency-Key: daily-report-2026-10-18-group-a' \
  -F 'device=phone-a' \
  -F 'owner_wxid=wxid_current_login' \
  -F 'account_generation=17' \
  -F 'wxid=123456789@chatroom' \
  -F 'file=@./report.pdf' \
  'https://your-server.example/api/send/file'
```

成功返回：

```json
{"ok":true,"outbox_id":123,"idempotent_replay":false}
```

这个响应只表示任务已持久化，**不表示已经发送成功**。管理台的发送记录会展示文件名、大小与发送状态。每次请求只有一个文件、一个目标群；向多个群通知时，分别创建请求并使用不同幂等键。

按任务编号查询结果，无需扫描最近 100 条发送记录：

```bash
curl --fail-with-body \
  -H "X-Bridge-Password: $OBSERVATORY_ADMIN_PASSWORD" \
  'https://your-server.example/api/outbox/123?device=phone-a&owner_wxid=wxid_current_login'
```

返回 `{ "item": { "id": 123, "kind": "file", "status": "sent", "attachment": { "name": "report.pdf", "size": 1234, "sha256": "..." } }, "server_time": "..." }`。实际响应还包含其他队列字段。`GET /api/outbox` 则用于列出近期发送记录。

接口使用 `multipart/form-data`，不是 Base64，也不接受任意下载链接。文件非空、单个最大 **25 MiB**；这是本项目当前上限，并非微信官方上限。支持 PDF、Office 文档、TXT、ZIP 和普通二进制文件。第一版拒绝微信会额外启动缩略图流程的图片/视频扩展名：`jpg jpeg bmp png tif tiff heic heif webp avi flv m4v mkv mov mp4 mts webm wmv`。后缀检查不区分大小写。

## 幂等与发送状态

`Idempotency-Key` 必填，长度 8–128 个可见 ASCII 字符。同一设备、登录账号与键下，目标、账号代次、文件名、大小、SHA-256 均相同的请求返回原 `outbox_id`；内容不同则返回 `409 idempotency_conflict`。文件字节清理后仍保留轻量去重记录。

调用方在提交前保存键与原请求参数。上传超时、连接中断、服务重启或回复丢失后，使用**原键、原参数、原文件**重试。不要因为超时就生成新键；新键表示新的发送意图。当前账号代次变更不影响对原请求的幂等查询。

发送历史仍按既有保留策略清理。历史行清理后，重试仍返回原任务编号并且不会重发，但按编号查询可能返回 404；调用方应保存已经取得的终态结果，不应把 404 当成新建发送的理由。

| 状态 | 含义 |
| --- | --- |
| `pending` | 等待模块领取 |
| `leased` | 模块已领取，正在下载或校验 |
| `native_started` | 已授予一次微信提交权限，等待结果 |
| `sent` | 微信原生附件流程完成，消息与附件记录核验通过 |
| `failed` | 已确认失败；不会自动新建发送任务 |
| `cancelled` | 未提交微信的任务失效或被取消 |
| `unknown` | 是否完成尚不能确认；禁止自动重发 |

`sent` 不是群成员已阅读或下载的证明。微信本身也可能独立重试它已经建立的那条消息。

手机将任务记录持久化后才申请原生提交权限。服务端只授予一次，手机在提交前再次验证当前账号；重启后仅核验已记录的原生消息，不再次调用发送入口。遇到断电等无法判定的窗口，优先保留 `unknown`，不能声称网络系统能保证“恰好发送一次”。同一手机恢复连接后可继续核验已记录的结果。

如果服务端能够原子确认任务尚未提交微信，模块会安全取消没有取得许可的旧意图，避免它不断重新领取并阻塞队列。这个取消操作不能撤回微信已经接手的文件。

## 文件在哪里，何时清理

1. **后端**：流式接收、计算 SHA-256，保存到私有目录中的随机文件名，再建立队列记录。模块下载需要有效 API Key、当前账号会话及任务租约；没有公开文件 URL。
2. **手机**：下载到当前微信用户的 `no_backup/observatory-outgoing`，核对大小和 SHA-256，然后交给微信原生附件流程。无需外部存储授权，也无需 Observatory 应用留在后台。
3. **确认结束后**：清理本项目的手机暂存副本，后端清理任务的文件字节。去重和结果记录保留，防止清理后重复发送。
4. **未提交的过期任务**：最长 24 小时过期；既有账号切换和设备离线取消策略也可能提前取消任务，并不保证离线排队 24 小时。上传中断留下的后端孤立文件由定期清理回收。已交给微信但结果未知的文件不会按时间强删，受存储配额限制，空间不足会拒绝新任务。

微信为聊天创建的正式附件、聊天记录和接收方下载文件属于微信，项目不会删除它们。卸载 Observatory 也不能自动删除微信私有目录中的数据；在微信网关设置里停用并删除配置可清理安全删除的暂存，原生任务仍可能使用的未知文件保留。不要清空微信应用数据来清理网关文件。

## 部署与升级

先用新版 `wechat-observatory-db -migrate -check` 更新表结构，**已有部署不要再次 `-seed`**；然后启动新版后端、安装匹配 APK 并重启微信。Docker 部署需持久化文件目录，数据库与文件目录一起备份。多个服务实例连接同一数据库时必须共用同一支持文件锁的目录；不要让各实例各用一份临时磁盘。

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `BRIDGE_FILE_SPOOL_DIR` | `./data/files` | 私有暂存目录；Compose 使用持久卷中的 `/data/files` |
| `BRIDGE_FILE_SPOOL_MAX_BYTES` | `536870912`（512 MiB） | 后端文件总配额，包含尚未确认完成的任务 |

反向代理应允许至少 25 MiB 的文件加 multipart 开销，并给予上传、下载足够的超时时间。服务端不为用户文件开启静态目录或公开下载路由。

测试验收以文件传输助手为目标，见[验收记录](file-send-validation.md)。群目标使用同一原生发送入口，但群成员权限、微信风控与接收端下载仍需在实际目标群单独验收。
