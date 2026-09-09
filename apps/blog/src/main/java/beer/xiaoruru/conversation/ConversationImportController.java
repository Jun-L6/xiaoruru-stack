package beer.xiaoruru.conversation;

import beer.xiaoruru.ai.AiSettingsService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class ConversationImportController {
    private final ConversationImportRepository imports;
    private final ConversationImportService service;
    private final AiSettingsService aiSettings;

    public ConversationImportController(ConversationImportRepository imports,
            ConversationImportService service, AiSettingsService aiSettings) {
        this.imports = imports;
        this.service = service;
        this.aiSettings = aiSettings;
    }

    @GetMapping("/admin/conversation-imports")
    public String list(Model model) {
        model.addAttribute("imports", imports.findTop50ByOrderByCreatedAtDesc());
        model.addAttribute("aiEnabled", aiSettings.enabled());
        return "admin/conversation-imports";
    }

    @PostMapping("/admin/conversation-imports/extract")
    public String extract(@RequestParam String sourceUrl, RedirectAttributes redirect) {
        try {
            ConversationImport job = service.extract(sourceUrl);
            redirect.addFlashAttribute("message", "已提取分享内容，请确认后生成草稿。");
            return "redirect:/admin/conversation-imports/" + job.getId();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
            return "redirect:/admin/conversation-imports";
        }
    }

    @GetMapping("/admin/conversation-imports/{id}")
    public String detail(@PathVariable Long id, Model model, RedirectAttributes redirect) {
        try {
            ConversationImport job = service.require(id);
            model.addAttribute("job", job);
            model.addAttribute("snapshot", service.snapshot(id));
            model.addAttribute("aiEnabled", aiSettings.enabled());
            return "admin/conversation-import-detail";
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
            return "redirect:/admin/conversation-imports";
        }
    }

    @PostMapping("/admin/conversation-imports/{id}/generate")
    public String generate(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            service.requestGeneration(id);
            redirect.addFlashAttribute("message", "生成任务已提交，将按单线程顺序执行。");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/conversation-imports/" + id;
    }
}
