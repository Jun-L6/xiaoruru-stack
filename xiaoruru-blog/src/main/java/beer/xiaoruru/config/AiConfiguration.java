package beer.xiaoruru.config;

import beer.xiaoruru.ai.ArticleClassifier;
import beer.xiaoruru.ai.DisabledArticleClassifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfiguration {
    @Bean
    @ConditionalOnMissingBean(ArticleClassifier.class)
    ArticleClassifier disabledArticleClassifier() {
        return new DisabledArticleClassifier();
    }
}
