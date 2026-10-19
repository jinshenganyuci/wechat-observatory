# 从旧核心补丁迁移到原生平台插件

新版不再提供或依赖 Hermes 核心补丁。微信与飞书通过官方平台接口共用 `default`；`gateway.delivery_ledger` 保持 Hermes 原值，原生会话、配对、命令、cron 和恢复流程由 Hermes 管理。插件不提供另一套自动补发循环，也不要求暂停自动升级。

## 已在 default 运行的旧补丁版

这是一次退出旧补丁的维护操作，不是每次 Hermes 升级都要执行的步骤。

1. **定位实际服务。** 查看 `command -v hermes`、启动器指向、`hermes --profile default gateway status --deep`、实际源码的 `git rev-parse HEAD` 和 `git status --short`。数据目录与源码目录不是一回事，不要在无关 checkout 上操作。
2. **暂停新增微信工作并核对未决发送。** 等待活跃对话及微信 cron 执行结束。用管理台核实已接受任务的状态；没有任务 ID 的请求保留原键、原内容及原文件。Hermes 原生待投递义务和插件的旧发送记录不是同一个表，不能只看一边就认定全部结清。
3. **短暂停 default，私密备份。** 备份实际源码的当前 commit/diff、配置、`.env`、Hermes 状态与整个 `observatory/` 目录。SQLite 用一致备份或在所有写入者停止后连同 WAL 保存，不能只复制运行中的主文件。保留飞书配置、授权、会话、模型及原有定时任务。
4. **只退出本项目加过的核心改动。** 对照安装时备份和相同官方 commit，核实旧补丁触及的 `gateway/platforms/base.py`、`gateway/platforms/base_delivery.py`、`gateway/run_startup.py`，以及新版补丁涉及的 `gateway/session_ingress.py` 与测试文件。只撤销确认属于 Observatory 的改动；不运行 `git reset --hard`，不覆盖其他用户改动。已有其他核心改动或无法确定来源时先解决差异，不能以移除补丁为名重置整个仓库。无可写源码的安装包沿其官方安装方式恢复，先在隔离副本验证。
5. **更新插件，保留当前状态。** 按主文档安装新版插件。已在 default 的状态库原地保留，不搬回旧 profile，不运行重新 seed，不改服务地址、设备或机器人绑定。首次打开旧库会把没有任务 ID 的旧 `pending` 标记为 `needs_review`；原请求、幂等键、接收游标、去重记录和旧关联表保留，不在启动时重放。
6. **用未修改的 Hermes 完成回归再启动。** 按[测试说明](../tests/README.md)检查正常收发、原请求短重试、原生恢复、飞书及 cron。重启原 default 一次，确认仅一个微信消费者、飞书恢复连接。先做获准的文件传输助手小消息测试。
7. **恢复原有升级计划。** 如果之前仅为保护 Observatory 补丁停用了 Hermes 升级，核实原计划的名称、配置和频率后恢复同一个计划；不要新建第二个升级任务。没有被本项目暂停过的计划无需修改。

旧 `.patch` 文件已从当前版本删除，历史提交仍可用于审计。不要将旧补丁重新应用到新版本，不用修改 Hermes 核心来满足插件安装检查。

## 仍在独立 wechat profile 的部署

按 [`docs/hermes.md`](../../../docs/hermes.md#从旧-wechat-profile-迁移到-default) 迁移本渠道配置与完整 scope 状态库。不要将旧 profile 的 `delivery_ledger: false`、整个配置、`state.db` 或 cron 目录覆盖到 default。只迁移未来需要的微信任务，不把旧执行作为新执行再跑一次。保留旧 profile，停用其渠道与服务，避免双消费者。

## 新的恢复边界

本次 HTTP 重试复用原幂等键；已交给 Observatory 的任务由后端和手机处理。插件不再自动扫描、猜测或补发旧未确认请求。Hermes 原生恢复如果缺少原发送身份，仍可能产生重复或中断；新版明确保留这一上游边界，不再声称补丁式的端到端严格一次。完整说明见[连接、恢复与防重复](../../../docs/hermes.md#连接恢复与防重复)。

回滚必须先停止当前写入者，保留最新的微信游标和发送记录；不能用迁移前快照覆盖此后产生的飞书或微信工作。尤其不要直接启动带自动重放逻辑的旧插件，把 `needs_review` 当作一批待补发任务。
