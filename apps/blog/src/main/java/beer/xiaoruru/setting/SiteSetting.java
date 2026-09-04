package beer.xiaoruru.setting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "site_settings")
public class SiteSetting {
    @Id
    @Column(name = "setting_key", length = 100)
    private String key;
    @Column(name = "setting_value", columnDefinition = "CLOB")
    private String value;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SiteSetting() {}

    public SiteSetting(String key, String value) {
        this.key = key;
        this.value = value;
        this.updatedAt = Instant.now();
    }

    public String getKey() { return key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; this.updatedAt = Instant.now(); }
}
