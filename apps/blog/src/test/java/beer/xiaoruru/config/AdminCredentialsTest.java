package beer.xiaoruru.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

class AdminCredentialsTest {
    private final SecurityConfig configuration = new SecurityConfig();
    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    private BlogProperties properties(String hash, String secret) {
        BlogProperties properties = mock(BlogProperties.class);
        when(properties.admin()).thenReturn(new BlogProperties.Admin(hash, secret, Duration.ofHours(1)));
        return properties;
    }

    @Test
    void yamlSecretIsEncodedBeforeAuthentication() {
        var user = configuration.userDetailsService(properties("", "a-long-local-secret"), encoder).loadUserByUsername("admin");
        assertThat(user.isEnabled()).isTrue();
        assertThat(user.getPassword()).startsWith("{bcrypt}").doesNotContain("a-long-local-secret");
        assertThat(encoder.matches("a-long-local-secret", user.getPassword())).isTrue();
        assertThat(encoder.matches("wrong-secret", user.getPassword())).isFalse();
    }

    @Test
    void existingHashTakesPrecedenceOverLocalSecret() {
        String hash = encoder.encode("existing-admin-secret");
        var user = configuration.userDetailsService(properties(hash, "another-local-secret"), encoder).loadUserByUsername("admin");
        assertThat(user.getPassword()).isEqualTo(hash);
        assertThat(encoder.matches("another-local-secret", user.getPassword())).isFalse();
    }

    @Test
    void unconfiguredAdminIsDisabled() {
        var user = configuration.userDetailsService(properties("", null), encoder).loadUserByUsername("admin");
        assertThat(user.isEnabled()).isFalse();
    }

    @Test
    void shortAndOverlongPlainSecretsAreRejectedWithoutLeakingTheirValue() {
        assertThatThrownBy(() -> configuration.userDetailsService(properties("", "too-short"), encoder))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("too-short");
        assertThatThrownBy(() -> configuration.userDetailsService(properties("", "密".repeat(25)), encoder))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void propertyStringDoesNotExposeCredentials() {
        assertThat(properties("sensitive-hash", "sensitive-secret").admin().toString())
                .contains("REDACTED").doesNotContain("sensitive");
    }
}
