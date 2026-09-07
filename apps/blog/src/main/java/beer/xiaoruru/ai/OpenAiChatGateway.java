package beer.xiaoruru.ai;

import com.openai.errors.OpenAIServiceException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.List;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

@Component
public class OpenAiChatGateway {
    private static final String COMPLETIONS_SUFFIX = "/chat/completions";
    private static final int MAX_TEXT_LENGTH = 2_000_000;

    public String complete(AiSettingsService.Connection connection, String system, String user) {
        try {
            OpenAiChatOptions.Builder options = OpenAiChatOptions.builder()
                    .baseUrl(springAiBaseUrl(connection.endpoint()))
                    .apiKey(connection.apiKey())
                    .model(connection.model())
                    .timeout(connection.timeout())
                    .maxRetries(0);
            if (connection.temperature() != null) {
                options.temperature(connection.temperature());
            }
            OpenAiChatModel model = OpenAiChatModel.builder().options(options.build()).build();
            var response = model.call(new Prompt(List.of(new SystemMessage(system), new UserMessage(user))));
            String content = response == null || response.getResult() == null
                    ? null : response.getResult().getOutput().getText();
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("AI 接口未返回文本内容。");
            }
            if (content.length() > MAX_TEXT_LENGTH) {
                throw new IllegalStateException("AI 返回内容过大。");
            }
            return content;
        } catch (OpenAIServiceException exception) {
            throw new IllegalStateException("AI 接口返回 HTTP " + exception.statusCode()
                    + "，请检查接口、API Key 和模型配置。");
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (causedByTimeout(exception)) {
                throw new IllegalStateException("AI 接口请求超时。");
            }
            throw new IllegalStateException("无法连接 AI 接口，请检查网络和服务配置。");
        }
    }

    private String springAiBaseUrl(URI endpoint) {
        String value = endpoint.toString();
        if (!value.endsWith(COMPLETIONS_SUFFIX)) {
            throw new IllegalStateException("AI 调用路径必须以 /chat/completions 结尾。");
        }
        return value.substring(0, value.length() - COMPLETIONS_SUFFIX.length());
    }

    private boolean causedByTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof HttpTimeoutException || current instanceof InterruptedIOException
                    || current instanceof java.util.concurrent.TimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
