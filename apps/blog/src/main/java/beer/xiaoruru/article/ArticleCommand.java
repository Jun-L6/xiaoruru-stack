package beer.xiaoruru.article;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 管理端保存文章时的输入边界。
 *
 * <p>{@code contentForm == null} 表示“AI 自动判断”，不是缺失必填字段。
 * 标题的必填规则依赖最终内容形态，因此由 {@link ArticleService} 执行跨字段校验。
 */
public record ArticleCommand(
        Long id,
        @Size(max = 200) String title,
        @Size(max = 220) String slug,
        @Size(max = 1000) String summary,
        @NotNull ContentType contentType,
        ContentForm contentForm,
        @NotNull @Size(max = 2_000_000) String content,
        @NotNull Long categoryId,
        @Size(max = 500) String tags,
        @Size(max = 300) String sourceCitation,
        @Size(max = 1000) String sourceUrl,
        Boolean pinned,
        Boolean classificationLocked,
        @Size(max = 200) String seoTitle,
        @Size(max = 500) String seoDescription
) {}
