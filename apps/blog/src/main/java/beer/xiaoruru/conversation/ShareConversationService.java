package beer.xiaoruru.conversation;

import java.net.URI;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ShareConversationService {
    private final List<ShareConversationExtractor> extractors;

    ShareConversationService(List<ShareConversationExtractor> extractors) {
        this.extractors = List.copyOf(extractors);
    }

    public ConversationSnapshot extract(String sourceUrl) {
        URI source = parse(sourceUrl);
        ShareConversationExtractor extractor = extractors.stream()
                .filter(candidate -> candidate.supports(source))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "仅支持 chatgpt.com/s、chatgpt.com/share 和 chat.deepseek.com/share 的 HTTPS 链接。"));
        return extractor.extract(source);
    }

    private URI parse(String value) {
        if (value == null || value.isBlank() || value.length() > 1000) {
            throw new IllegalArgumentException("请填写有效的分享链接。");
        }
        try {
            return URI.create(value.strip());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("分享链接格式不正确。");
        }
    }
}
