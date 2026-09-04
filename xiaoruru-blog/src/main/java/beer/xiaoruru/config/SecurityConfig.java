package beer.xiaoruru.config;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/admin/login", "/css/**", "/js/**", "/webjars/**",
                                "/media/**", "/actuator/health", "/error", "/favicon.ico").permitAll()
                        .requestMatchers("/admin/**").authenticated()
                        .anyRequest().permitAll())
                .formLogin(form -> form
                        .loginPage("/admin/login")
                        .loginProcessingUrl("/admin/login")
                        .defaultSuccessUrl("/admin", true)
                        .failureUrl("/admin/login?error"))
                .logout(logout -> logout
                        .logoutUrl("/admin/logout")
                        .logoutSuccessUrl("/admin/login?logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID"))
                .headers(headers -> headers
                        .contentTypeOptions(contentType -> {})
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                .sessionManagement(session -> session.maximumSessions(1))
                .addFilterBefore(new LoginRateLimitFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(BlogProperties properties, PasswordEncoder passwordEncoder) {
        String configured = properties.admin().secretHash();
        String secretHash = configured;
        boolean disabled = false;
        if (secretHash == null || secretHash.isBlank()) {
            String secret = properties.admin().secret();
            if (secret != null && !secret.isBlank()) {
                if (secret.length() < 12 || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
                    throw new IllegalArgumentException("管理密钥至少 12 个字符，且 UTF-8 编码不超过 72 字节");
                }
                secretHash = passwordEncoder.encode(secret);
            } else {
                secretHash = passwordEncoder.encode(UUID.randomUUID().toString());
                disabled = true;
                log.warn("Admin login is disabled: configure blog.admin.secret or blog.admin.secret-hash");
            }
        } else if (!secretHash.startsWith("{")) {
            secretHash = "{bcrypt}" + secretHash;
        }
        return new InMemoryUserDetailsManager(User.withUsername("admin")
                .password(secretHash)
                .disabled(disabled)
                .roles("ADMIN")
                .build());
    }
}
