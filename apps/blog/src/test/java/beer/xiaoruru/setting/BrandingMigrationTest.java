package beer.xiaoruru.setting;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class BrandingMigrationTest {
    @Test
    void replacesLegacyDefaults() throws Exception {
        try (Connection connection = prepare("纸上代码", "由 Paper Blog 驱动")) {
            migrate(connection);
            assertThat(value(connection, "site.name")).isEqualTo("小茹茹博客");
            assertThat(value(connection, "site.footer")).isEqualTo("由 rurublog 驱动");
        }
    }

    @Test
    void preservesOwnerCustomizationsAndIsIdempotent() throws Exception {
        try (Connection connection = prepare("我的自定义站名", "我的自定义页脚")) {
            migrate(connection);
            migrate(connection);
            assertThat(value(connection, "site.name")).isEqualTo("我的自定义站名");
            assertThat(value(connection, "site.footer")).isEqualTo("我的自定义页脚");
        }
    }

    private Connection prepare(String name, String footer) throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID());
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE site_settings (setting_key VARCHAR(100) PRIMARY KEY, "
                    + "setting_value CLOB, updated_at TIMESTAMP)");
        }
        try (var insert = connection.prepareStatement("INSERT INTO site_settings VALUES (?, ?, CURRENT_TIMESTAMP)")) {
            insert.setString(1, "site.name");
            insert.setString(2, name);
            insert.executeUpdate();
            insert.setString(1, "site.footer");
            insert.setString(2, footer);
            insert.executeUpdate();
        }
        return connection;
    }

    private void migrate(Connection connection) {
        ScriptUtils.executeSqlScript(connection,
                new ClassPathResource("db/migration/V5__rurublog_branding.sql"));
    }

    private String value(Connection connection, String key) throws Exception {
        try (var query = connection.prepareStatement("SELECT setting_value FROM site_settings WHERE setting_key=?")) {
            query.setString(1, key);
            try (var rows = query.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        }
    }
}
