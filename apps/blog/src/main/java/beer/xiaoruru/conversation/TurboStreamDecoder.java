package beer.xiaoruru.conversation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** 将 React Router/Turbo Stream 的扁平引用数组还原为普通 JSON 树。 */
final class TurboStreamDecoder {
    private final ArrayNode values;
    private final Map<Integer, JsonNode> memo = new HashMap<>();
    private final Set<Integer> resolving = new HashSet<>();

    private TurboStreamDecoder(ArrayNode values) {
        this.values = values;
    }

    static JsonNode decode(ObjectMapper mapper, String payload) {
        JsonNode node = mapper.readTree(payload);
        if (!(node instanceof ArrayNode array)) {
            throw new IllegalArgumentException("ChatGPT 分享数据不是有效的引用数组");
        }
        return new TurboStreamDecoder(array).resolve(0);
    }

    private JsonNode resolve(int index) {
        if (index < 0 || index >= values.size()) {
            return JsonNodeFactory.instance.nullNode();
        }
        JsonNode cached = memo.get(index);
        if (cached != null) return cached;
        if (!resolving.add(index)) return JsonNodeFactory.instance.nullNode();
        JsonNode source = values.get(index);
        JsonNode result;
        if (source.isObject()) {
            ObjectNode object = JsonNodeFactory.instance.objectNode();
            memo.put(index, object);
            Iterator<Map.Entry<String, JsonNode>> fields = source.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = resolveKey(field.getKey());
                if (key != null && !key.isBlank()) object.set(key, resolveValue(field.getValue()));
            }
            result = object;
        } else if (source.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            memo.put(index, array);
            source.forEach(value -> array.add(resolveValue(value)));
            result = array;
        } else {
            result = source.deepCopy();
        }
        resolving.remove(index);
        memo.put(index, result);
        return result;
    }

    private String resolveKey(String encoded) {
        if (!encoded.startsWith("_") || encoded.length() == 1) return encoded;
        try {
            JsonNode key = resolve(Integer.parseInt(encoded.substring(1)));
            return key.isTextual() ? key.asText() : null;
        } catch (NumberFormatException ignored) {
            return encoded;
        }
    }

    private JsonNode resolveValue(JsonNode value) {
        if (value.isIntegralNumber()) return resolve(value.intValue());
        if (value.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode();
            value.forEach(child -> array.add(resolveValue(child)));
            return array;
        }
        return value.deepCopy();
    }
}
