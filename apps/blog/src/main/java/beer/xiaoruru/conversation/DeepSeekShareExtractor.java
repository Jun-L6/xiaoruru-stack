package beer.xiaoruru.conversation;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class DeepSeekShareExtractor implements ShareConversationExtractor {
    private static final Pattern PATH = Pattern.compile("/share/([a-zA-Z0-9_-]{8,100})/?");
    private static final int MAX_CHARACTERS = 300_000;
    private final ShareHttpClient http;
    private final ObjectMapper mapper;

    DeepSeekShareExtractor(ShareHttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public boolean supports(URI source) {
        return "https".equalsIgnoreCase(source.getScheme())
                && "chat.deepseek.com".equalsIgnoreCase(source.getHost())
                && (source.getPort() == -1 || source.getPort() == 443)
                && source.getUserInfo() == null && source.getQuery() == null && source.getFragment() == null
                && PATH.matcher(source.getPath()).matches();
    }

    @Override
    public ConversationSnapshot extract(URI source) {
        Matcher matcher = PATH.matcher(source.getPath());
        if (!matcher.matches()) throw new IllegalArgumentException("DeepSeek 分享链接格式不正确。");
        URI endpoint = URI.create("https://chat.deepseek.com/api/v0/share/content?share_id=" + matcher.group(1));
        return parse(http.get(endpoint, source, "application/json"));
    }

    ConversationSnapshot parse(String response) {
        JsonNode root;
        try {
            root = mapper.readTree(response);
        } catch (RuntimeException exception) {
            if (exception instanceof IllegalStateException) throw exception;
            throw new IllegalStateException("DeepSeek 分享接口返回了无效数据。");
        }
        JsonNode data = root.path("data").path("biz_data");
        JsonNode messageNodes = data.path("messages");
        if (!messageNodes.isArray()) {
            throw new IllegalStateException("无法解析 DeepSeek 分享内容，接口格式可能已经变化。");
        }
        List<RawMessage> raw = new ArrayList<>();
        int order = 0;
        for (JsonNode node : messageNodes) {
            String role = node.path("role").asText("");
            if (!("USER".equals(role) || "ASSISTANT".equals(role))) continue;
            if (!"FINISHED".equals(node.path("status").asText("FINISHED"))) continue;
            String expected = "USER".equals(role) ? "REQUEST" : "RESPONSE";
            List<String> fragments = new ArrayList<>();
            String direct = node.path("content").asText("").strip();
            if (!direct.isBlank()) fragments.add(direct);
            if (direct.isBlank()) {
                for (JsonNode fragment : node.path("fragments")) {
                    if (expected.equals(fragment.path("type").asText())) {
                        String content = fragment.path("content").asText("").strip();
                        if (!content.isBlank()) fragments.add(content);
                    }
                }
            }
            String content = String.join("\n\n", fragments);
            if (!content.isBlank()) {
                raw.add(new RawMessage(node.path("message_id").asText(""),
                        node.path("parent_id").asText(""), role, content, order++));
            }
        }
        List<ConversationMessage> messages = activeConversation(raw);
        int characters = messages.stream().mapToInt(message -> message.content().length()).sum();
        if (characters > MAX_CHARACTERS) {
            throw new IllegalArgumentException("分享对话超过 30 万字符，暂时无法导入。");
        }
        return new ConversationSnapshot(ConversationProvider.DEEPSEEK,
                data.path("title").asText(""), messages);
    }

    private List<ConversationMessage> activeConversation(List<RawMessage> raw) {
        if (raw.isEmpty()) return List.of();
        Map<String, RawMessage> byId = new HashMap<>();
        raw.stream().filter(message -> !message.id().isBlank()).forEach(message -> byId.put(message.id(), message));
        RawMessage current = raw.get(raw.size() - 1);
        List<RawMessage> chain = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (current != null && (current.id().isBlank() || seen.add(current.id()))) {
            chain.add(current);
            current = current.parent().isBlank() ? null : byId.get(current.parent());
        }
        if (chain.size() > 1) Collections.reverse(chain); else chain = raw;
        return chain.stream().map(message -> new ConversationMessage(
                "USER".equals(message.role()) ? ConversationMessage.Role.USER : ConversationMessage.Role.ASSISTANT,
                message.body())).toList();
    }

    private record RawMessage(String id, String parent, String role, String body, int order) {}
}
