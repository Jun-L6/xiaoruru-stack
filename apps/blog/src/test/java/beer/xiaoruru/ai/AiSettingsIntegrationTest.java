package beer.xiaoruru.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:ai_settings_test;DB_CLOSE_DELAY=-1",
        "blog.ai.poll-delay=1h"})
@AutoConfigureMockMvc
class AiSettingsIntegrationTest {
    @Autowired AiSettingsService settings;
    @Autowired AiSettingsRepository repository;
    @Autowired OpenAiChatGateway gateway;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @BeforeEach void reset() { repository.deleteAll(); }

    private AiSettingsService.Form cpa(String key) {
        return new AiSettingsService.Form("cpa", "https://ignored.example", key, "model-a",
                "/v1/chat/completions", 10, null, false);
    }

    @Test void defaultNoneAndCpaSaveDoNotNeedAnUpstream() {
        assertThat(settings.enabled()).isFalse();
        assertThatThrownBy(settings::connection).hasMessageContaining("关闭");
        settings.save(cpa("private-api-key"));
        assertThat(settings.enabled()).isTrue();
        assertThat(settings.connection().endpoint().toString())
                .isEqualTo("http://cli-proxy-api:8317/v1/chat/completions");
        assertThat(settings.connection().apiKey()).isEqualTo("private-api-key");
        assertThat(repository.findById(1L).orElseThrow().getDocument()).doesNotContain("private-api-key");
        assertThat(mapper.writeValueAsString(settings.view())).doesNotContain("private-api-key", "encryptedKey");
        settings.save(cpa(""));
        assertThat(settings.connection().apiKey()).isEqualTo("private-api-key");
        settings.save(new AiSettingsService.Form("none", "", "", "", "", 0, null, false));
        assertThat(settings.enabled()).isFalse();
        settings.save(cpa(""));
        assertThat(settings.connection().apiKey()).isEqualTo("private-api-key");
    }

    @Test void changingAddressCannotReuseExistingKeyAndClearDisablesAi() {
        settings.save(new AiSettingsService.Form("external", "https://a.example", "secret", "model",
                "/v1/chat/completions", 10, null, false));
        assertThatThrownBy(() -> settings.save(new AiSettingsService.Form("external", "https://b.example",
                "", "model", "/v1/chat/completions", 10, null, false)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重新填写");
        settings.save(new AiSettingsService.Form("external", "", "", "", "", 0, null, true));
        assertThat(settings.enabled()).isFalse();
        assertThat(mapper.writeValueAsString(settings.view())).contains("\"hasKey\":false");
    }

    @Test void onlyAnActualCallSendsARequestAndUsesTheCurrentSettings() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> payload = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = completion("hello", "model-one").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            settings.save(new AiSettingsService.Form("external", url, "key-one", "model-one",
                    "/v1/chat/completions", 10, 0.2, false));
            assertThat(calls).hasValue(0);
            assertThat(gateway.complete(settings.connection(), "system", "user")).isEqualTo("hello");
            assertThat(authorization.get()).isEqualTo("Bearer key-one");
            assertThat(payload.get()).contains("model-one", "\"temperature\":0.2");
            settings.save(new AiSettingsService.Form("external", url, "key-two", "model-two",
                    "/v1/chat/completions", 10, null, false));
            gateway.complete(settings.connection(), "system", "user");
            assertThat(calls).hasValue(2);
            assertThat(authorization.get()).isEqualTo("Bearer key-two");
            assertThat(payload.get()).contains("model-two").doesNotContain("temperature");
        } finally { server.stop(0); }
    }

    @Test void settingsPageRequiresLoginAndCsrfAndNeverRendersKey() throws Exception {
        mvc.perform(get("/admin/ai-settings")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/admin/ai-settings").with(user("admin")).param("mode", "none"))
                .andExpect(status().isForbidden());
        settings.save(cpa("never-render-this-key"));
        String html = mvc.perform(get("/admin/ai-settings").with(user("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("AI 设置", "ai-mode", "语义相似检测", "embedding-mode")
                .doesNotContain("never-render-this-key", "encryptedKey");
        mvc.perform(post("/admin/ai-settings").with(user("admin")).with(csrf()).param("mode", "none"))
                .andExpect(status().is3xxRedirection());
        assertThat(settings.enabled()).isFalse();
    }

    @Test void embeddingCanReuseAiOrUseExternalEndpointWithoutAddingAService() {
        settings.save(new AiSettingsService.Form("external", "https://chat.example", "chat-secret", "chat-model",
                "/v1/chat/completions", 10, null, false));
        settings.saveEmbedding(new AiSettingsService.EmbeddingForm("reuse", "", "", "embed-model",
                "/v1/embeddings", 12, false));
        assertThat(settings.embeddingConnection().endpoint().toString())
                .isEqualTo("https://chat.example/v1/embeddings");
        assertThat(settings.embeddingConnection().apiKey()).isEqualTo("chat-secret");

        settings.saveEmbedding(new AiSettingsService.EmbeddingForm("external", "https://embed.example",
                "embedding-secret", "embed-external", "/embeddings", 20, false));
        assertThat(settings.embeddingConnection().endpoint().toString())
                .isEqualTo("https://embed.example/embeddings");
        assertThat(settings.embeddingConnection().apiKey()).isEqualTo("embedding-secret");
        assertThat(repository.findById(1L).orElseThrow().getDocument())
                .doesNotContain("chat-secret", "embedding-secret");
        assertThat(mapper.writeValueAsString(settings.embeddingView()))
                .doesNotContain("embedding-secret", "encryptedKey");

        settings.saveEmbedding(new AiSettingsService.EmbeddingForm("disabled", "", "", "", "", 0, false));
        assertThat(settings.embeddingEnabled()).isFalse();
        assertThatThrownBy(settings::embeddingConnection).hasMessageContaining("尚未启用");
    }

    @Test void upstreamErrorsAreVisibleWithoutReflectingSecretResponseBodies() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "your api key is TOP-SECRET".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            settings.save(new AiSettingsService.Form("external",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "TOP-SECRET", "model",
                    "/v1/chat/completions", 10, null, false));
            assertThatThrownBy(() -> gateway.complete(settings.connection(), "system", "user"))
                    .hasMessageContaining("HTTP 401").hasMessageNotContaining("TOP-SECRET");
        } finally { server.stop(0); }
    }

    @Test void invalidAddressesAndHeaderKeysProduceValidationErrors() {
        for (String url : java.util.List.of("", "/relative", "ftp://example.com", "http://example.com:65536", "http://example.com:0")) {
            assertThatThrownBy(() -> settings.save(new AiSettingsService.Form("external", url, "secret", "model",
                    "/v1/chat/completions", 10, null, false)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("地址");
        }
        assertThatThrownBy(() -> settings.save(cpa("secret-不能作为HTTP头")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("secret-");
    }

    @Test void concurrentSavesKeepBothIndependentProfiles() throws Exception {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var first = executor.submit(() -> {
                start.await();
                settings.save(cpa("cpa-concurrent-key"));
                return null;
            });
            var second = executor.submit(() -> {
                start.await();
                settings.save(new AiSettingsService.Form("external", "https://external.example", "external-key",
                        "external-model", "/v1/chat/completions", 5, null, false));
                return null;
            });
            start.countDown();
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            String view = mapper.writeValueAsString(settings.view());
            assertThat(view).contains("model-a", "external-model");
            assertThat(view).doesNotContain("cpa-concurrent-key", "external-key");
        }
    }

    @Test void responseBodyStallTimesOutAndLaterRequestsStillWork() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    if (calls.incrementAndGet() == 1) {
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody().write('{');
                        exchange.getResponseBody().flush();
                        try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                    } else {
                        byte[] body = completion("recovered", "model").getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    }
                } finally { exchange.close(); }
            });
            server.start();
            try {
                settings.save(new AiSettingsService.Form("external", "http://127.0.0.1:" + server.getAddress().getPort(),
                        "key", "model", "/v1/chat/completions", 1, null, false));
                org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(4), () ->
                        assertThatThrownBy(() -> gateway.complete(settings.connection(), "system", "user"))
                                .hasMessageContaining("超时"));
                assertThat(gateway.complete(settings.connection(), "system", "user")).isEqualTo("recovered");
            } finally { release.countDown(); server.stop(0); }
        }
    }

    @Test void oversizedBodyIsRejectedWithoutReturningItsContents() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = completion("x".repeat(2_000_001), "model").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try { exchange.getResponseBody().write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            settings.save(new AiSettingsService.Form("external", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "key", "model", "/v1/chat/completions", 5, null, false));
            assertThatThrownBy(() -> gateway.complete(settings.connection(), "system", "user")).hasMessageContaining("过大");
        } finally { server.stop(0); }
    }

    @Test void malformedSuccessResponseIsReportedWithoutReflectingItsBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] bytes = "not-json-TOP-SECRET".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try { exchange.getResponseBody().write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            settings.save(new AiSettingsService.Form("external", "http://127.0.0.1:" + server.getAddress().getPort(),
                    "key", "model", "/v1/chat/completions", 5, null, false));
            assertThatThrownBy(() -> gateway.complete(settings.connection(), "system", "user"))
                    .hasMessageContaining("格式").hasMessageNotContaining("TOP-SECRET");
        } finally { server.stop(0); }
    }

    private static String completion(String content, String model) {
        return "{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\",\"created\":1,"
                + "\"model\":\"" + model + "\",\"choices\":[{\"index\":0,\"message\":{"
                + "\"role\":\"assistant\",\"content\":\"" + content + "\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,"
                + "\"completion_tokens\":1,\"total_tokens\":2}}";
    }
}
