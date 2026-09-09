package beer.xiaoruru.conversation;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** 仅访问提取器构造出的固定域名地址，不接受任意服务端 URL。 */
@Component
class ShareHttpClient {
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/140 Safari/537.36";
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    String get(URI target, URI referer, String expectedContentType) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", expectedContentType)
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
                .header("User-Agent", USER_AGENT)
                .GET();
        if (referer != null) {
            builder.header("Referer", referer.toString());
        }
        try {
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("分享页面返回 HTTP " + response.statusCode()
                            + "，链接可能已失效或来源站点拒绝了服务器访问。");
                }
                String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase();
                String required = expectedContentType.startsWith("application/json") ? "json" : "html";
                if (!contentType.contains(required)) {
                    throw new IllegalStateException("分享页面返回了无法识别的内容类型。");
                }
                long announced = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (announced > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("分享内容超过 5 MB 安全上限。");
                }
                byte[] bytes = input.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new IllegalStateException("分享内容超过 5 MB 安全上限。");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("提取分享内容的请求被中断。");
        } catch (IOException exception) {
            throw new IllegalStateException("无法连接分享站点，请稍后重试。");
        }
    }
}
