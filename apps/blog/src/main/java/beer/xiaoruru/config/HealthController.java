package beer.xiaoruru.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 供容器编排使用的最小健康检查，避免为单个端点加载完整监控框架。 */
@RestController
final class HealthController {
    private static final Map<String, String> UP = Map.of("status", "UP");
    private static final Map<String, String> DOWN = Map.of("status", "DOWN");
    private final DataSource dataSource;

    HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping(value = "/actuator/health", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, String>> health() {
        try (Connection connection = dataSource.getConnection()) {
            if (connection.isValid(2)) {
                return ResponseEntity.ok(UP);
            }
        } catch (SQLException ignored) {
            // 健康检查只返回状态，不把数据库路径或异常细节暴露给公网。
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(DOWN);
    }
}
