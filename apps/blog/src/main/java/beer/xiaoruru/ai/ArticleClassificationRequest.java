package beer.xiaoruru.ai;

public record ArticleClassificationRequest(
        Long articleId,
        String title,
        String summary,
        String plainContent,
        String contentHash
) {}
