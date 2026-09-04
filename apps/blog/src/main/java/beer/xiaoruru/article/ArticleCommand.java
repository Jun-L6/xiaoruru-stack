package beer.xiaoruru.article;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ArticleCommand(
        Long id,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 220) String slug,
        @Size(max = 1000) String summary,
        @NotNull ContentType contentType,
        @NotNull @Size(max = 2_000_000) String content,
        @NotNull Long categoryId,
        @Size(max = 500) String tags,
        Boolean pinned,
        Boolean classificationLocked,
        @Size(max = 200) String seoTitle,
        @Size(max = 500) String seoDescription
) {}
