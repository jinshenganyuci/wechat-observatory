# 手机连接诊断（v0.1.3-fork.2）

手机配置页新增“保存并测试连接”，显示后端可达性、API Key 有效性，以及该密钥绑定设备的后端注册/在线状态。服务地址可直接查看，API Key 继续隐藏。

- 测试读取 `/healthz`，然后向 `/module/diagnostics` 提交 API Key。
- 诊断接口只读，不注册设备、不续期心跳、不消费出站队列、不发送消息；仅返回密钥绑定设备的简要状态。
- 手机 App 测试成功不证明微信进程已加载模块，也不证明消息已送达。微信注册状态来自后端最近记录，过期记录显示离线。
- 旧后端返回 404/405 时，App 提示升级镜像，不能验证密钥和微信状态。
- HTTP/IP 直连可用于诊断，HTTPS 使用系统证书校验。禁止携带凭据自动跟随重定向。
- `/module/register` 和 `/module/diagnostics` 在容器日志记录方法、路径、状态码和耗时，不记录密钥、查询字符串或请求体。
- 保存配置的 Root 镜像写入移到后台，并限制 Root 等待时间。保存成功仅表示 App 配置已保存；微信是否读到配置仍应通过注册状态和 LSPosed 日志确认。

## 更新

覆盖安装新版 APK（沿用 fork.1 签名）。在 `.env` 设置
`OBSERVATORY_IMAGE=jinshenganyuci/wechat-observatory:v0.1.3-fork.2`，然后执行：

```bash
docker compose pull observatory db-init
docker compose up -d --remove-orphans
```

没有数据库结构变更；已初始化的部署无需重新 `-seed`。
在 LSPosed 中确认模块作用域为微信，重启手机后打开微信，再运行连接诊断。

```bash
docker compose logs -f --tail=100 observatory
```
