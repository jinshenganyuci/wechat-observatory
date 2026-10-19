# Hermes 微信渠道

Observatory 是 Hermes 官方平台插件接口上的微信渠道。微信与飞书可在同一个 `default` gateway 中运行；模型、会话、授权、命令、cron 和回复恢复都交给 Hermes，插件只负责微信传输与本次 HTTP 请求的幂等记录。**不修改 Hermes 核心，不关闭全局投递账本，也不要求暂停 Hermes 升级。** 手机模块和后端发送任务的处理方式不变。

插件声明的兼容范围为 **`>=0.22.2`**。CI 使用未修改的官方 v0.22.2 与当前部署基线 `6e9b3e3` 源码验证；这些是回归基线，不是安装时必须锁定的提交。声明范围不表示所有未来版本都已测试。升级插件按官方插件机制处理；遇到上游接口变化，只维护插件，不给 Hermes 打补丁。旧补丁版的退出步骤见[迁移说明](../integrations/hermes/compat/README.md)。Hermes 内置 Weixin 渠道使用另一套服务，不能连接 Observatory。

## 功能范围

| 功能 | 用法与边界 |
| --- | --- |
| 私聊文字对话 | 已授权微信账号私聊机器人；每个发送人独立会话 |
| 管理员群聊问答 | 机器人已加入的群内，配置中的管理员真正 @ 机器人后触发；回复当前群 |
| 新建对话 | `/new`，使用 Hermes 原生会话重置 |
| 切换模型 | 管理员发送 `/model` 查看，`/model 模型名 --session` 切换当前会话 |
| 管理员绑定 | 在本机配置向导填写管理员真实 wxid；聊天账号不能自行抢占管理员 |
| 添加普通用户 | 对方私聊获得配对码，再在服务器用 Hermes 原生 pairing 批准 |
| 文本通知 | Hermes CLI、cron 通过 Observatory 发给好友、群或文件传输助手 |
| 普通文件通知 | 复用文件上传 API，单文件最多 300 MiB；不复制一套手机上传逻辑 |

当前不识别收到的图片、语音和文件。微信侧不提供本适配所需的消息编辑、按钮或输入状态能力，因此回复按普通文本发出，不模拟 Telegram 的流式编辑。发送成功也不代表收件人已读。

## 安装与管理员绑定

先将 Observatory 后端更新到包含文本 `Idempotency-Key` 支持的版本，确认管理台能正常收发。使用 MySQL 持久化部署；内存模式重启后会丢失消息和幂等记录，不适合作为此渠道的长期后端。

已有部署使用原数据库配置执行新版 `wechat-observatory-db -migrate -check`（源码部署为 `go run ./cmd/bridge-db -migrate -check`），再启动新版后端；不要重新 `-seed`。插件会先检查 `/api/modules/status` 的 `capabilities.text_idempotency`，旧后端缺少此能力时会拒绝发送。

新安装直接加入现有 `default`，共享它的模型配置、技能、记忆和定时任务。各渠道的会话仍按 Hermes 的规则隔离，不会自动合并聊天记录。已经使用旧 `wechat` profile 的部署，先读下方迁移步骤，**不能直接重新配置并启动**。

确认操作的是正在运行的 Hermes 安装与 `default` profile。新安装直接使用官方源码或安装包；已经安装旧投递补丁的部署先按[移除旧补丁](../integrations/hermes/compat/README.md)处理，不能只升级插件而把旧核心改动留着。

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
      group_admin_mentions: true
      group_allow_from: [wxid_admin]
      group_allow_admin_from: [wxid_admin]
      user_allowed_commands: [new, status, stop]
      unauthorized_dm_behavior: pair
```

在完成插件和配置后重启原来的 default 服务。不要另外启动第二个 default gateway；原来使用前台进程或外部 supervisor 的部署，沿用其启动方式。飞书只会在这次切换时短暂断线重连：

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

共享 default 保留 Hermes 默认开启的投递账本。旧版为了独立微信而设置的 `delivery_ledger: false` 不再是本插件要求；不要把旧 profile 的全局 gateway 配置复制进 default。原生恢复有自己的重试与不确定结果边界，见[连接、恢复与防重复](#连接恢复与防重复)。

## 管理员在群内 @ 机器人

插件 0.4.0 支持机器人已加入的所有群，不需要逐群登记。仅当 `group_admin_mentions: true`，**真实发送者 wxid 在 `allow_admin_from` 中，且手机上报的结构化 `mentioned_wxids` 精确包含机器人 wxid** 时，群文字才交给 Hermes。手打 `@昵称`、只 @ 其他人、`@所有人`、普通群成员以及仅获私聊配对的用户均不能触发。群内的未知用户不会收到配对提示。

旧配置未设置该开关时保持关闭；新安装向导会为绑定管理员开启。重跑向导不会自动给旧私聊渠道开启群聊，也不会覆盖已有更窄的群权限。为现有渠道启用时，只在 `platforms.observatory.extra` 添加上例的三个 `group_*` 字段，管理员列表使用已有真实 wxid。`group_allow_from` 和 `group_allow_admin_from` 分别是 Hermes 原生的群聊天及群命令授权；插件的管理员门槛不会绕过它们。不要修改 `gateway` 全局设置或飞书配置。

手机需要 Observatory **0.1.24 或更新 APK**，后端也需更新到本次包含 `mentioned_wxids` 的版本，并对原数据库执行新版迁移。旧 APK、旧后端、历史消息缺少该字段或字段为空时，插件不会从正文猜测 @ 关系。只有群名和昵称属于显示信息：插件通过账号隔离的通讯录接口查询，最多缓存 5 分钟；查询失败时显示原 ID，不影响已核实的触发。模型每条消息都获得当前群 ID、群名、真实发送者 wxid 和昵称，回复使用当前群 ID，不会变成私聊。

会话沿用 Hermes 的原生策略。当前绑定单一管理员时，同一群连续对话、不同群和私聊彼此隔离。插件不改全局 `group_sessions_per_user`；将来绑定多个管理员时，默认仍按“群＋发送者”隔离，不承诺自动共享为一个跨管理员会话。名称不参与身份判断，群改名不会改变会话。

群消息正文不改写；要使用原生命令，命令放在正文开头，例如 `/status @机器人`，并通过微信真正选择机器人作为 @ 对象。历史补读不重新执行以 `/` 开头的命令。第一次验证应由管理员在测试群主动 @，不要向未知群批量发送测试消息。

## 从旧 wechat profile 迁移到 default

以下是同一 Hermes 安装内的一次性迁移。整个过程中不删除旧 profile，不覆盖 default 的 `state.db`、聊天记录、cron、模型、技能或飞书配置。不要使用 `profile clone`、`profile import` 来代替渠道合并；它们不是此用途，`profile export` 也会剔除密码和部分运行状态，不能当作完整回滚备份。

### 1. 核实和暂停旧微信入口

先在隔离环境验证未修改的 Hermes 与新版插件；default 暂不启用 Observatory。用以下命令确定真实配置路径、运行方式和任务，不要猜测 `HERMES_HOME`，也不要在命令行打印密码：

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
            "SELECT intent,payload FROM outgoing WHERE status IN ('pending','needs_review') AND outbox_id IS NULL"
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

复制后核对源、目标所有状态表的记录与游标一致：`meta`、`ingress`、`outgoing`、`bundles`，以及新版的 `intent_aliases`（旧库可能还没有这张表，新版会创建）。`intent_aliases` 是旧补丁版留下的关联记录，新版保留读取，不再创建新关联；迁移时仍不能遗漏。新版首次打开旧库会把无任务 ID 的旧 `pending` 标为 `needs_review`，原请求与原幂等键都保留，不会在启动时自动重发。未确认文件必须保留同一路径和内容；如果迁移到另一台机器，先解决这些文件的可读性，不能只复制数据库后尝试发送。

### 3. 只合并本渠道配置并启动

在 default 中配置相同的后端地址、设备和机器人账号，只添加 `OBSERVATORY_ADMIN_PASSWORD`；不要用旧 `.env` 覆盖 default `.env`。使用 Hermes 原生配置写入工具或向导，保留原配置格式。把旧 Observatory 的完整 `allow_from`、`allow_admin_from`、`user_allowed_commands`、`unauthorized_dm_behavior` 和其他本渠道设置迁入，不能只保留向导询问的一个管理员而丢掉其他用户。

通过配对批准过的普通用户还存放在该 profile 的 `pairing/` 或 `platforms/pairing/`（取决于 Hermes 当前目录布局）。只复制 `observatory-approved.json` 到 default 对应目录，权限设为 `600`。目标已有本渠道配对记录时先停止核对，不覆盖；不复制整个 pairing 目录、共享限流文件或飞书授权。未完成配对码让用户在恢复后重新申请。已批准普通用户不能因此加入管理员名单。

default 的 `gateway.delivery_ledger` 保持开启。旧 profile 禁用 Observatory，保留 `standalone: true`、已暂停的 cron 和原目录，以免默认 multiplexer 再接管旧入口：

```sh
hermes --profile wechat config set platforms.observatory.enabled false
hermes --profile default gateway start
hermes --profile default gateway status --deep
```

若旧微信配置了系统服务自动启动，沿用该服务管理方式停用其自启；仅保留 profile 数据。不要删除旧 profile，也不要再次启动其 gateway。核对 default 的飞书及微信都已连接、只有一个微信消费者；旧发送记录仍在，未确认请求没有因启动插件而新建任务。先向文件传输助手做一次小消息测试，再由已授权账号发起新的私聊；不要拿群聊批量测试。

旧微信会话和记忆仍留在旧 profile，**不会自动合并到 default**。default 的配置、记忆、技能、现有 cron 直接由微信使用；不能为“合并聊天”覆盖 default `state.db`。

### 4. 回滚

尚未启动 default 的微信时，可以保留新库作为副本，恢复旧渠道配置并启动旧微信入口。default 已处理新事件或发送请求后，不能直接启动停在旧游标的旧 profile：先停止 default 的微信写入，核对并处理本渠道仍未结束的 Hermes 投递账本，再将 **default 最新的 Observatory 状态库** 用同样的一致备份方法反向迁回旧 profile（先把旧副本归档，让目标路径不存在），最后恢复旧微信入口。未确认请求与文件继续保留原键和原文件。

回滚只撤销新增的 Observatory 配置并恢复其状态，不用旧的 default 全量数据库或全量配置覆盖这段时间产生的飞书工作。旧聊天记录保留用于查阅，不代表可以跳过游标与未完成投递核对就安全恢复旧会话。新版插件可在未修改的 Hermes 上与其他渠道共用 default；不应为回滚重新关闭全局账本或恢复旧核心补丁。若必须退回旧插件，应先核对其行为与未完成记录，不能直接启动旧版的自动重放循环。

## 通知

通知沿用 Hermes 命令，不需要先让模型生成文字：

```sh
hermes --profile default send --to observatory:filehelper "备份已完成"
hermes --profile default send --to observatory:123456789@chatroom "今晚维护已结束"
hermes --profile default send --to observatory:123456789@chatroom \
  "报告文件 MEDIA:/absolute/path/report.pdf"
```

目标应使用管理台看到的好友 wxid 或群 ID。`hermes send --file report.txt` 读取的是文本正文；发送附件使用 `MEDIA:`。通知正文最多 8000 字符，超限整条拒绝，不拆成多个发送任务；图片和视频目前仍不支持。

插件 0.3.1 在提供公开 `StandaloneSendPolicy` 的 Hermes 上声明附件发送能力；已验证的 `6e9b3e3` 基线支持只有附件的 CLI 和 cron 通知，例如 `hermes --profile default send --to observatory:filehelper "MEDIA:/absolute/path/report.pdf"`。v0.22.2 没有这个公开接口，其通用通知路由会先拒绝空正文；该版本的文件通知仍须带一句简短说明。插件不修改旧版核心来绕过限制。

Hermes cron 的投递目标同样使用 `observatory:<目标>`，在 `default` 中创建或选择已有任务即可。文件原件由产生它的工具管理，插件不会删除用户原文件；后端和手机的传输暂存按[文件发送与清理约定](file-send.md)处理。

文件上传结果未确认时保留原文件路径和内容，先查看原任务。已经获得任务 ID 后，后端和手机继续处理该任务，插件不会因尚未显示 `sent` 再发一份。重新执行一次 CLI 命令或手动触发 cron 是**新的发送请求**；不能把它当成原上传的安全重试。

在同一个官方 cron 执行身份中重试，已经记录任务 ID 的文件不会再次上传。若上传响应丢失、尚不知道任务 ID，HTTP 重试仍可能重新传输文件字节，但沿用原幂等键，后端不会创建第二个发送任务；这不等于网络字节只传一次。

## 连接、恢复与防重复

责任边界如下：

| 层次 | 负责的恢复和去重 |
| --- | --- |
| 微信入站传输 | SSE 断线后按持久游标补读，按 `event_key` 去重；接收绑定账号私聊文字及通过管理员真实 @ 校验的群文字 |
| Hermes | 原生授权、配对、会话、命令、队列、最终文本账本及崩溃后恢复；飞书保持同一套原生行为 |
| 插件的一次发送 | 请求落盘后再发 HTTP，短暂重试沿用同一幂等键、原内容及原账号代次；不扫描旧记录自动补发 |
| Observatory 后端与手机 | 按原任务 ID 执行、记录实际发送状态及处理传输暂存文件 |

入站消息已领取给 Hermes 但尚未持久化处理时崩溃，可能没有回复；插件不会为补偿它而重复执行控制命令。历史补读中的 `/new` 等命令不重放，断线期间发出的命令应在恢复后重新发送。手机从未上报到后端的消息无法通过 SSE 恢复。

出站不按相同正文猜测重复。Hermes 提供相同 `reply_to`，或者当前调用确实带有官方 cron 执行上下文时，插件可找到原请求并沿用原键。没有这些公开身份的独立通知各自生成新键；两次合法的相同文字会各发一次。插件不读取私有队列、调用栈、Hermes 内部数据库，也不再依赖补丁的 `metadata.delivery_id`。

HTTP 短暂重试仍不能核实结果时，将原请求标为 `needs_review`，保留内容、幂等键及文件信息，返回失败。不启动第二个恢复循环，也不会因为同目标存在旧未确认记录而堵住所有新消息。后端返回任务 ID 只表示已接受，不表示微信已发送或对方已读；后续以管理台原任务状态为准。

**这里不承诺端到端严格一次。** Hermes 原生恢复可能没有原 `reply_to`、可能为恢复正文添加标记；此时插件无法证明它就是先前 HTTP 请求，不能把任意相同文字强行合并。若后端已接受，而 Hermes 尚未记下成功就崩溃，原生恢复仍可能产生重复消息。这与“本次 HTTP 重试始终复用原键”是不同层面的保证。遇到这种情况应核对原任务，不删除记录或换新键反复发送。发送中断后，是否继续或提示未确认，遵循所安装 Hermes 的原生恢复规则。

原生账本不是完整附件队列。尚未交给后端的附件、部分完成的 CLI/cron 通知及旧补丁记录不会由插件在启动后自行补发；原文件和记录用于核查。已交给后端的文件任务仍由原后端/手机流程处理，不受移除插件恢复循环影响。文件临时清理与人工清理保护沿用[文件发送约定](file-send.md)。

状态位于 profile 的 `observatory/`。不要在存在未确认发送时删除它。修改后端地址、设备或机器人账号会选择另一份状态库；向导在旧状态无法核实或有未确认请求时拒绝切换。仅 DDNS 解析 IP 改变而配置域名不变，不会改变状态绑定。

## 开发验证

插件源码在 [`integrations/hermes/observatory`](../integrations/hermes/observatory/)，隔离后端与模拟手机测试见[测试说明](../integrations/hermes/tests/README.md)。测试使用单独数据库、合成账号和小文件，不向真实微信联系人发送测试消息。

参考 Hermes 官方的[平台适配指南](https://github.com/NousResearch/hermes-agent/blob/v0.22.2/website/docs/developer-guide/adding-platform-adapters.md)与[插件接口](https://github.com/NousResearch/hermes-agent/blob/v0.22.2/website/docs/developer-guide/plugins/index.md)。
