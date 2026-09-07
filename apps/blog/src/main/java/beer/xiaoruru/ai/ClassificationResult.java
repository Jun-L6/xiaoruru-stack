package beer.xiaoruru.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import beer.xiaoruru.article.ContentForm;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ClassificationResult(
        String categorySlug,
        ContentForm contentForm,
        double confidence,
        List<String> tags,
        String reason,
        String summary,
        String seoDescription,
        String suggestedCategory
) {}
