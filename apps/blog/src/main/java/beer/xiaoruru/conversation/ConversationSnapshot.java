package beer.xiaoruru.conversation;

import java.util.List;

public record ConversationSnapshot(
        ConversationProvider provider,
        String sourceTitle,
        List<ConversationMessage> messages
) {
    public ConversationSnapshot {
        if (provider == null || messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("分享内容中没有可用的对话消息");
        }
        sourceTitle = sourceTitle == null ? "" : sourceTitle.strip();
        if (sourceTitle.length() > 300) sourceTitle = sourceTitle.substring(0, 300);
        if (messages.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("分享内容中包含无效消息");
        }
        messages = List.copyOf(messages);
    }

    public int questionAnswerCount() {
        int users = (int) messages.stream().filter(message -> message.role() == ConversationMessage.Role.USER).count();
        int assistants = (int) messages.stream().filter(message -> message.role() == ConversationMessage.Role.ASSISTANT).count();
        return users == 0 ? assistants : Math.min(users, assistants);
    }

    public int characterCount() {
        return messages.stream().mapToInt(message -> message.content().length()).sum();
    }
}
