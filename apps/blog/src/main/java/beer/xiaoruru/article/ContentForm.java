package beer.xiaoruru.article;

/** 文章的表达形态，用于校验编辑规则并选择前台展示样式。 */
public enum ContentForm {
    LONGFORM("长文"),
    ESSAY("随笔"),
    NOTE("笔记"),
    MOMENT("动态"),
    EXCERPT("摘录");

    private final String label;

    ContentForm(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 轻量内容可以只有正文，标题仅作为内部索引。 */
    public boolean isTitleOptional() {
        return this == MOMENT || this == EXCERPT;
    }

    /** 紧凑形态在列表和详情页不使用完整长文框架。 */
    public boolean isCompact() {
        return this == MOMENT || this == EXCERPT;
    }
}
