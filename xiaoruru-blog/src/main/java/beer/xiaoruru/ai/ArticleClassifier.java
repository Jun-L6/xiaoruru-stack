package beer.xiaoruru.ai;

public interface ArticleClassifier {
    ClassificationResult classify(ArticleClassificationRequest request);
}
