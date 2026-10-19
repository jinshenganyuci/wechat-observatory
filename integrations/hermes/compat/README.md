# Hermes v0.22.2 投递恢复兼容补丁

微信与飞书共用 `default`，需要同时保留 Hermes 的通用投递账本和 Observatory 的原请求幂等键。仅安装外部插件不能补齐 Hermes v0.22.2 在恢复路径中遗漏的投递身份，因此这里提供一个明确应用、可以审查的源码补丁：[`hermes-v0.22.2-delivery.patch`](hermes-v0.22.2-delivery.patch)。插件不会在运行时偷偷修改 Hermes。

## 补丁的范围

补丁基线为官方 **v0.22.2**，Git commit **`d923a5eb487a17c0a68801bba9ef0f56c81040a8`**。它修改 Hermes 的 `gateway/platforms/base.py`、`gateway/run_startup.py`，将原有投递登记函数移入 `gateway/platforms/base_delivery.py`，并增加 `tests/gateway/test_idempotent_delivery.py`。这次抽取用于遵守 Hermes 对大型核心文件的限制，没有复制第二套投递逻辑。

- 增加默认关闭的 `BasePlatformAdapter.idempotent_delivery` 能力。Observatory 显式启用；飞书及其他未选择此能力的渠道保留原来的发送、恢复和提示文字。
- 对启用此能力的渠道，首次发送和恢复均通过 `metadata["delivery_id"]` 传递同一个原生投递 ID；恢复使用原文，避免添加恢复标记后变成另一个请求。
- Hermes 账本写入失败时，拒绝发送这条最终文本，返回 `delivery_ledger_unavailable` 并记录故障；不会先发出无法关联原身份的请求。

Observatory 继续使用自己的 SQLite 保存原请求及后端幂等键，Hermes 负责原有的投递义务和恢复。无需关闭 `gateway.delivery_ledger`，也无需复制一套飞书发送逻辑。补丁本身不迁移 profile、改配置、安装服务或发送测试消息。

恢复保障以正常持久化成功为前提：落盘后的网络失败、回执丢失、进程崩溃会使用原投递 ID。`delivery_ledger_unavailable` 是需要处理的本地存储故障，不承诺自动补发；保留轮次标记只为非正常启动提供恢复机会，干净退出或后续新轮次可能清除、替换它。补丁没有再增加一份备用队列。

原生账本覆盖最终文本回复；附件依靠 Observatory 已持久化的发送意图恢复。尚未进入 `prepare` 的附件不保证补发，CLI/cron 的文件通知仍沿用原 bundle、文件路径和清单，不能因部分失败而新建一次执行。详细边界见[恢复与防重复](../../../docs/hermes.md#连接恢复与防重复)。

## 确认正在使用哪一份 Hermes

必须对 **实际 gateway 使用的 Hermes 源码及 PM 环境** 操作。`~/.hermes` 通常是数据目录，不等于源码；随便另克隆一个仓库打补丁不会改变正在运行的 gateway。

先查看 `command -v hermes`、该启动器或符号链接指向，以及 `hermes --profile default gateway status --deep` 显示的服务定义/启动命令。外部 supervisor、容器、安装包可能使用不同安装目录，应以实际服务的源码路径为准。不要把 `hermes config path` 的结果当成源码路径。

在确认的源码目录，按该安装原有方式激活其 PM 管理的环境。源码安装可以在 Bash 中使用 `. ./activate`；不要猜测旧 `.venv/bin/python`，不要给系统 Python 单独安装一套依赖。核对：

```sh
hermes --version
python -c 'import sys, gateway.platforms.base as b; print(sys.executable); print(b.__file__)'
git rev-parse HEAD
```

`b.__file__` 必须位于实际运行源码中，版本和 commit 必须匹配上述基线。若是无可写源码的安装包、不同版本、另一个 fork 或已修改的核心文件，先停止；不能强行套用这份补丁。需要针对那份安装单独适配验证，或者由使用者明确决定切换到已验证的源码部署。

## 应用与核验

以下两个变量均应填写**核实过的绝对路径**。Observatory 的补丁可以来自本仓库 checkout；不要下载一个版本不明的同名文件。

```sh
HERMES_SOURCE=/实际使用的/hermes-agent
OBSERVATORY_PATCH=/实际/wechat-observatory/integrations/hermes/compat/hermes-v0.22.2-delivery.patch

test "$(git -C "$HERMES_SOURCE" rev-parse HEAD)" = \
  d923a5eb487a17c0a68801bba9ef0f56c81040a8 || {
  echo 'Hermes 版本不匹配，停止；不要继续 apply'
  exit 1
}
git -C "$HERMES_SOURCE" status --short
git -C "$HERMES_SOURCE" apply --check "$OBSERVATORY_PATCH"
```

最后一项成功，且确认目标核心文件没有其他未处理的改动后，才执行：

```sh
git -C "$HERMES_SOURCE" apply "$OBSERVATORY_PATCH"
git -C "$HERMES_SOURCE" diff --check
git -C "$HERMES_SOURCE" diff --stat
```

如果正向 `--check` 失败，先判断是否已经完整应用：

```sh
git -C "$HERMES_SOURCE" apply --reverse --check "$OBSERVATORY_PATCH"
```

反向检查成功表示补丁已经存在，**不要再执行正向 apply，也不要执行反向 apply 来“修复”**。正反检查都失败表示版本、部分应用状态或本地改动冲突，停止核对；不要 `git reset --hard`、`git checkout --` 丢掉用户改动。记录应用前 commit、补丁版本和 `git diff`，便于升级时重新验证。

测试在该源码的 PM 测试环境执行；预先在隔离副本验证可缩短最终服务切换窗口：

```sh
cd "$HERMES_SOURCE"
scripts/run_tests.sh tests/gateway/test_idempotent_delivery.py \
  tests/gateway/test_delivery_ledger.py \
  tests/gateway/test_delivery_ledger_producer.py \
  tests/gateway/test_active_turn_recovery.py
```

这是合成渠道和临时数据的回归测试，不向真实飞书或微信联系人发送消息。补丁接口可通过新 Python 进程确认：

```sh
python -c 'from gateway.platforms.base import BasePlatformAdapter; assert BasePlatformAdapter.idempotent_delivery is False; print("delivery identity capability present")'
```

然后按[安装或迁移步骤](../../../docs/hermes.md)更新插件与本渠道配置。共享 `default` 保持投递账本开启，向导不写全局 gateway 开关。最后按原服务方式只重启一次 default gateway，检查飞书和微信都正常连接；已有 Python 进程不会因为磁盘源码改变而自动加载补丁。已有旧微信 profile 时，必须先迁移原游标和发送状态，不能让两个 profile 同时消费同一账号。

## 升级与回滚边界

Hermes 升级可能覆盖补丁或引入相同能力的新实现；不能依赖“上次装过”。升级前保留状态与配置，升级后重新核验实际源码、能力接口和恢复回归测试。不要把 v0.22.2 补丁用 `--reject` 或手工忽略冲突的方式应用到其他版本。

Observatory 会检查恢复能力；账本开启但接口缺失时拒绝运行。不要为了消除这个错误而关闭 default 的账本，这会影响飞书等渠道的崩溃后恢复。需要临时回滚时，先按迁移文档处理最新状态及未完成投递，再停用共享模式；原来的微信独立 profile、`delivery_ledger: false` 模式仍保留兼容。移除核心补丁也须先核对其上是否叠加其他改动，不能直接重置整个 Hermes 仓库。
