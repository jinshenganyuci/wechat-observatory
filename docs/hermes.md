# Hermes 微信渠道

Observatory 负责微信收发，Hermes 负责模型、会话和命令。本集成是一个外部 platform plugin，不修改 Hermes 核心，也不需要更换现有 0.1.23 手机模块。

插件声明的 Hermes 兼容范围为 **`>=0.22.2, <0.23`**，本次实际验证基线为 **v0.22.2**；其他版本仍需按本文重新核验。Hermes 的内置 Weixin 渠道使用另一套服务，不能用来连接 Observatory。

## 功能范围

| 功能 | 用法与边界 |
| --- | --- |
| 私聊文字对话 | 已授权微信账号私聊机器人；每个发送人独立会话 |
| 新建对话 | `/new`，使用 Hermes 原生会话重置 |
| 切换模型 | 管理员发送 `/model` 查看，`/model 模型名 --session` 切换当前会话 |
| 管理员绑定 | 在本机配置向导填写管理员真实 wxid；聊天账号不能自行抢占管理员 |
| 添加普通用户 | 对方私聊获得配对码，再在服务器用 Hermes 原生 pairing 批准 |
| 文本通知 | Hermes CLI、cron 通过 Observatory 发给好友、群或文件传输助手 |
| 普通文件通知 | 复用文件上传 API，单文件最多 300 MiB；不复制一套手机上传逻辑 |

当前不接入群聊问答，不识别收到的图片、语音和文件。微信侧不提供本适配所需的消息编辑、按钮或输入状态能力，因此回复按普通文本发出，不模拟 Telegram 的流式编辑。发送成功也不代表收件人已读。

## 安装与管理员绑定

先将 Observatory 后端更新到包含文本 `Idempotency-Key` 支持的版本，确认管理台能正常收发。使用 MySQL 持久化部署；内存模式重启后会丢失消息和幂等记录，不适合作为此渠道的长期后端。

已有部署使用原数据库配置执行新版 `wechat-observatory-db -migrate -check`（源码部署为 `go run ./cmd/bridge-db -migrate -check`），再启动新版后端；不要重新 `-seed`。插件会先检查 `/api/modules/status` 的 `capabilities.text_idempotency`，旧后端缺少此能力时会拒绝发送。

给微信使用独立的 Hermes profile。原生 `--clone` 会复制模型等配置，默认不复制 Telegram、飞书等消息渠道的凭据与授权名单；不要加 `--clone-channels`：

```sh
hermes profile create wechat --clone
hermes --profile wechat plugins install \
  jinshenganyuci/wechat-observatory/integrations/hermes/observatory --enable
hermes --profile wechat setup gateway
```

在渠道选择中选 **WeChat Observatory**，填写：

- 后端根地址，例如 `https://gateway.example.com`。公网连接使用 HTTPS。
- 设备 ID，例如 `phone-a`。
- 机器人当前登录账号的真实 `wxid`，对应管理台的 `owner_wxid`。
- 管理员真实 `wxid`：这是给机器人发私聊的另一个微信账号，不能填昵称。
- 后端管理密码：不是手机模块的 API Key；向导隐藏输入，保存为该 profile 的 `OBSERVATORY_ADMIN_PASSWORD`。

向导只修改微信 profile。模型、其他配置沿用 Hermes 原有设置；如果检测到该 profile 还启用了其他渠道，会要求先使用专用 profile。

向导保存的关键配置为：

```yaml
gateway:
  standalone: true
  delivery_ledger: false
platforms:
  observatory:
    enabled: true
    typing_indicator: false
    gateway_restart_notification: false
    extra:
      base_url: https://gateway.example.com
      device: phone-a
      owner_wxid: wxid_robot
      allow_from: [wxid_admin]
      allow_admin_from: [wxid_admin]
      user_allowed_commands: [new, status, stop]
      unauthorized_dm_behavior: pair
```

启动这个 profile：

```sh
hermes --profile wechat gateway run
```

首次接入从当前消息位置开始，不把过去的微信历史交给模型。管理员发一条新的私聊即可验证。普通用户私聊获得配对码后，由服务器管理员批准：

```sh
hermes --profile wechat pairing approve observatory <配对码>
```

配对授予聊天权限，不授予管理员身份。普通用户默认可用 `/new`、`/status`、`/stop`；模型切换由管理员处理。Hermes 的工具审批与 toolset 设置仍然有效，`allow_admin_from` 只控制命令权限，不能替代工具审批。

`/new` 按 Hermes 现有审批设置可能先提示确认，此时回复 `/approve` 即可；这是 Hermes 的原生确认流程。

`standalone` 是 v0.22.2 的官方兼容选项，避免默认 gateway 同时接管该 profile。升级 Hermes 后应重新核验此行为和发送恢复测试；不要直接删除这两个 gateway 设置。

## 通知

通知沿用 Hermes 命令，不需要先让模型生成文字：

```sh
hermes --profile wechat send --to observatory:filehelper "备份已完成"
hermes --profile wechat send --to observatory:123456789@chatroom "今晚维护已结束"
hermes --profile wechat send --to observatory:123456789@chatroom \
  "报告文件 MEDIA:/absolute/path/report.pdf"
```

目标应使用管理台看到的好友 wxid 或群 ID。`hermes send --file report.txt` 读取的是文本正文；发送附件使用 `MEDIA:`，并带一句简短说明（v0.22.2 的通用 CLI 路由会拒绝仅有附件标记的空正文）。图片和视频目前仍不支持。

Hermes cron 的投递目标同样使用 `observatory:<目标>`，在微信 profile 中创建任务即可。文件原件由产生它的工具管理，插件不会删除用户原文件；后端和手机的传输暂存按[文件发送与清理约定](file-send.md)处理。

文件上传结果未确认时，必须保留原文件的路径和内容，不要移动、覆盖或删除。恢复需要用原文件和原幂等键核对；源文件已丢失或改变时插件会停止恢复，不会把替代文件当成原任务重发。已经获得任务 ID 后，应在看台查看该 ID 的发送状态；不要因为尚未显示 `sent` 就重新发送一份。

## 连接、恢复与防重复

入站使用后端 SSE，断线按持久游标补读；消息事件按 `event_key` 去重，微信本地消息 ID 不用作游标。只接收固定设备、固定登录账号下收到的私聊文字，自发消息和群消息不进入模型。历史补读中的控制命令不会重新执行，掉线期间发送的命令应在恢复后重新发送。

出站先将请求和随机幂等键写入 profile 内的 SQLite，再调用已有发送 API。请求超时、服务端返回不确定结果或插件重启时，使用原键、原内容、原账号代次恢复，不生成替代任务。API 返回任务 ID 只表示后端已接受；实际发送结果仍以看台的任务状态为准。

Hermes v0.22.2 的默认投递账本在恢复时不向渠道传递原请求身份，可能重新生成消息，所以本 profile 关闭它，由插件保存原发送请求。其他 profile 的账本不受影响。启动时的空内部自动续跑事件也不会触发微信重新生成回复；用户下一条新消息仍可正常继续对话。

这不是端到端严格一次保证：在消息已被领取给 Hermes、但尚未生成并保存回复的瞬间发生进程崩溃，该轮可能没有回复。插件优先避免自动重复执行 `/new` 或重复发消息；这种情况下由用户重新发起对话。手机模块未成功上报给后端的消息，也无法由 SSE 恢复。

状态数据位于该 profile 的 `observatory/` 目录。不要在存在未确认发送时删除此目录或换一个全新的 profile 重试。对未确认任务先查看管理台，确认状态后再决定下一步；不要通过删除去重状态绕过错误。

状态也绑定服务地址、设备 ID 和机器人 `owner_wxid`。修改 `base_url` 的协议、域名、端口或路径会选用另一份状态库；即使新旧地址指向同一个后端，也不会自动迁移未确认请求。切换绑定前先停止此 profile 的 gateway；配置向导会只读检查旧状态库，有未确认发送或无法核实状态时拒绝切换。仍有未确认任务时应保留原配置和状态，核实原任务后再切换，不能手改配置或用新地址重新发送来绕过检查。仅 DDNS 解析出的公网 IP 变化、配置中的域名地址保持不变，不会改变这项绑定。

## 开发验证

插件源码在 [`integrations/hermes/observatory`](../integrations/hermes/observatory/)，隔离后端与模拟手机测试见[测试说明](../integrations/hermes/tests/README.md)。测试使用单独数据库、合成账号和小文件，不向真实微信联系人发送测试消息。

参考 Hermes 官方的[平台适配指南](https://github.com/NousResearch/hermes-agent/blob/v0.22.2/website/docs/developer-guide/adding-platform-adapters.md)与[插件接口](https://github.com/NousResearch/hermes-agent/blob/v0.22.2/website/docs/developer-guide/plugins/index.md)。
