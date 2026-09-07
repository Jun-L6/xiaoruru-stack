package beer.xiaoruru.admin;

import beer.xiaoruru.ai.AiJobRepository;
import beer.xiaoruru.ai.AiJobService;
import beer.xiaoruru.ai.AiJobStatus;
import beer.xiaoruru.article.Article;
import beer.xiaoruru.article.ArticleCommand;
import beer.xiaoruru.article.ArticleRepository;
import beer.xiaoruru.article.ArticleService;
import beer.xiaoruru.article.ArticleStatus;
import beer.xiaoruru.article.ContentType;
import beer.xiaoruru.article.ContentForm;
import beer.xiaoruru.article.ClassificationStatus;
import beer.xiaoruru.backup.BackupStatus;
import beer.xiaoruru.backup.BackupRecordRepository;
import beer.xiaoruru.setting.SiteSettingsService;
import beer.xiaoruru.taxonomy.CategoryRepository;
import beer.xiaoruru.taxonomy.TagRepository;
import beer.xiaoruru.taxonomy.TaxonomyService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;
import java.nio.file.Files;
import java.io.IOException;
import beer.xiaoruru.config.BlogProperties;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class AdminController {
    private final ArticleRepository articles;
    private final ArticleService articleService;
    private final CategoryRepository categories;
    private final TagRepository tags;
    private final TaxonomyService taxonomy;
    private final AiJobRepository aiJobs;
    private final AiJobService aiJobService;
    private final BackupRecordRepository backups;
    private final SiteSettingsService settings;
    private final BlogProperties properties;
    private final beer.xiaoruru.ai.AiSettingsService aiSettings;

    public AdminController(ArticleRepository articles, ArticleService articleService,
            CategoryRepository categories, TagRepository tags, TaxonomyService taxonomy,
            AiJobRepository aiJobs, AiJobService aiJobService, BackupRecordRepository backups,
            SiteSettingsService settings, BlogProperties properties, beer.xiaoruru.ai.AiSettingsService aiSettings) {
        this.articles = articles;
        this.articleService = articleService;
        this.categories = categories;
        this.tags = tags;
        this.taxonomy = taxonomy;
        this.aiJobs = aiJobs;
        this.aiJobService = aiJobService;
        this.backups = backups;
        this.settings = settings;
        this.properties = properties;
        this.aiSettings = aiSettings;
    }

    @GetMapping("/admin/login")
    public String login() {
        return "admin/login";
    }

    @GetMapping("/admin")
    public String dashboard(Model model) {
        model.addAttribute("publishedCount", articles.countByStatus(ArticleStatus.PUBLISHED));
        model.addAttribute("draftCount", articles.countByStatus(ArticleStatus.DRAFT));
        model.addAttribute("trashCount", articles.countByStatus(ArticleStatus.TRASHED));
        model.addAttribute("recentArticles", articles.findAllForAdmin().stream().limit(8).toList());
        model.addAttribute("recentJobs", aiJobs.findTop50ByOrderByCreatedAtDesc().stream().limit(5).toList());
        model.addAttribute("recentBackups", backups.findAllByOrderByCreatedAtDesc().stream().limit(5).toList());
        model.addAttribute("categoryCount", categories.count());
        model.addAttribute("tagCount", tags.count());
        model.addAttribute("reviewCount", articles.countByClassificationStatus(ClassificationStatus.REVIEW));
        model.addAttribute("aiFailedCount", aiJobs.countByStatus(AiJobStatus.FAILED));
        model.addAttribute("lastSuccessfulBackup", backups
                .findFirstByStatusOrderByCreatedAtDesc(BackupStatus.SUCCEEDED).orElse(null));
        model.addAttribute("dataUsage", dataUsage());
        model.addAttribute("dataFree", dataFree());
        model.addAttribute("aiEnabled", aiSettings.enabled());
        return "admin/dashboard";
    }

    @GetMapping("/admin/articles")
    public String articleList(@RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) ArticleStatus status,
            @RequestParam(required = false) ContentType contentType,
            @RequestParam(required = false) ContentForm contentForm,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Long tagId,
            @RequestParam(required = false) ClassificationStatus classificationStatus,
            @RequestParam(required = false) LocalDate publishedFrom,
            @RequestParam(required = false) LocalDate publishedTo, Model model) {
        ZoneId zone = ZoneId.systemDefault();
        Instant from = publishedFrom == null ? null : publishedFrom.atStartOfDay(zone).toInstant();
        Instant until = publishedTo == null ? null : publishedTo.plusDays(1).atStartOfDay(zone).toInstant();
        model.addAttribute("articles", articles.filterForAdmin(q.strip(), status, contentType, contentForm, categoryId,
                tagId, classificationStatus, from, until));
        model.addAttribute("categories", categories.findAllByOrderBySortOrderAscNameAsc());
        model.addAttribute("tags", tags.findAllSorted());
        model.addAttribute("statuses", ArticleStatus.values());
        model.addAttribute("contentTypes", ContentType.values());
        model.addAttribute("contentForms", ContentForm.values());
        model.addAttribute("classificationStatuses", ClassificationStatus.values());
        model.addAttribute("q", q.strip());
        model.addAttribute("selectedStatus", status);
        model.addAttribute("selectedContentType", contentType);
        model.addAttribute("selectedContentForm", contentForm);
        model.addAttribute("selectedCategoryId", categoryId);
        model.addAttribute("selectedTagId", tagId);
        model.addAttribute("selectedClassificationStatus", classificationStatus);
        model.addAttribute("publishedFrom", publishedFrom);
        model.addAttribute("publishedTo", publishedTo);
        return "admin/articles";
    }

    @PostMapping("/admin/articles/bulk")
    public String bulkArticles(@RequestParam(name = "ids", required = false) java.util.List<Long> ids,
            @RequestParam String action, RedirectAttributes redirect) {
        if (ids == null || ids.isEmpty()) {
            redirect.addFlashAttribute("error", "请至少选择一篇文章");
            return "redirect:/admin/articles";
        }
        int completed = 0;
        for (Long id : ids.stream().distinct().limit(100).toList()) {
            try {
                switch (action) {
                    case "publish" -> articleService.publish(id);
                    case "withdraw" -> articleService.withdraw(id);
                    case "trash" -> articleService.trash(id);
                    case "classify" -> aiJobService.requestNow(id);
                    default -> throw new IllegalArgumentException("不支持的批量操作");
                }
                completed++;
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // Continue with the remaining selected articles and report the aggregate result.
            }
        }
        redirect.addFlashAttribute(completed > 0 ? "message" : "error",
                "已处理 " + completed + " / " + ids.size() + " 篇文章");
        return "redirect:/admin/articles";
    }

    @GetMapping("/admin/articles/new")
    public String newArticle(Model model) {
        Long uncategorized = categories.findBySlug("uncategorized").orElseThrow().getId();
        model.addAttribute("articleForm", new ArticleCommand(null, "", "", "", ContentType.MARKDOWN,
                ContentForm.LONGFORM, "# 新文章\n\n从这里开始写作。", uncategorized, "", false, false, "", ""));
        addEditorModel(model, null);
        return "admin/article-edit";
    }

    @GetMapping("/admin/articles/{id}")
    public String editArticle(@PathVariable Long id, Model model) {
        Article article = articleService.requireDetailed(id);
        model.addAttribute("articleForm", articleService.toCommand(article));
        addEditorModel(model, article);
        return "admin/article-edit";
    }

    @PostMapping("/admin/articles/save")
    public String saveArticle(@Valid @ModelAttribute("articleForm") ArticleCommand command,
            BindingResult binding, Model model, RedirectAttributes redirect) {
        if (binding.hasErrors()) {
            addEditorModel(model, command.id() == null ? null : articleService.require(command.id()));
            return "admin/article-edit";
        }
        try {
            Article article = articleService.save(command);
            redirect.addFlashAttribute("message", "文章已保存");
            return "redirect:/admin/articles/" + article.getId();
        } catch (IllegalArgumentException exception) {
            binding.reject("save.failed", exception.getMessage());
            addEditorModel(model, command.id() == null ? null : articleService.require(command.id()));
            return "admin/article-edit";
        }
    }

    @PostMapping("/admin/articles/{id}/publish")
    public String publish(@PathVariable Long id, RedirectAttributes redirect) {
        articleService.publish(id);
        redirect.addFlashAttribute("message", "文章已发布");
        return "redirect:/admin/articles/" + id;
    }

    @PostMapping("/admin/articles/{id}/withdraw")
    public String withdraw(@PathVariable Long id, RedirectAttributes redirect) {
        articleService.withdraw(id);
        redirect.addFlashAttribute("message", "文章已撤回为草稿");
        return "redirect:/admin/articles/" + id;
    }

    @PostMapping("/admin/articles/{id}/trash")
    public String trash(@PathVariable Long id, RedirectAttributes redirect) {
        articleService.trash(id);
        redirect.addFlashAttribute("message", "文章已移入回收站");
        return "redirect:/admin/articles";
    }

    @PostMapping("/admin/articles/{id}/restore")
    public String restore(@PathVariable Long id, RedirectAttributes redirect) {
        articleService.restore(id);
        redirect.addFlashAttribute("message", "文章已恢复为草稿");
        return "redirect:/admin/articles/" + id;
    }

    @PostMapping("/admin/articles/{id}/delete")
    public String permanentlyDelete(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            articleService.permanentlyDelete(id);
            redirect.addFlashAttribute("message", "文章已永久删除，文章引用的资源文件仍然保留");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/articles";
    }

    @GetMapping("/admin/categories")
    public String categories(Model model) {
        model.addAttribute("categories", categories.findAllByOrderBySortOrderAscNameAsc());
        return "admin/categories";
    }

    @PostMapping("/admin/categories")
    public String createCategory(@RequestParam String name, @RequestParam String slug,
            @RequestParam(required = false) Long parentId, @RequestParam(defaultValue = "") String description,
            @RequestParam(defaultValue = "") String aiDescription,
            @RequestParam(defaultValue = "") String aiExclusions,
            @RequestParam(defaultValue = "") String aiExamples,
            @RequestParam(defaultValue = "") String aiKeywords, RedirectAttributes redirect) {
        try {
            taxonomy.createCategory(name, slug, parentId, description, aiDescription, aiExclusions, aiExamples, aiKeywords);
            redirect.addFlashAttribute("message", "分类已创建");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/categories";
    }

    @PostMapping("/admin/categories/{id}")
    public String updateCategory(@PathVariable Long id, @RequestParam String name, @RequestParam String slug,
            @RequestParam(defaultValue = "") String description,
            @RequestParam(defaultValue = "") String aiDescription,
            @RequestParam(defaultValue = "") String aiExclusions,
            @RequestParam(defaultValue = "") String aiExamples,
            @RequestParam(defaultValue = "") String aiKeywords,
            @RequestParam(defaultValue = "100") int sortOrder, RedirectAttributes redirect) {
        try {
            taxonomy.updateCategory(id, name, slug, description, aiDescription, aiExclusions, aiExamples, aiKeywords, sortOrder);
            redirect.addFlashAttribute("message", "分类已更新");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/categories";
    }

    @PostMapping("/admin/categories/{id}/toggle")
    public String toggleCategory(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            taxonomy.toggleCategory(id);
            redirect.addFlashAttribute("message", "分类状态已更新");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/categories";
    }

    @PostMapping("/admin/categories/{id}/delete")
    public String deleteCategory(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            taxonomy.deleteCategory(id);
            redirect.addFlashAttribute("message", "分类已删除");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/categories";
    }

    @GetMapping("/admin/tags")
    public String tags(Model model) {
        var allTags = tags.findAllSorted();
        model.addAttribute("tags", allTags);
        model.addAttribute("tagUsage", allTags.stream().collect(Collectors.toMap(
                tag -> tag.getId(), tag -> articles.countByTagsId(tag.getId()))));
        return "admin/tags";
    }

    @PostMapping("/admin/tags/{id}/rename")
    public String renameTag(@PathVariable Long id, @RequestParam String name, RedirectAttributes redirect) {
        try {
            taxonomy.renameTag(id, name);
            redirect.addFlashAttribute("message", "标签已重命名");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/tags";
    }

    @PostMapping("/admin/tags/{id}/merge")
    public String mergeTag(@PathVariable Long id, @RequestParam Long targetId, RedirectAttributes redirect) {
        try {
            taxonomy.mergeTag(id, targetId);
            redirect.addFlashAttribute("message", "标签及文章关联已合并");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/tags";
    }

    @PostMapping("/admin/tags/{id}/delete")
    public String deleteTag(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            taxonomy.deleteTag(id);
            redirect.addFlashAttribute("message", "标签已删除");
        } catch (IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/tags";
    }

    @GetMapping("/admin/settings")
    public String settings(Model model) {
        model.addAttribute("settings", settings.all());
        return "admin/settings";
    }

    @PostMapping("/admin/settings")
    public String saveSettings(@RequestParam Map<String, String> request, RedirectAttributes redirect) {
        Map<String, String> allowed = new LinkedHashMap<>();
        for (String key : new String[] {"site.name", "site.subtitle", "site.author", "site.footer", "site.about"}) {
            if (request.containsKey(key)) {
                allowed.put(key, request.get(key));
            }
        }
        settings.update(allowed);
        redirect.addFlashAttribute("message", "网站设置已保存");
        return "redirect:/admin/settings";
    }

    private void addEditorModel(Model model, Article article) {
        model.addAttribute("article", article);
        model.addAttribute("leafCategories", categories.findEnabledLeaves());
        model.addAttribute("contentTypes", ContentType.values());
        model.addAttribute("contentForms", ContentForm.values());
    }

    private long dataUsage() {
        try (var stream = Files.walk(properties.dataDir().toAbsolutePath().normalize())) {
            return stream.filter(Files::isRegularFile).mapToLong(path -> {
                try { return Files.size(path); } catch (IOException ignored) { return 0L; }
            }).sum();
        } catch (IOException ignored) {
            return -1;
        }
    }

    private long dataFree() {
        try {
            return Files.getFileStore(properties.dataDir().toAbsolutePath().normalize()).getUsableSpace();
        } catch (IOException ignored) {
            return -1;
        }
    }
}
