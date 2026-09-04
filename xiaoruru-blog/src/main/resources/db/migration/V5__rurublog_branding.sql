-- Keep V1-V4 immutable: existing installations must retain valid Flyway checksums.
-- Only replace old defaults; preserve names and footer text customized by the owner.
UPDATE site_settings
SET setting_value = '小茹茹博客', updated_at = CURRENT_TIMESTAMP
WHERE setting_key = 'site.name'
  AND setting_value IN ('纸上代码', 'Paper Blog', 'paper-blog', 'xiaoruru-blog');

UPDATE site_settings
SET setting_value = '由 rurublog 驱动', updated_at = CURRENT_TIMESTAMP
WHERE setting_key = 'site.footer' AND setting_value = '由 Paper Blog 驱动';
