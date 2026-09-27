package cc.wechat.observatory.gateway;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLException;

/** Bounded, read-only diagnostic requests. Never follows credential-bearing redirects. */
public final class ConnectionProbe {
    public static final class Reply {
        public final int status;
        public final String body;
        Reply(int status, String body) { this.status = status; this.body = body; }
    }

    public static Reply request(GatewayEndpoint endpoint, String path, String json) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) endpoint.resolve(path).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (json != null) {
                byte[] data = json.getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(data.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream stream = connection.getOutputStream()) { stream.write(data); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (stream != null) {
                try (InputStream input = stream) {
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (bytes.size() + count > 16384) throw new IOException("diagnostic response too large");
                        bytes.write(buffer, 0, count);
                    }
                }
            }
            return new Reply(status, new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        } finally { connection.disconnect(); }
    }

    public static String describeFailure(Throwable error) {
        if (error instanceof UnknownHostException) return "域名无法解析，请检查服务地址或手机网络。";
        if (error instanceof SocketTimeoutException) return "连接或响应超时，请检查 IP、端口、防火墙和手机网络。";
        if (error instanceof ConnectException) return "无法连接服务器，请检查后端是否运行、端口是否开放。";
        if (error instanceof SSLException) return "HTTPS 证书验证失败，请检查证书和地址；HTTP 服务请填写 http://。";
        return "请求失败（" + error.getClass().getSimpleName() + "），请检查地址、服务日志和网络。";
    }

    public static String moduleSummary(boolean registered, String status) {
        if (!registered) return "微信模块：未注册。请保存配置，在 LSPosed 勾选微信后重启微信并登录；若仍未注册，检查 LSPosed 中 WechatGateway 日志及微信版本。";
        if ("offline".equals(status)) return "微信模块：曾注册，目前离线。请打开微信并检查后台运行和网络。";
        if ("disabled".equals(status)) return "微信模块：已停用，请在网页管理台检查设备及密钥状态。";
        if ("ready".equals(status) || "pending".equals(status) || "sending".equals(status))
            return "微信模块：后端记录为在线。实际消息送达仍需发送测试确认。";
        if ("failed".equals(status)) return "微信模块：已注册，但发送任务失败，请查看管理台的发送错误。";
        return "微信模块：已注册，运行状态尚未确认。";
    }
}
