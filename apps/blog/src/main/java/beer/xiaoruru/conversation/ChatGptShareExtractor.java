package beer.xiaoruru.conversation;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class ChatGptShareExtractor implements ShareConversationExtractor {
    private static final Pattern PATH = Pattern.compile("/(?:s/t_[A-Za-z0-9_-]{10,100}|share/[A-Za-z0-9-]{10,100})/?");
    private static final String ENQUEUE_PREFIX = "streamController.enqueue(";
    private static final int MAX_CHARACTERS = 300_000;
    private final ShareHttpClient http;
    private final ObjectMapper mapper;

    ChatGptShareExtractor(ShareHttpClient http, ObjectMapper mapper) {
        this.http = http;
        this.mapper = mapper;
    }

    @Override
    public boolean supports(URI source) {
        return "https".equalsIgnoreCase(source.getScheme()) && "chatgpt.com".equalsIgnoreCase(source.getHost())
                && safePort(source) && PATH.matcher(source.getPath()).matches()
                && source.getUserInfo() == null && source.getQuery() == null && source.getFragment() == null;
    }

    @Override
    public ConversationSnapshot extract(URI source) {
        String html = http.get(source, null, "text/html,application/xhtml+xml");
        return parse(html);
    }

    ConversationSnapshot parse(String html) {
        RuntimeException lastFailure = null;
        for (String literal : enqueueLiterals(html)) {
            try {
                String payload = mapper.readValue(literal, String.class);
                if (!payload.startsWith("[")) continue;
                JsonNode root = TurboStreamDecoder.decode(mapper, payload);
                List<RawMessage> raw = new ArrayList<>();
                collectMessages(root, raw, new int[] {0});
                List<ConversationMessage> messages = activeConversation(raw);
                if (messages.isEmpty()) continue;
                int characters = messages.stream().mapToInt(message -> message.content().length()).sum();
                if (characters > MAX_CHARACTERS) {
                    throw new IllegalArgumentException("分享对话超过 30 万字符，暂时无法导入。");
                }
                return new ConversationSnapshot(ConversationProvider.CHATGPT, findTitle(root), messages);
            } catch (RuntimeException exception) {
                lastFailure = exception;
            }
        }
        if (lastFailure instanceof IllegalArgumentException illegal) throw illegal;
        throw new IllegalStateException("无法解析 ChatGPT 分享内容，页面格式可能已经变化。");
    }

    /** 避免在超大 HTML/JS 字符串上使用回溯正则导致栈溢出。 */
    private List<String> enqueueLiterals(String html) {
        List<String> result = new ArrayList<>();
        int from = 0;
        while (from < html.length()) {
            int call = html.indexOf(ENQUEUE_PREFIX, from);
            if (call < 0) break;
            int start = call + ENQUEUE_PREFIX.length();
            while (start < html.length() && Character.isWhitespace(html.charAt(start))) start++;
            if (start >= html.length() || html.charAt(start) != '"') {
                from = start + 1;
                continue;
            }
            boolean escaped = false;
            int end = start + 1;
            for (; end < html.length(); end++) {
                char current = html.charAt(end);
                if (current == '"' && !escaped) break;
                if (current == '\\') escaped = !escaped; else escaped = false;
            }
            if (end < html.length()) result.add(html.substring(start, end + 1));
            from = Math.min(html.length(), end + 1);
        }
        return result;
    }

    private void collectMessages(JsonNode node, List<RawMessage> target, int[] order) {
        Deque<JsonNode> pending = new ArrayDeque<>();
        java.util.Set<JsonNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        if (node != null) pending.push(node);
        while (!pending.isEmpty()) {
            JsonNode current = pending.pop();
            if (current == null || current.isNull() || !visited.add(current)) continue;
            if (current.isObject()) {
                JsonNode messageNode = current.has("message") && current.get("message").isObject()
                        ? current.get("message") : current;
                JsonNode author = messageNode.get("author");
                JsonNode content = messageNode.get("content");
                if (author != null && content != null) {
                    String role = text(author.get("role"));
                    String body = contentText(content);
                    if (("user".equalsIgnoreCase(role) || "assistant".equalsIgnoreCase(role)) && !body.isBlank()) {
                        String parent = text(current.get("parent"));
                        if (parent.isBlank()) parent = text(messageNode.get("parent_id"));
                        target.add(new RawMessage(text(messageNode.get("id")), parent,
                                number(messageNode.get("create_time")), role, body, order[0]++));
                    }
                }
                current.properties().forEach(entry -> pending.push(entry.getValue()));
            } else if (current.isArray()) {
                current.forEach(pending::push);
            }
        }
    }

    private String contentText(JsonNode content) {
        JsonNode parts = content.get("parts");
        if (parts == null || !parts.isArray()) return text(content.get("text"));
        List<String> values = new ArrayList<>();
        for (JsonNode part : parts) {
            String value = part.isTextual() ? part.asText() : text(part.get("text"));
            if (!value.isBlank()) values.add(value.strip());
        }
        return String.join("\n\n", values);
    }

    private List<ConversationMessage> activeConversation(List<RawMessage> raw) {
        Map<String, RawMessage> unique = new LinkedHashMap<>();
        for (RawMessage message : raw) {
            String key = message.id().isBlank() ? message.role() + "\n" + message.body() : message.id();
            unique.merge(key, message, (existing, candidate) -> existing.parent().isBlank()
                    && !candidate.parent().isBlank() ? candidate : existing);
        }
        List<RawMessage> all = new ArrayList<>(unique.values());
        if (all.isEmpty()) return List.of();
        all.sort(Comparator.comparingDouble(RawMessage::created).thenComparingInt(RawMessage::order));
        Map<String, RawMessage> byId = new HashMap<>();
        all.stream().filter(message -> !message.id().isBlank()).forEach(message -> byId.put(message.id(), message));
        java.util.Set<String> parentIds = all.stream().map(RawMessage::parent)
                .filter(parent -> !parent.isBlank()).collect(java.util.stream.Collectors.toSet());
        RawMessage terminal = all.stream().filter(message -> !message.id().isBlank() && !parentIds.contains(message.id()))
                .max(Comparator.comparingDouble(RawMessage::created).thenComparingInt(RawMessage::order))
                .orElse(all.get(all.size() - 1));
        List<RawMessage> chain = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (terminal != null && (terminal.id().isBlank() || seen.add(terminal.id()))) {
            chain.add(terminal);
            terminal = terminal.parent().isBlank() ? null : byId.get(terminal.parent());
        }
        if (chain.size() > 1) {
            Collections.reverse(chain);
            all = chain;
        }
        return all.stream().map(message -> new ConversationMessage(
                "user".equalsIgnoreCase(message.role()) ? ConversationMessage.Role.USER : ConversationMessage.Role.ASSISTANT,
                message.body())).toList();
    }

    private String findTitle(JsonNode root) {
        String postTitle = findTitle(root, true);
        return postTitle.isBlank() ? findTitle(root, false) : postTitle;
    }

    private String findTitle(JsonNode node, boolean requireMessageSlice) {
        Deque<JsonNode> pending = new ArrayDeque<>();
        java.util.Set<JsonNode> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        if (node != null) pending.push(node);
        while (!pending.isEmpty()) {
            JsonNode current = pending.pop();
            if (current == null || current.isNull() || !visited.add(current)) continue;
            if (current.isObject()) {
                if (current.has("message_slice") && current.has("text") && current.get("text").isTextual()) {
                    String value = current.get("text").asText().strip();
                    if (!value.isBlank() && value.length() <= 300) return value;
                }
                if (current.has("mapping") && current.has("title") && current.get("title").isTextual()) {
                    String value = current.get("title").asText().strip();
                    if (!value.isBlank() && value.length() <= 300) return value;
                }
                if (!requireMessageSlice && current.has("title") && current.get("title").isTextual()) {
                    String value = current.get("title").asText().strip();
                    if (!value.isBlank() && value.length() <= 300) return value;
                }
                current.properties().forEach(entry -> pending.push(entry.getValue()));
            } else if (current.isArray()) {
                current.forEach(pending::push);
            }
        }
        return "";
    }

    private static String text(JsonNode node) { return node != null && node.isTextual() ? node.asText().strip() : ""; }
    private static double number(JsonNode node) { return node != null && node.isNumber() ? node.doubleValue() : 0; }
    private static boolean safePort(URI uri) { return uri.getPort() == -1 || uri.getPort() == 443; }

    private record RawMessage(String id, String parent, double created, String role, String body, int order) {}
}
