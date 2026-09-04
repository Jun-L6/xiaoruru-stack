package beer.xiaoruru.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.LinkedHashMap;
import beer.xiaoruru.article.Article;
import beer.xiaoruru.article.ArticleRepository;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class MediaController {
    private final MediaAssetRepository media;
    private final MediaService service;
    private final ArticleRepository articles;

    public MediaController(MediaAssetRepository media, MediaService service, ArticleRepository articles) {
        this.media = media;
        this.service = service;
        this.articles = articles;
    }

    @GetMapping("/admin/media")
    public String list(Model model) {
        var assets = media.findByDeletedFalseOrderByCreatedAtDesc();
        Map<Long, java.util.List<Article>> references = new LinkedHashMap<>();
        for (MediaAsset asset : assets) {
            references.put(asset.getId(), articles.findAllByContentContaining("/media/" + asset.getId() + "/"));
        }
        model.addAttribute("media", assets);
        model.addAttribute("references", references);
        return "admin/media";
    }

    @PostMapping("/admin/media")
    public String upload(@RequestParam MultipartFile file, RedirectAttributes redirect) {
        try {
            service.upload(file);
            redirect.addFlashAttribute("message", "资源已上传");
        } catch (IOException | IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/media";
    }

    @PostMapping("/admin/api/media")
    @ResponseBody
    public Map<String, Object> uploadApi(@RequestParam MultipartFile file) throws IOException {
        MediaAsset asset = service.upload(file);
        return Map.of("id", asset.getId(), "name", asset.getOriginalName(),
                "url", "/media/" + asset.getId() + "/" + asset.getOriginalName(),
                "mimeType", asset.getMimeType());
    }

    @PostMapping("/admin/media/{id}/delete")
    public String delete(@PathVariable Long id, RedirectAttributes redirect) {
        try {
            service.delete(id);
            redirect.addFlashAttribute("message", "资源已移入回收目录");
        } catch (IOException | IllegalArgumentException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/media";
    }

    @GetMapping("/media/{id}/{name:.+}")
    @ResponseBody
    public ResponseEntity<org.springframework.core.io.Resource> get(@PathVariable Long id, @PathVariable String name)
            throws IOException {
        MediaService.MediaFile file = service.load(id);
        MediaType type = MediaType.parseMediaType(file.asset().getMimeType());
        boolean inline = file.asset().getMimeType().startsWith("image/");
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(file.asset().getOriginalName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(type)
                .contentLength(file.asset().getFileSize())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(file.resource());
    }
}
