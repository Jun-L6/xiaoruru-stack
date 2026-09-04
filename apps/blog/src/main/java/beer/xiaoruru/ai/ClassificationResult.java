package beer.xiaoruru.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ClassificationResult(
        Long categoryId,
        double confidence,
        List<String> tags,
        String reason,
        String summary,
        String seoDescription,
        String suggestedCategory
) {}
