# API

> Android 0.1.8 uses persistent account sessions. For protected devices add
> `instance_id`, `account_session`, `account_generation` to registration;
> carry `account_session` on all module data requests and explicit `owner_wxid`
> on messages. Admin sends should carry the generation returned by module status.
> The examples below describe the legacy baseline; see
> [account-session-isolation.md](account-session-isolation.md) before upgrading.


`wechat-observatory` 有两类 API：

- 管理 API：Web 管理台使用，认证头是 `X-Bridge-Password`。
- 模块 API：手机 LSPosed 模块使用，认证凭据是 API Key。

示例中的服务地址使用：

```text
http://127.0.0.1:8088
```

## 管理认证

```bash
curl -H "X-Bridge-Password: your-admin-password" \
  http://127.0.0.1:8088/api/modules/status
```

也支持 `?password=...`，但不建议在生产环境使用 URL 参数传密码。

`GET /api/modules/status` 保留原有 `modules` 数组，并返回
`capabilities.text_idempotency` 布尔值，表示当前队列存储是否支持文本请求幂等。
需要安全自动重试的客户端必须先确认该值为 `true`；字段缺失或为 `false` 时应拒绝发送，
不能通过真实消息探测。旧后端可能忽略 `Idempotency-Key`，不能仅凭连接成功判断支持。

## API Key 管理

列出：

```bash
curl -H "X-Bridge-Password: your-admin-password" \
  http://127.0.0.1:8088/api/api-keys
```

创建：

```bash
curl -X POST \
  -H "X-Bridge-Password: your-admin-password" \
  -H "Content-Type: application/json" \
  -d '{"api_key":"","device":"phone-a","nickname":"Front Desk Phone"}' \
  http://127.0.0.1:8088/api/api-keys
```

`api_key` 留空时，服务端自动生成。

停用：

```bash
curl -X POST \
  -H "X-Bridge-Password: your-admin-password" \
  http://127.0.0.1:8088/api/api-keys/<api-key>/disable
```

启用：

```bash
curl -X POST \
  -H "X-Bridge-Password: your-admin-password" \
  http://127.0.0.1:8088/api/api-keys/<api-key>/enable
```

删除：

```bash
curl -X DELETE \
  -H "X-Bridge-Password: your-admin-password" \
  http://127.0.0.1:8088/api/api-keys/<api-key>
```

删除 API Key 后，对应模块身份会注销。手机端继续使用旧 API Key 时不会重新注册成功。

## 设备管理

修改设备显示名：

```bash
curl -X POST \
  -H "X-Bridge-Password: your-admin-password" \
  -H "Content-Type: application/json" \
  -d '{"name":"phone-a","nickname":"Front Desk Phone"}' \
  http://127.0.0.1:8088/api/devices
```

设备名和显示名由 Web 管理端管理，手机模块不能改名。

## 消息查询

```bash
curl -H "X-Bridge-Password: your-admin-password" \
  "http://127.0.0.1:8088/api/messages?device=phone-a&limit=50"
```

常用参数：

| 参数 | 说明 |
| --- | --- |
| `device` | 设备名 |
| `owner_wxid` | 当前登录微信账号，省略时默认当前设备绑定 |
| `wxid` | 对话对象或群聊 ID |
| `limit` | 返回数量 |

## 发送文本

```bash
curl -X POST \
  -H "X-Bridge-Password: your-admin-password" \
  -H "Content-Type: application/json" \
  -d '{"device":"phone-a","owner_wxid":"wxid_current_login","wx_ids":["wxid_friend"],"text":"hello"}' \
  http://127.0.0.1:8088/api/send/text
```

`owner_wxid` 必填。它用于防止浏览器停留在旧账号状态时，把消息发到切换后的微信账号里。

机器人回复建议加 `Idempotency-Key` 请求头。带此头时，必须提供正数 `account_generation`（来自 `/api/modules/status`），且 `wx_ids` 只能有一个收件人。键为 8–128 个可见 ASCII 字符；同一 `device`、`owner_wxid` 与键下，账号代次、收件人和文本必须完全相同。收件人去首尾空白；文本保留原样，首尾空白也参与比较。

成功返回 `{"ok":true,"outbox_id":123,"idempotent_replay":false}`；重复原请求返回同一 ID 和 `idempotent_replay:true`。内容不同返回 `409 idempotency_conflict`。首次响应丢失或返回 `503 send_unconfirmed` 时，只能用原键和原请求重试；即使手机离线、账号已换代或任务已失败/取消，也不会重新入队。去重记录会保留到发送历史清理之后，此时原任务详情可能已返回 404，但重试仍返回原 ID。新请求仍受当前账号、代次和在线检查约束。

不带此头的旧接口行为不变，也不提供请求去重。200 只表示入队；用 `GET /api/outbox/{id}?device=...&owner_wxid=...` 查询结果。文本 `failed` 可能包含“已提交微信但结果未确认”，不能据此自动换键重发；`sent` 是手机报告的本地发送成功，不是收件人已读回执。请求幂等也不等于手机原生发送的跨进程严格一次保证。

## 发送文件

文件通知使用 `POST /api/send/file`：上传一个文件并创建一个好友、群聊或文件传输助手发送任务，要求 `Idempotency-Key`、当前账号与代次，单文件上限 300 MiB。上传成功不代表发送成功；应查询任务结果。完整调用、重试与清理约定见 [文件发送 API](file-send.md)。 管理台还提供文件暂存列表、详情与安全清理；对应 `GET /api/files`、`GET /api/files/{id}`、`POST /api/files/{id}/cleanup`，清理不会重新发送消息。

## 实时事件

```bash
curl -N -H "X-Bridge-Password: your-admin-password" \
  http://127.0.0.1:8088/api/live/events
```

管理台用该接口实时刷新模块状态和消息。

## 模块注册

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{"api_key":"dev_key_001","device":"","wxid":"wxid_current_login","nickname":"Current WeChat"}' \
  http://127.0.0.1:8088/module/register
```

服务端以 API Key 为准，`device` 会被 Web 绑定覆盖。

## 模块上报消息

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{"api_key":"dev_key_001","from":"wxid_friend","to":"wxid_current_login","text":"hello","message_type":1,"direction":"recv","chat_id":"wxid_friend","chat_kind":"direct"}' \
  http://127.0.0.1:8088/webhook/lsposed/message
```

群聊建议带：

```json
{
  "room_id": "12345@chatroom",
  "sender": "wxid_member",
  "chat_id": "12345@chatroom",
  "chat_kind": "room"
}
```

## 模块同步联系人

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{"api_key":"dev_key_001","wxid":"wxid_current_login","complete":true,"contacts":[{"wxid":"filehelper","nickname":"文件传输助手","chatroom":false}]}' \
  http://127.0.0.1:8088/module/contacts/snapshot
```

## 模块出站队列

WebSocket：

```text
GET /module/outbox/ws?api_key=<api-key>&device=<device>&wxid=<current-wxid>
```

HTTP 轮询：

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{"api_key":"dev_key_001","device":"phone-a","wxid":"wxid_current_login","limit":1}' \
  http://127.0.0.1:8088/module/outbox/poll
```

ACK：

```bash
curl -X POST \
  -H "Content-Type: application/json" \
  -d '{"api_key":"dev_key_001","device":"phone-a","items":[{"id":1,"status":"sent"}]}' \
  http://127.0.0.1:8088/module/outbox/ack
```
