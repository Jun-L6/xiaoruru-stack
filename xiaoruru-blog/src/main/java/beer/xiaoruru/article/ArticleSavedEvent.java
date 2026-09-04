package beer.xiaoruru.article;

public record ArticleSavedEvent(Long articleId, String contentHash) {}
