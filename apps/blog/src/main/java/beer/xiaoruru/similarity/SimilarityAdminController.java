package beer.xiaoruru.similarity;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class SimilarityAdminController {
    private final ArticleSimilarityService similarity;

    public SimilarityAdminController(ArticleSimilarityService similarity) {
        this.similarity = similarity;
    }

    @PostMapping("/admin/articles/{id}/similarity")
    public String check(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            ArticleSimilarityCheck result = similarity.check(id);
            redirect.addFlashAttribute("message", "相似检测完成：" + result.getRisk().getLabel());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/articles/" + id;
    }

    @PostMapping("/admin/similarity/index")
    public String index(RedirectAttributes redirect) {
        try {
            int count = similarity.indexNextPublished(50);
            redirect.addFlashAttribute("message", count == 0 ? "全部已发布文章的语义索引均为最新。"
                    : "已更新 " + count + " 篇文章的语义索引；如仍有未索引文章，可再次执行。");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/ai-settings";
    }
}
