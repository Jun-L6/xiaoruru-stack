package beer.xiaoruru.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

/** 手动契约测试：仅在显式开启时访问第三方公开分享链接。 */
@EnabledIfEnvironmentVariable(named = "BLOG_LIVE_SHARE_TESTS", matches = "true")
class ShareExtractorLiveTest {
    private final ShareHttpClient http = new ShareHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatGptShareExtractor chatGpt = new ChatGptShareExtractor(http, mapper);
    private final DeepSeekShareExtractor deepSeek = new DeepSeekShareExtractor(http, mapper);

    @Test
    void extractsChatGptSingleResponseAndConversation() {
        assertSnapshot(chatGpt.extract(URI.create(
                "https://chatgpt.com/s/t_6aa0f2bbf41081918c190ebbd86c7079")));
        ConversationSnapshot multiple = chatGpt.extract(URI.create(
                "https://chatgpt.com/share/6aa0f2da-d04c-83ea-be35-d698d67fef7d"));
        assertSnapshot(multiple);
        assertThat(multiple.messages().size()).isGreaterThan(2);
    }

    @Test
    void extractsDeepSeekSingleAndConversation() {
        assertSnapshot(deepSeek.extract(URI.create(
                "https://chat.deepseek.com/share/g727lm5t602m0oxe4w")));
        ConversationSnapshot multiple = deepSeek.extract(URI.create(
                "https://chat.deepseek.com/share/eu1atlue5syinpgxox"));
        assertSnapshot(multiple);
        assertThat(multiple.messages().size()).isGreaterThan(2);
    }

    private void assertSnapshot(ConversationSnapshot snapshot) {
        assertThat(snapshot.messages()).isNotEmpty();
        assertThat(snapshot.characterCount()).isPositive();
        assertThat(snapshot.messages()).allSatisfy(message -> assertThat(message.content()).isNotBlank());
    }
}
