package beer.xiaoruru.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import beer.xiaoruru.config.BlogProperties;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

class AiYamlConfigurationTest {

    @Test
    void usesYamlUntilAdminSettingsArePersistedAndDoesNotExposeTheKey() {
        AiSettingsRepository repository = mock(AiSettingsRepository.class);
        BlogProperties properties = mock(BlogProperties.class);
        var openAi = new BlogProperties.OpenAi(true, "https://models.example/v1", "yaml-secret-key",
                "example-model", "/chat/completions", Duration.ofSeconds(30), null);
        when(properties.dataDir()).thenReturn(Path.of(System.getProperty("java.io.tmpdir"), "rurublog-yaml-test"));
        when(properties.ai()).thenReturn(new BlogProperties.Ai(openAi, null));
        when(repository.findById(1L)).thenReturn(Optional.empty());
        var service = new AiSettingsService(repository, new ObjectMapper(), properties,
                mock(PlatformTransactionManager.class));

        AiSettingsService.Connection connection = service.connection();

        assertThat(service.enabled()).isTrue();
        assertThat(connection.endpoint().toString())
                .isEqualTo("https://models.example/v1/chat/completions");
        assertThat(connection.apiKey()).isEqualTo("yaml-secret-key");
        assertThat(new ObjectMapper().writeValueAsString(service.view()))
                .contains("\"source\":\"yaml\"").doesNotContain("yaml-secret-key");
    }
}
