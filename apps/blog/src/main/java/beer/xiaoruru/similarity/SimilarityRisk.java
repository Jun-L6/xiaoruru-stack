package beer.xiaoruru.similarity;

public enum SimilarityRisk {
    NONE("未发现明显相似", false),
    RELATED("存在相关内容", false),
    HIGH("疑似重复", true),
    EXACT("正文重复", true);

    private final String label;
    private final boolean confirmationRequired;

    SimilarityRisk(String label, boolean confirmationRequired) {
        this.label = label;
        this.confirmationRequired = confirmationRequired;
    }

    public String getLabel() { return label; }
    public boolean isConfirmationRequired() { return confirmationRequired; }
}
