package beer.xiaoruru.admin;

import beer.xiaoruru.article.ContentType;
import beer.xiaoruru.render.ContentRenderer;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/api")
public class PreviewController {
    private final ContentRenderer renderer;

    public PreviewController(ContentRenderer renderer) {
        this.renderer = renderer;
    }

    @PostMapping(value = "/preview", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> preview(@RequestBody Map<String, String> request) {
        ContentType type = ContentType.valueOf(request.getOrDefault("contentType", "MARKDOWN"));
        String content = request.getOrDefault("content", "");
        if (content.length() > 2_000_000) {
            return ResponseEntity.status(413).build();
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(renderer.render(type, content));
    }
}
