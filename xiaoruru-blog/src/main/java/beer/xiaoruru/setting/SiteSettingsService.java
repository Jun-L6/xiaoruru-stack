package beer.xiaoruru.setting;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SiteSettingsService {
    private final SiteSettingRepository repository;

    public SiteSettingsService(SiteSettingRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Map<String, String> all() {
        Map<String, String> result = new LinkedHashMap<>();
        repository.findAll().forEach(setting -> result.put(setting.getKey(), setting.getValue()));
        return result;
    }

    @Transactional(readOnly = true)
    public String get(String key, String defaultValue) {
        return repository.findById(key).map(SiteSetting::getValue).orElse(defaultValue);
    }

    @Transactional
    public void update(Map<String, String> values) {
        values.forEach((key, value) -> {
            SiteSetting setting = repository.findById(key).orElseGet(() -> new SiteSetting(key, value));
            setting.setValue(value == null ? "" : value.strip());
            repository.save(setting);
        });
    }
}
