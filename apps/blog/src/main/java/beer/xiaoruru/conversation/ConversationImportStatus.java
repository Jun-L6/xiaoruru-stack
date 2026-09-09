package beer.xiaoruru.conversation;

public enum ConversationImportStatus {
    EXTRACTED("已提取"),
    PENDING("等待生成"),
    RUNNING("生成中"),
    READY("草稿已生成"),
    FAILED("生成失败");

    private final String label;

    ConversationImportStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
