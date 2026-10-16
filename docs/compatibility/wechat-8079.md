# 0.1.13-wechat8079

本版本合并上游 `v0.1.12-multi-phone` 的账户会话协议、当前 fork 的连接诊断与固定签名，并为微信 `8.0.79 / versionCode 3200 arm64` 增加独立适配。

## 修复范围

- 账号读取使用 `pp0.i1.x()`、配置 getter `m(int,Object)`；仅按明确的版本名称与版本号选择映射，不在混淆命名空间中盲目回退。
- 文本发送使用 `f61.t1 → f61.s1 → f61.o1`，收件人是 `h(String)`，不是旧版同名位置的 `g(String)`。构建前验证参数落入的字段，构建后核对请求和本地消息 ID；队列忙时复用同一个请求，避免重复创建消息。
- 新发送路径的入队成功只代表排队；继续读取本地状态，只有状态 2 才确认发送，状态 5 表示失败。超时保留消息 ID 并明确提示结果未确认，不自动重发。
- 在模块进入微信主进程时立即向 LSPosed 与 Android logcat 写入启动信息。生命周期 Hook 先于 WCDB 解析安装；运行时 ClassLoader 就绪后可重试 WCDB Hook，成功安装的方法不会重复 Hook。
- 配置读取优先使用实时 Provider/XSharedPreferences，旧镜像文件作为后备；删除作者服务器地址的隐式默认值，首次配置必须填写自己的服务地址。
- 设置页通过独立的 `/module/connection` 验证 API Key、账户会话协议和注册状态。旧 `/module/diagnostics` 探测请求仍兼容，原诊断报告上传与限流也保留。

## 服务端升级

上游 main `31853e9` 没有本模块所需的 `account_session` / `generation` 注册响应。其服务能启动或 API Key 正确，都不等于与本模块兼容。

1. 备份原 MySQL 数据库和服务环境配置。停止旧服务。
2. 使用本 fork 同一提交的源码或配套服务端二进制；保留原环境变量、API Key、管理密码、数据库 DSN。不要使用另一个分支的旧镜像。
3. 在原服务环境中执行迁移与检查（已有数据库不需要重新创建或重新填充种子）：

   ```sh
   ./wechat-observatory-db -migrate -check
   ./wechat-observatory
   ```

   源码部署对应命令为 `go run ./cmd/bridge-db -migrate -check` 和 `go run ./cmd/bridge`。Docker 部署需要从此 fork 的修复提交重新构建镜像，并按原部署方式运行迁移。
4. 新模块的“测试连接”应显示支持账户会话协议；微信启动前显示“未注册”是正常的。不要把探测成功当作已注入微信。

## 手机安装与验收

APK 包名仍为 `cc.wechat.observatory`，版本名 `0.1.13-wechat8079`，版本号 14。沿用此 fork 已配置的固定调试签名，证书 SHA-256：

```text
6b95e8c196f30a26b0f1dd0d9be43d08d2714cc0a2614dce2f33165043996f4e
```

如果旧模块签名不同而不能覆盖安装，先记下服务地址和 API Key，再卸载旧 **Observatory 模块**，安装此 APK；无需卸载微信。

1. 设置页填写自己的服务地址和 API Key，保存并测试连接。
2. LSPosed 中启用模块并勾选微信；强行停止微信后重新打开，登录账号并等待初始化。
3. LSPosed 日志搜索 `WechatGateway` 和 `module loaded version=0.1.13-wechat8079/14`。也可以通过 ADB 查看：

   ```sh
   adb logcat -v time -s WechatGateway:I
   ```

4. 服务端应出现注册成功，账号、设备角色和账户会话保持一致。向文件传输助手收发一条测试文本；确认控制台入站记录、手机实际发送和控制台发送状态都正确。
5. 断网发送、恢复网络、前后台切换、切换微信账号都需要实机复核。发送超时后先检查手机实际结果，避免手动重复发送。

## 验证证据与边界

目标微信 APK SHA-256：

```text
f2a725bc04302ad218a563533ae79356e66b64aa711b275bc919a8d432f7e15d
```

[`wechat-8.0.79-3200.json`](wechat-8.0.79-3200.json) 是对该 APK 的 46 项 DexSQL 核验结果，包含类/字段/方法定义、参数字段写入及账号读取关系；未包含 APK 或整段反编译代码。使用官方 DexSQL 可复现：

```sh
python3 tools/verify_wechat8079.py --dexsql /path/to/dexsql --apk /path/to/weixin.apk --output /tmp/wechat-8079.json
```

GitHub Actions 运行 Android 单元测试、签名校验、Go race 测试和隔离 MySQL 的迁移/会话测试。构建产物包含源码提交、APK 元信息、签名信息和 SHA-256。

这些检查不能替代真实手机上的 LSPosed 注入和微信服务端行为验证。本次没有连接手机，不声明媒体上传、全部消息类型、插件共存或微信其他版本已完成实机兼容测试。
