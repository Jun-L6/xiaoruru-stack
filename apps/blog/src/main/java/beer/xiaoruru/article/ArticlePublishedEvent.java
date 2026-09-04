package beer.xiaoruru.article;

public record ArticlePublishedEvent(Long articleId, String contentHash) {}
