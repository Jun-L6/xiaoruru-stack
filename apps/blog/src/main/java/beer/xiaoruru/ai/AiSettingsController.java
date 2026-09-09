package beer.xiaoruru.ai;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AiSettingsController {
    private final AiSettingsService settings;
    public AiSettingsController(AiSettingsService settings) { this.settings = settings; }

    @GetMapping("/admin/ai-settings")
    public String page(Model model) {
        model.addAttribute("ai", settings.view());
        model.addAttribute("embedding", settings.embeddingView());
        return "admin/ai-settings";
    }

    @PostMapping("/admin/ai-settings")
    public String save(@RequestParam String mode,
            @RequestParam(defaultValue = "") String baseUrl,
            @RequestParam(defaultValue = "") String apiKey,
            @RequestParam(defaultValue = "") String model,
            @RequestParam(defaultValue = "/v1/chat/completions") String completionsPath,
            @RequestParam(defaultValue = "45") int timeoutSeconds,
            @RequestParam(required = false) Double temperature,
            @RequestParam(defaultValue = "false") boolean clearKey,
            RedirectAttributes redirect) {
        try {
            settings.save(new AiSettingsService.Form(mode, baseUrl, apiKey, model, completionsPath,
                    timeoutSeconds, temperature, clearKey));
            redirect.addFlashAttribute("message", "AI 设置已保存，对后续调用生效；未发起模型测试请求。");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/ai-settings";
    }

    @PostMapping("/admin/embedding-settings")
    public String saveEmbedding(@RequestParam String mode,
            @RequestParam(defaultValue = "") String baseUrl,
            @RequestParam(defaultValue = "") String apiKey,
            @RequestParam(defaultValue = "") String model,
            @RequestParam(defaultValue = "/v1/embeddings") String embeddingsPath,
            @RequestParam(defaultValue = "45") int timeoutSeconds,
            @RequestParam(defaultValue = "false") boolean clearKey,
            RedirectAttributes redirect) {
        try {
            settings.saveEmbedding(new AiSettingsService.EmbeddingForm(mode, baseUrl, apiKey, model,
                    embeddingsPath, timeoutSeconds, clearKey));
            redirect.addFlashAttribute("message", "语义相似检测设置已保存；保存时没有发送测试请求。");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/ai-settings";
    }
}
