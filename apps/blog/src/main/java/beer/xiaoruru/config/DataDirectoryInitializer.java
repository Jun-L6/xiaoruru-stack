package beer.xiaoruru.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DataDirectoryInitializer implements ApplicationRunner {
    private final BlogProperties properties;

    public DataDirectoryInitializer(BlogProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        Path root = properties.dataDir().toAbsolutePath().normalize();
        for (String child : new String[] {"database", "uploads", "backups", "logs", "temp"}) {
            Files.createDirectories(root.resolve(child));
        }
        if (!Files.isWritable(root)) {
            throw new IllegalStateException("Blog data directory is not writable: " + root);
        }
    }
}
