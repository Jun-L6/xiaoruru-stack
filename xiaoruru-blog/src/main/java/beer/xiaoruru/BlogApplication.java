package beer.xiaoruru;

import beer.xiaoruru.config.BlogProperties;
import java.io.IOException;
import java.io.Console;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

@EnableAsync
@EnableScheduling
@SpringBootApplication
@EnableConfigurationProperties(BlogProperties.class)
public class BlogApplication {

    public static void main(String[] args) {
        if (Arrays.asList(args).contains("--generate-admin-hash")) {
            generateAdminHash();
            return;
        }
        initializeDirectories(System.getenv().getOrDefault("BLOG_DATA_DIR", "./data"));
        SpringApplication application = new SpringApplication(BlogApplication.class);
        application.addInitializers(context -> initializeDirectories(
                context.getEnvironment().getProperty("blog.data-dir", "./data")));
        application.run(args);
    }

    private static void generateAdminHash() {
        Console console = System.console();
        if (console == null) {
            throw new IllegalStateException("This command needs an interactive terminal");
        }
        char[] first = console.readPassword("请输入新的管理密钥（至少 12 个字符）：");
        char[] second = console.readPassword("请再次输入：");
        try {
            if (first == null || first.length < 12) {
                throw new IllegalArgumentException("管理密钥至少需要 12 个字符");
            }
            if (!Arrays.equals(first, second)) {
                throw new IllegalArgumentException("两次输入不一致");
            }
            String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode(new String(first));
            console.writer().println(hash);
            console.writer().flush();
        } finally {
            if (first != null) Arrays.fill(first, '\0');
            if (second != null) Arrays.fill(second, '\0');
        }
    }

    private static void initializeDirectories(String configured) {
        Path root = Path.of(configured).toAbsolutePath().normalize();
        try {
            for (String child : new String[] {"database", "uploads", "backups", "logs", "temp"}) {
                Files.createDirectories(root.resolve(child));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot initialize blog data directory: " + root, exception);
        }
    }
}
