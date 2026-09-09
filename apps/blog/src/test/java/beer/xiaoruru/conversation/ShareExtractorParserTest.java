package beer.xiaoruru.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ShareExtractorParserTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ShareHttpClient unusedHttp = new ShareHttpClient();

    @Test
    void parsesChatGptTurboStreamWithoutRegexBacktracking() {
        String payload = "[{\"_1\":2},\"loaderData\",{\"_3\":4,\"_5\":6},\"title\",\"一次分享\","
                + "\"mapping\",[7,16],{\"_8\":9,\"_10\":-5},\"message\","
                + "{\"_11\":12,\"_13\":14,\"_15\":18},\"parent\",\"author\",{\"_19\":20},"
                + "\"content\",{\"_21\":22},\"id\",{\"_8\":24,\"_10\":18},null,\"m1\","
                + "\"role\",\"user\",\"parts\",[23],\"问题\","
                + "{\"_11\":25,\"_13\":27,\"_15\":29},{\"_19\":26},\"assistant\","
                + "{\"_21\":28},[30],\"m2\",\"回答正文\"]";
        String html = "<script>window.__reactRouterContext.streamController.enqueue("
                + mapper.writeValueAsString(payload) + ")</script>";

        ConversationSnapshot result = new ChatGptShareExtractor(unusedHttp, mapper).parse(html);

        assertThat(result.sourceTitle()).isEqualTo("一次分享");
        assertThat(result.messages()).extracting(ConversationMessage::content)
                .containsExactly("问题", "回答正文");
    }

    @Test
    void parsesCurrentAndLegacyDeepSeekShapesWithoutThinkingContent() {
        String response = """
                {"data":{"biz_data":{"title":"多轮内容","messages":[
                  {"message_id":1,"parent_id":null,"role":"USER","status":"FINISHED","content":"问题一"},
                  {"message_id":2,"parent_id":1,"role":"ASSISTANT","status":"FINISHED","content":"回答一","thinking_content":"不要导入"},
                  {"message_id":3,"parent_id":2,"role":"USER","status":"FINISHED","fragments":[{"type":"REQUEST","content":"问题二"}]},
                  {"message_id":4,"parent_id":3,"role":"ASSISTANT","status":"FINISHED","fragments":[{"type":"RESPONSE","content":"回答二"}]}
                ]}}}
                """;

        ConversationSnapshot result = new DeepSeekShareExtractor(unusedHttp, mapper).parse(response);

        assertThat(result.questionAnswerCount()).isEqualTo(2);
        assertThat(result.messages()).extracting(ConversationMessage::content)
                .containsExactly("问题一", "回答一", "问题二", "回答二")
                .doesNotContain("不要导入");
    }
}
