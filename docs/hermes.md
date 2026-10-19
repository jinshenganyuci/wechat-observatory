# Hermes 微信渠道

Observatory 负责微信收发，Hermes 负责模型、会话和命令。本集成包含外部 platform plugin；微信与飞书共用 `default` 时，还需要应用本仓库提供的、**与实际 Hermes commit 对应的投递恢复补丁**。补丁让首次发送和恢复发送携带同一个投递身份，不关闭飞书的投递账本。不需要更换现有 0.1.23 手机模块。

插件声明的 Hermes 兼容范围为 **`>=0.22.2, <0.23`**；共享 `default` 分别支持官方 **v0.22.2（`d923a5e`）** 与 **`349e4f7`** 两个精确 commit，使用各自的补丁与回归测试。完整 SHA 和选择命令见[兼容说明](../integrations/hermes/compat/README.md)。不能把声明范围当作其他版本已通过恢复测试，也不必为套用旧补丁而降级。Hermes 的内置 Weixin 渠道使用另一套服务，不能用来连接 Observatory。

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

新安装直接加入现有 `default`，共享它的模型配置、技能、记忆和定时任务。各渠道的会话仍按 Hermes 的规则隔离，不会自动合并聊天记录。已经使用旧 `wechat` profile 的部署，先读下方迁移步骤，**不能直接重新配置并启动**。

先按[补丁安装与核验说明](../integrations/hermes/compat/README.md)确认实际运行的 Hermes 源码、完整 commit 与 PM 环境，再选择对应补丁。新版源码不能直接套用 v0.22.2 补丁；应用检查不通过时停止，不覆盖整段恢复函数。安装插件本身不会自动修改 Hermes 源码。未应用补丁而开启 Hermes 投递账本时，插件会拒绝运行；不要通过关闭 default 的账本绕过检查。

在确认过的运行环境中安装并配置：

```sh
hermes --profile default plugins install \
  jinshenganyuci/wechat-observatory/integrations/hermes/observatory --enable
hermes --profile default setup gateway
```

在渠道选择中选 **WeChat Observatory**，填写：

- 后端根地址，例如 `https://gateway.example.com`。公网连接使用 HTTPS。
- 设备 ID，例如 `phone-a`。
- 机器人当前登录账号的真实 `wxid`，对应管理台的 `owner_wxid`。
- 管理员真实 `wxid`：这是给机器人发私聊的另一个微信账号，不能填昵称。
- 后端管理密码：不是手机模块的 API Key；向导隐藏输入，保存为该 profile 的 `OBSERVATORY_ADMIN_PASSWORD`。

向导只写入 `platforms.observatory` 和该 profile 的管理密码，不修改 `gateway` 的全局开关、模型或其他平台。已有飞书继续使用原来的配置。配置前后核对 `hermes --profile default config get gateway.delivery_ledger`：共享模式保持 `true`（未设置时 Hermes 默认开启）。如果此前曾手动关闭，应先确认原因，不能由微信向导悄悄改回。

向导保存的关键配置为：

```yaml
gateway:
  delivery_ledger: true  # 保留 Hermes 原有默认值；向导不写这一项。
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

在完成补丁和配置后重启原来的 default 服务。不要另外启动第二个 default gateway；原来使用前台进程或外部 supervisor 的部署，沿用其启动方式。飞书只会在这次切换时短暂断线重连：

```sh
hermes --profile default gateway restart
hermes --profile default gateway status --deep
```

首次接入从当前消息位置开始，不把过去的微信历史交给模型。管理员发一条新的私聊即可验证。普通用户私聊获得配对码后，由服务器管理员批准：

```sh
hermes --profile default pairing approve observatory <配对码>
```

配对授予聊天权限，不授予管理员身份。普通用户默认可用 `/new`、`/status`、`/stop`；模型切换由管理员处理。Hermes 的工具审批与 toolset 设置仍然有效，`allow_admin_from` 只控制命令权限，不能替代工具审批。

`/new` 按 Hermes 现有审批设置可能先提示确认，此时回复 `/approve` 即可；这是 Hermes 的原生确认流程。

旧的微信专用 profile 仍可保留 `gateway.standalone: true`、`gateway.delivery_ledger: false`，用于兼容或回滚，由插件恢复已保存的原请求。**这不是共享 default 的推荐配置**：关闭 default 的账本会同时影响飞书等渠道的持久化投递恢复。升级 Hermes 后必须重新核验补丁及恢复测试，不能假定原补丁仍然适用。

## 从旧 wechat profile 迁移到 default

以下是同一 Hermes 安装内的一次性迁移。整个过程中不删除旧 profile，不覆盖 default 的 `state.db`、聊天记录、cron、模型、技能或飞书配置。不要使用 `profile clone`、`profile import` 来代替渠道合并；它们不是此用途，`profile export` 也会剔除密码和部分运行状态，不能当作完整回滚备份。

### 1. 核实和暂停旧微信入口

先安装并验证补丁及新版插件；default 暂不启用 Observatory。用以下命令确定真实配置路径、运行方式和任务，不要猜测 `HERMES_HOME`，也不要在命令行打印密码：

```sh
hermes --profile wechat config path
hermes --profile default config path
hermes --profile wechat gateway status --deep
hermes --profile default gateway status --deep
hermes --profile wechat cron list
```

旧 profile 若有定时任务，先逐项运行 `hermes --profile wechat cron pause <任务ID>`，等待活跃对话和任务结束。跨 profile 后 cron 的执行身份会变化，不能把尚未完成的执行搬到 default 后重跑。需要保留的未来任务在核对后单独建立一次；default 原有任务不用搬。

停止旧微信 gateway，确认没有它的独立 CLI 发送、`cron run` 或其他机器上的同账号消费者继续工作：

```sh
hermes --profile wechat gateway stop
hermes --profile wechat gateway status --deep
```

状态仍显示运行、服务自动拉起、进程身份无法确认时，先停止迁移。不要用 `--all` 误停其他服务，不要删除锁文件或用 `--replace` 抢占。随后短暂停 default，完成以下离线切换；这是飞书此次短暂重连的窗口。

```sh
hermes --profile default gateway stop
hermes --profile default gateway status --deep
```

### 2. 备份并保留原发送身份

私密备份双方的 `config.yaml`、`.env`、本渠道配对记录和 `observatory/` 状态。备份目录仅本人可读，不提交 Git。保留旧的 `base_url`、`device`、`owner_wxid` **原值**；即使另一地址指向同一后端，也不能在迁移时一起换地址。

状态库名是上述三个值的 SHA-256 前 24 位加 `.sqlite3`，不包含 profile 名称。整库包含接收游标、去重记录、发送意图、原幂等键及文件通知的完成进度。迁移必须包含它们，不能只搬配置。先到看台核对未确认任务；有任务 ID 的以原任务为准，没有 ID 的仍按原请求核对，不能重新点发送。

源库使用 WAL，不能只 `cp` 主 `.sqlite3`。下面脚本使用 SQLite 一致备份，保留 WAL 中已提交的内容；它不发送消息、不修改源库，也不会覆盖已有目标库。先把两个路径替换为实际的同名 scope 库；源路径是旧 profile，目标路径是 default。**进程停止核验由上一步完成，脚本不能代替这项核验。**

```sh
python3 - /实际/wechat/observatory/同一scope.sqlite3 \
  /实际/default/observatory/同一scope.sqlite3 <<'PY'
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile

source, target = (Path(p).expanduser().absolute() for p in sys.argv[1:])
if not source.is_file() or source.resolve() == target.resolve():
    raise SystemExit("源库不存在，或源/目标相同；停止迁移")
if source.name != target.name:
    raise SystemExit("源/目标 scope 库名不同；停止迁移")
if any(os.path.lexists(str(target) + suffix) for suffix in ("", "-wal", "-shm")):
    raise SystemExit("目标库或 sidecar 已存在；停止迁移，不覆盖或合并")
os.umask(0o077)
target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
handle, temporary = tempfile.mkstemp(prefix=".observatory-migrate-", dir=target.parent)
os.close(handle)
try:
    with closing(sqlite3.connect(source.as_uri() + "?mode=ro", uri=True)) as src:
        pending = src.execute(
            "SELECT intent,payload FROM outgoing WHERE status='pending' AND outbox_id IS NULL"
        ).fetchall()
        for intent, raw in pending:
            payload = json.loads(raw)
            if payload.get("kind") == "file":
                attachment = payload["attachment"]
                path = Path(attachment["path"])
                if not path.is_file():
                    raise SystemExit(f"未确认文件的原路径不可读：{intent}；停止迁移")
                digest = hashlib.sha256()
                size = 0
                with path.open("rb") as stream:
                    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                        size += len(chunk)
                        digest.update(chunk)
                if size != attachment["size"] or digest.hexdigest() != attachment["sha256"]:
                    raise SystemExit(f"未确认文件内容变化：{intent}；停止迁移")
        with closing(sqlite3.connect(temporary)) as dst:
            src.backup(dst)
            if dst.execute("PRAGMA integrity_check").fetchall() != [("ok",)]:
                raise SystemExit("目标数据库完整性检查失败；停止迁移")
            dst.execute("PRAGMA journal_mode=DELETE")
    # 同目录临时文件；link 原子发布且拒绝覆盖并发出现的目标。
    os.chmod(temporary, 0o600)
    os.link(temporary, target)
    print(f"已复制状态库，保留 {len(pending)} 条未确认请求；尚未启动或发送")
finally:
    for suffix in ("", "-wal", "-shm"):
        Path(temporary + suffix).unlink(missing_ok=True)
PY
```

复制后核对源、目标所有状态表的记录与游标一致：`meta`、`ingress`、`outgoing`、`bundles`，以及新版的 `intent_aliases`（旧库可能还没有这张表，新版会创建）。`intent_aliases` 保留原发送键与原生投递 ID 的关联，不能在回滚时遗漏。未确认文件必须保留同一路径和内容；如果迁移到另一台机器，先解决这些文件的可读性，不能只复制数据库后尝试发送。

### 3. 只合并本渠道配置并启动

在 default 中配置相同的后端地址、设备和机器人账号，只添加 `OBSERVATORY_ADMIN_PASSWORD`；不要用旧 `.env` 覆盖 default `.env`。使用 Hermes 原生配置写入工具或向导，保留原配置格式。把旧 Observatory 的完整 `allow_from`、`allow_admin_from`、`user_allowed_commands`、`unauthorized_dm_behavior` 和其他本渠道设置迁入，不能只保留向导询问的一个管理员而丢掉其他用户。

通过配对批准过的普通用户还存放在该 profile 的 `pairing/` 或 `platforms/pairing/`（取决于 Hermes 当前目录布局）。只复制 `observatory-approved.json` 到 default 对应目录，权限设为 `600`。目标已有本渠道配对记录时先停止核对，不覆盖；不复制整个 pairing 目录、共享限流文件或飞书授权。未完成配对码让用户在恢复后重新申请。已批准普通用户不能因此加入管理员名单。

default 的 `gateway.delivery_ledger` 保持开启。旧 profile 禁用 Observatory，保留 `standalone: true`、已暂停的 cron 和原目录，以免默认 multiplexer 再接管旧入口：

```sh
hermes --profile wechat config set platforms.observatory.enabled false
hermes --profile default gateway start
hermes --profile default gateway status --deep
```

若旧微信配置了系统服务自动启动，沿用该服务管理方式停用其自启；仅保留 profile 数据。不要删除旧 profile，也不要再次启动其 gateway。核对 default 的飞书及微信都已连接、只有一个微信消费者，发送恢复使用原任务而没有新建重复任务。先向文件传输助手做一次小消息测试，再由已授权账号发起新的私聊；不要拿群聊批量测试。

旧微信会话和记忆仍留在旧 profile，**不会自动合并到 default**。default 的配置、记忆、技能、现有 cron 直接由微信使用；不能为“合并聊天”覆盖 default `state.db`。

### 4. 回滚

尚未启动 default 的微信时，可以保留新库作为副本，恢复旧渠道配置并启动旧微信入口。default 已处理新事件或发送请求后，不能直接启动停在旧游标的旧 profile：先停止 default 的微信写入，核对并处理本渠道仍未结束的 Hermes 投递账本，再将 **default 最新的 Observatory 状态库** 用同样的一致备份方法反向迁回旧 profile（先把旧副本归档，让目标路径不存在），最后恢复旧微信入口。未确认请求与文件继续保留原键和原文件。

回滚只撤销新增的 Observatory 配置并恢复其状态，不用旧的 default 全量数据库或全量配置覆盖这段时间产生的飞书工作。旧聊天记录保留用于查阅，不代表可以跳过游标与未完成投递核对就安全恢复旧会话。卸除核心补丁前先禁用共享模式；未打补丁时，微信只能回到原来的独立 profile、账本关闭模式。

## 通知

通知沿用 Hermes 命令，不需要先让模型生成文字：

```sh
hermes --profile default send --to observatory:filehelper "备份已完成"
hermes --profile default send --to observatory:123456789@chatroom "今晚维护已结束"
hermes --profile default send --to observatory:123456789@chatroom \
  "报告文件 MEDIA:/absolute/path/report.pdf"
```

目标应使用管理台看到的好友 wxid 或群 ID。`hermes send --file report.txt` 读取的是文本正文；发送附件使用 `MEDIA:`。所有文件通知（包括 cron）都应带一句简短说明，v0.22.2 的通用通知路由会拒绝仅有附件的空正文。通知正文最多 8000 字符，超限整条拒绝，不拆成多个发送任务；图片和视频目前仍不支持。

Hermes cron 的投递目标同样使用 `observatory:<目标>`，在 `default` 中创建或选择已有任务即可。文件原件由产生它的工具管理，插件不会删除用户原文件；后端和手机的传输暂存按[文件发送与清理约定](file-send.md)处理。

文件上传结果未确认时，必须保留原文件的路径和内容，不要移动、覆盖或删除。恢复需要用原文件和原幂等键核对；源文件已丢失或改变时插件会停止恢复，不会把替代文件当成原任务重发。已经获得任务 ID 后，应在看台查看该 ID 的发送状态；不要因为尚未显示 `sent` 就重新发送一份。

## 连接、恢复与防重复

入站使用后端 SSE，断线按持久游标补读；消息事件按 `event_key` 去重，微信本地消息 ID 不用作游标。只接收固定设备、固定登录账号下收到的私聊文字，自发消息和群消息不进入模型。历史补读中的控制命令不会重新执行，掉线期间发送的命令应在恢复后重新发送。

出站先将请求和随机幂等键写入 profile 内的 SQLite，再调用已有发送 API。请求超时、服务端返回不确定结果或插件重启时，使用原键、原内容、原账号代次恢复，不生成替代任务。API 返回任务 ID 只表示后端已接受；实际发送结果仍以看台的任务状态为准。

共享模式保留 Hermes 的投递账本。对应版本的补丁把同一投递身份贯穿最终文本回复的首次发送、重试和恢复；插件据此关联原请求，避免后端已接受但 Hermes 尚未记成功时再次创建任务。**这些恢复保障以本地持久化成功为前提**，覆盖之后的网络失败、回执丢失和进程崩溃。其他渠道仍走 Hermes 原有恢复行为。旧的独立 profile、账本关闭模式保留兼容，由插件恢复已持久化的请求；未锚定原消息的内部空自动续跑不会触发微信重新生成回复。

`delivery_ledger_unavailable` 表示本地投递账本无法正常写入，插件不会把这条最终文本回复发到后端，应先检查日志、磁盘及数据库状态。补丁保留当前轮次标记，只提供下一次非正常启动时从已落盘回复恢复的机会；正常退出或之后的新轮次可能清除、替换该标记，因此**不承诺存储故障后的自动补发**。修复存储后先核对原发送记录，再决定是否重新请求回复，不能把这个错误当成普通网络超时反复新建消息。

`349e4f7` 的补丁同时保留新版会话队列的收尾通知：账本失败时跳过持久标记的清除，但仍通知当前发送流程已经结束，让后续消息继续处理。这个通知不表示消息发送成功，也不表示恢复记录已经落盘；不以卡住队列来替代故障处理。

Hermes 原生投递账本覆盖最终文本，不是一份完整的附件队列。文件已进入插件 `prepare`、写入原请求后，可按原幂等键恢复；生成了文件但尚未持久化发送意图就崩溃的附件，不保证自动补发。CLI/cron 文件通知继续使用原有 bundle 及完整文件清单；遇到部分发送未确认，应保留原路径、原内容、原清单重试同一次通知，不要重新创建 cron 执行或换一份附件绕过未完成状态。

这不是端到端严格一次保证：在消息已被领取给 Hermes、但尚未生成并保存回复的瞬间发生进程崩溃，该轮可能没有回复。插件优先避免自动重复执行 `/new` 或重复发消息；这种情况下由用户重新发起对话。手机模块未成功上报给后端的消息，也无法由 SSE 恢复。

状态数据位于该 profile 的 `observatory/` 目录。不要在存在未确认发送时删除此目录或换一个全新的 profile 重试。对未确认任务先查看管理台，确认状态后再决定下一步；不要通过删除去重状态绕过错误。

状态也绑定服务地址、设备 ID 和机器人 `owner_wxid`。修改 `base_url` 的协议、域名、端口或路径会选用另一份状态库；即使新旧地址指向同一个后端，也不会自动迁移未确认请求。切换绑定前先停止此 profile 的 gateway；配置向导会只读检查旧状态库，有未确认发送或无法核实状态时拒绝切换。仍有未确认任务时应保留原配置和状态，核实原任务后再切换，不能手改配置或用新地址重新发送来绕过检查。仅 DDNS 解析出的公网 IP 变化、配置中的域名地址保持不变，不会改变这项绑定。

## 开发验证

插件源码在 [`integrations/hermes/observatory`](../integrations/hermes/observatory/)，隔离后端与模拟手机测试见[测试说明](../integrations/hermes/tests/README.md)。测试使用单独数据库、合成账号和小文件，不向真实微信联系人发送测试消息。

参考 Hermes 官方的[平台适配指南](https://github.com/NousResearch/hermes-agent/blob/v0.22.2/website/docs/developer-guide/adding-platform-adapters.md)与[插件接口](https://github.com/NousResearch/hermes-agent/blob/v0.22.2/website/docs/developer-guide/plugins/index.md)。
