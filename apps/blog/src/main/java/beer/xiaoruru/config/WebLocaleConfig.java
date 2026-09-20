package beer.xiaoruru.config;

import java.util.Locale;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.FixedLocaleResolver;

/** Keeps rendered pages and the HTTP Content-Language header aligned. */
@Configuration
public class WebLocaleConfig {
    @Bean
    LocaleResolver localeResolver() {
        return new FixedLocaleResolver(Locale.SIMPLIFIED_CHINESE);
    }
}
