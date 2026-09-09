package beer.xiaoruru.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class TurboStreamDecoderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void restoresReferencedKeysObjectsAndArrays() {
        String payload = "[{\"_1\":2},\"loaderData\",{\"_3\":4},\"messages\",[5],"
                + "{\"_6\":7,\"_8\":9},\"author\",{\"_10\":11},\"content\",{\"_12\":13},"
                + "\"role\",\"assistant\",\"parts\",[14],\"正文\"]";

        var decoded = TurboStreamDecoder.decode(mapper, payload);

        assertThat(decoded.path("loaderData").path("messages").get(0)
                .path("author").path("role").asText()).isEqualTo("assistant");
        assertThat(decoded.path("loaderData").path("messages").get(0)
                .path("content").path("parts").get(0).asText()).isEqualTo("正文");
    }
}
