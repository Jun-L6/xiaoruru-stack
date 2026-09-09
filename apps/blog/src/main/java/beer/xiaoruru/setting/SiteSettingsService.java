package beer.xiaoruru.setting;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SiteSettingsService {
    private static final Map<String, String> DISPLAY_DEFAULTS = Map.of(
            "site.article-width", "standard",
            "site.article-font-size", "standard",
            "site.article-line-height", "standard",
            "site.code-theme", "soft",
            "site.toc-mode", "floating");
    private static final Map<String, Set<String>> DISPLAY_CHOICES = Map.of(
            "site.article-width", Set.of("narrow", "standard", "wide"),
            "site.article-font-size", Set.of("small", "standard", "large"),
            "site.article-line-height", Set.of("compact", "standard", "relaxed"),
            "site.code-theme", Set.of("soft", "paper", "dark"),
            "site.toc-mode", Set.of("floating", "hidden"));

    private final SiteSettingRepository repository;

    public SiteSettingsService(SiteSettingRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Map<String, String> all() {
        Map<String, String> result = new LinkedHashMap<>(DISPLAY_DEFAULTS);
        repository.findAll().forEach(setting -> result.put(setting.getKey(), normalize(setting.getKey(), setting.getValue())));
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
            setting.setValue(normalize(key, value));
            repository.save(setting);
        });
    }

    private String normalize(String key, String value) {
        String normalized = value == null ? "" : value.strip();
        Set<String> choices = DISPLAY_CHOICES.get(key);
        if (choices != null && !choices.contains(normalized)) {
            return DISPLAY_DEFAULTS.get(key);
        }
        return normalized;
    }
}
