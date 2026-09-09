package beer.xiaoruru.similarity;

import static org.assertj.core.api.Assertions.assertThat;

import beer.xiaoruru.ai.AiSettingsService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EmbeddingGatewayTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void batchesRequestsAndRestoresProviderIndexOrder() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer secret");
            var body = mapper.readTree(exchange.getRequestBody());
            assertThat(body.path("model").asText()).isEqualTo("embed-model");
            List<String> input = new ArrayList<>();
            body.path("input").forEach(node -> input.add(node.asText()));
            List<Map<String, Object>> data = new ArrayList<>();
            for (int index = input.size() - 1; index >= 0; index--) {
                float marker = Float.parseFloat(input.get(index));
                data.add(new LinkedHashMap<>(Map.of(
                        "index", index,
                        "embedding", List.of(marker, 1f, 2f, 3f, 4f, 5f, 6f, 7f))));
            }
            byte[] response = mapper.writeValueAsBytes(Map.of("data", data));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var connection = new AiSettingsService.EmbeddingConnection(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings"),
                    "secret", "embed-model", Duration.ofSeconds(5));
            List<String> input = java.util.stream.IntStream.range(0, 65).mapToObj(Integer::toString).toList();

            List<float[]> result = new EmbeddingGateway(mapper).embed(connection, input);

            assertThat(requests).hasValue(2);
            assertThat(result).hasSize(65);
            for (int index = 0; index < result.size(); index++) {
                assertThat(result.get(index)[0]).isEqualTo((float) index);
            }
        } finally {
            server.stop(0);
        }
    }
}
