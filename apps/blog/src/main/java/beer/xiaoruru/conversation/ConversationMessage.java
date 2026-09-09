package beer.xiaoruru.conversation;

public record ConversationMessage(Role role, String content) {
    public enum Role { USER, ASSISTANT }

    public ConversationMessage {
        if (role == null || content == null || content.isBlank()) {
            throw new IllegalArgumentException("对话消息的角色和正文不能为空");
        }
        content = content.strip();
    }
}
