package beer.xiaoruru.ai;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AiAdminController {
    private final AiJobRepository jobs;
    private final AiJobService service;
    private final AiExecutionLogRepository executionLogs;

    public AiAdminController(AiJobRepository jobs, AiJobService service,
            AiExecutionLogRepository executionLogs) {
        this.jobs = jobs;
        this.service = service;
        this.executionLogs = executionLogs;
    }

    @GetMapping("/admin/ai-jobs")
    public String list(Model model) {
        model.addAttribute("jobs", jobs.findTop50ByOrderByCreatedAtDesc());
        model.addAttribute("executionLogs", executionLogs.findTop100ByOrderByCreatedAtDesc());
        return "admin/ai-jobs";
    }

    @PostMapping("/admin/articles/{id}/classify")
    public String classify(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            service.requestNow(id);
            redirect.addFlashAttribute("message", "AI 分类任务已提交");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/articles/" + id;
    }

    @PostMapping("/admin/ai-jobs/{id}/retry")
    public String retry(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            service.retry(id);
            redirect.addFlashAttribute("message", "AI 任务已重新进入队列");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/ai-jobs";
    }
}
