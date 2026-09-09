package beer.xiaoruru.conversation;

public enum ConversationProvider {
    CHATGPT("ChatGPT"),
    DEEPSEEK("DeepSeek");

    private final String label;

    ConversationProvider(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
