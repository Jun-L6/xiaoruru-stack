package beer.xiaoruru.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import beer.xiaoruru.ai.AiSettingsRepository;
import beer.xiaoruru.ai.AiSettingsService;
import beer.xiaoruru.ai.OpenAiChatGateway;
import beer.xiaoruru.article.ArticleStatus;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:conversation_import_test;DB_CLOSE_DELAY=-1",
        "blog.ai.poll-delay=1h", "blog.ai.classification.auto-run=false"
})
@AutoConfigureMockMvc
class ConversationImportIntegrationTest {
    @Autowired ConversationImportRepository imports;
    @Autowired ConversationImportService service;
    @Autowired AiSettingsService settings;
    @Autowired AiSettingsRepository settingsRepository;
    @Autowired ObjectMapper mapper;
    @Autowired MockMvc mvc;
    @MockitoBean OpenAiChatGateway gateway;

    @BeforeEach
    void reset() {
        imports.deleteAll();
        settingsRepository.deleteAll();
    }

    @Test
    void createsOneDraftFromEveryTurnAndClearsTheRawSnapshot() {
        settings.save(new AiSettingsService.Form("cpa", "", "ordinary-key", "writer-model",
                "/v1/chat/completions", 30, null, false));
        ConversationSnapshot snapshot = new ConversationSnapshot(ConversationProvider.DEEPSEEK, "分享标题", List.of(
                new ConversationMessage(ConversationMessage.Role.USER, "第一个问题"),
                new ConversationMessage(ConversationMessage.Role.ASSISTANT, "第一个回答"),
                new ConversationMessage(ConversationMessage.Role.USER, "第二个问题"),
                new ConversationMessage(ConversationMessage.Role.ASSISTANT, "第二个回答")));
        ConversationImport job = imports.save(new ConversationImport("https://chat.deepseek.com/share/example123",
                snapshot, mapper.writeValueAsString(snapshot)));
        when(gateway.complete(any(), any(), contains("第一个问题"))).thenReturn("""
                {"title":"整理后的文章","summary":"文章摘要","markdown":"## 第一部分\\n\\n完整正文",\
                "categorySlug":"ai-digital-tools","contentForm":"LONGFORM","tags":["AI","写作"]}
                """);

        service.requestGeneration(job.getId());
        service.processNext();

        ConversationImport completed = service.require(job.getId());
        assertThat(completed.getStatus()).isEqualTo(ConversationImportStatus.READY);
        assertThat(completed.getSnapshot()).isEqualTo("{}");
        assertThat(completed.getArticle()).isNotNull();
        assertThat(completed.getArticle().getStatus()).isEqualTo(ArticleStatus.DRAFT);
        assertThat(completed.getArticle().getTitle()).isEqualTo("整理后的文章");
        assertThat(completed.getArticle().getContent()).contains("完整正文");
        assertThat(completed.getArticle().isClassificationLocked()).isTrue();
    }

    @Test
    void importWorkspaceRequiresLoginAndRendersTheExtractedPreview() throws Exception {
        ConversationSnapshot snapshot = new ConversationSnapshot(ConversationProvider.CHATGPT, "预览标题", List.of(
                new ConversationMessage(ConversationMessage.Role.USER, "预览问题"),
                new ConversationMessage(ConversationMessage.Role.ASSISTANT, "预览回答")));
        ConversationImport job = imports.save(new ConversationImport("https://chatgpt.com/share/example-id",
                snapshot, mapper.writeValueAsString(snapshot)));

        mvc.perform(get("/admin/conversation-imports")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/admin/conversation-imports/" + job.getId()).with(user("admin")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("预览问题")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("预览回答")));
    }
}
