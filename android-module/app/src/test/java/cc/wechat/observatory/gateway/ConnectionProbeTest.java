package cc.wechat.observatory.gateway;

import org.junit.Test;
import static org.junit.Assert.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class ConnectionProbeTest {
    private ConnectionProbe.Reply request(String response, String body, String expectedPath) throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(3000);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> incoming = executor.submit(() -> {
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(3000);
                        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                        String first = reader.readLine();
                        int length = 0;
                        String line;
                        while ((line=reader.readLine())!=null && !line.isEmpty()) {
                            if (line.toLowerCase().startsWith("content-length:")) length=Integer.parseInt(line.substring(15).trim());
                        }
                        char[] data=new char[length];
                        int read=0;
                        while(read<length) { int n=reader.read(data,read,length-read); if(n<0)break; read+=n; }
                        socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                        return first+"\n"+new String(data);
                    }
                });
                ConnectionProbe.Reply reply = ConnectionProbe.request(GatewayEndpoint.parse("http://127.0.0.1:"+server.getLocalPort()+"/observatory"),expectedPath,body);
                String request=incoming.get(3,TimeUnit.SECONDS);
                assertTrue(request.startsWith((body==null?"GET":"POST")+" /observatory"+expectedPath+" HTTP/1.1"));
                if(body!=null)assertTrue(request.endsWith(body));
                return reply;
            } finally { executor.shutdownNow(); }
        }
    }
    @Test public void preservesPathAndReadsHealth() throws Exception {
        ConnectionProbe.Reply r=request("HTTP/1.1 200 OK\r\nContent-Length: 11\r\nConnection: close\r\n\r\n{\"ok\":true}",null,"/healthz");
        assertEquals(200,r.status);assertEquals("{\"ok\":true}",r.body);
    }
    @Test public void doesNotFollowRedirectWithApiKey() throws Exception {
        ConnectionProbe.Reply r=request("HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/leak\r\nContent-Length: 0\r\nConnection: close\r\n\r\n","{\"api_key\":\"secret\"}","/module/diagnostics");
        assertEquals(302,r.status);
    }
    @Test public void readsUnauthorizedResponse() throws Exception {
        ConnectionProbe.Reply r=request("HTTP/1.1 401 Unauthorized\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}","{}","/module/diagnostics");
        assertEquals(401,r.status);assertEquals("{}",r.body);
    }
    @Test public void distinguishesOfflineFromOnlineAndNeverClaimsDelivery() {
        assertTrue(ConnectionProbe.moduleSummary(false,"ready").contains("未注册"));
        assertTrue(ConnectionProbe.moduleSummary(true,"offline").contains("离线"));
        assertTrue(ConnectionProbe.moduleSummary(true,"ready").contains("实际消息送达仍需"));
        assertTrue(ConnectionProbe.moduleSummary(true,"failed").contains("发送任务失败"));
    }
    @Test public void failureDoesNotEchoSecrets() {
        assertFalse(ConnectionProbe.describeFailure(new IOException("secret-api-key")).contains("secret-api-key"));
        assertTrue(ConnectionProbe.describeFailure(new SocketTimeoutException()).contains("超时"));
        assertTrue(ConnectionProbe.describeFailure(new UnknownHostException()).contains("域名"));
    }
}
