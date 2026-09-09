package beer.xiaoruru.article;

import java.util.List;

public record GeneratedArticleDraft(
        String title,
        String summary,
        String markdown,
        String categorySlug,
        ContentForm contentForm,
        List<String> tags,
        String sourceUrl
) {}
