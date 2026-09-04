package beer.xiaoruru.backup;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class BackupController {
    private final BackupService service;
    private final BackupRecordRepository records;

    public BackupController(BackupService service, BackupRecordRepository records) {
        this.service = service;
        this.records = records;
    }

    @GetMapping("/admin/backups")
    public String list(Model model) {
        model.addAttribute("backups", records.findAllByOrderByCreatedAtDesc());
        return "admin/backups";
    }

    @PostMapping("/admin/backups")
    public String create(RedirectAttributes redirect) {
        try {
            service.start(BackupType.MANUAL);
            redirect.addFlashAttribute("message", "备份任务已开始，可稍后刷新查看状态");
        } catch (IllegalStateException exception) {
            redirect.addFlashAttribute("error", exception.getMessage());
        }
        return "redirect:/admin/backups";
    }

    @GetMapping("/admin/backups/{id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable Long id) {
        Path path = service.requireDownload(id);
        FileSystemResource resource = new FileSystemResource(path);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(path.toFile().length())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(path.getFileName().toString(), StandardCharsets.UTF_8).build().toString())
                .body(resource);
    }
}
