package beer.xiaoruru.ai;

public class DisabledArticleClassifier implements ArticleClassifier {
    @Override
    public ClassificationResult classify(ArticleClassificationRequest request) {
        throw new IllegalStateException("AI 功能尚未配置或未启用");
    }
}
