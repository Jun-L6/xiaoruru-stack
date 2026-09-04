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
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"hello\"}}]}".getBytes(StandardCharsets.UTF_8);
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
        assertThat(html).contains("AI 设置", "ai-mode").doesNotContain("never-render-this-key", "encryptedKey");
        mvc.perform(post("/admin/ai-settings").with(user("admin")).with(csrf()).param("mode", "none"))
                .andExpect(status().is3xxRedirection());
        assertThat(settings.enabled()).isFalse();
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
}
