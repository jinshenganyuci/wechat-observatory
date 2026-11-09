# 微信 8.0.80 / 3220 · Observatory 0.1.25

微信升级到 8.0.80 后，旧模块会报告 `Unsupported WeChat version 8.0.80/3220`。新版恢复当前账号识别、文字发送、文件发送、真实群 @ 解析及微信设置中的网关入口，同时保留 8.0.79 / 3200 绑定。

两个版本共用原有发送、回执和清理流程，只选择各自经过核验的微信类、方法与资源。没有增加后台服务、唤醒锁或重试层；文件仍只取得一次原生提交许可，未确认结果不自动重新发送。后端协议、Hermes 插件和飞书配置不需要为本次适配修改。

## 升级

使用原签名覆盖安装 `wechat-observatory-0.1.25-wechat8080-debug.apk`（versionCode 29），保留 LSPosed 对微信的作用域。随后强行停止并重新打开承载机器人的微信；使用分身时操作对应分身。无需卸载模块或微信，也不清除原有私有配置、发送与去重记录。

## 可复现的静态核验

工具为 DexSQL 0.1.1，命令在本地解析 APK，不连接微信服务或发送消息：

```sh
python3 tools/verify_wechat8080.py --dexsql /path/to/dexsql \
  --apk /path/to/wechat8080.apk --output wechat8080-verification.json
python3 tools/verify_wechat8079.py --dexsql /path/to/dexsql \
  --apk /path/to/wechat8079.apk --output wechat8079-verification.json
```

8.0.80 验证器接受本轮逐项核验的两份 APK SHA-256：

- `9d9b6c516e8e07e437c81485fde21fe13ce4f61b37d19e793fff40c71be39c0e`（提供的安装包）。
- `024c53bfc192c463d56b66a676d9133f6d5b1cdcd435601d02d4f6632a333152`（从测试手机拉取的实际安装包）。

两份包的部分 DEX 字节不同，因此各自独立执行 134 项定义、字段含义、调用和布局检查，均通过。验证覆盖账号配置槽与数据库路径、文字 builder 的收件人/正文/消息 ID、LVBuffer 中真实 atuserlist、设置资源与父子布局、文件提交/阶段/原生回执及附件状态。旧 8.0.79 的 58 项检查仍通过。

Android 编译与测试在 Debian amd64、JDK 21、Android SDK 35、Gradle 8.9 环境执行。358 项单元测试通过，包含两版本反射绑定与账号隔离；编译成功不等于真机发送成功。

## 真机验证

当前静态核验和构建已完成；同签名 APK 的安装、网关重新注册、文件传输助手文字/小文件发送、去重与临时副本清理尚待完成。真实群 @ 接收需要管理员发出实际群消息，不能以静态解析检查替代。
