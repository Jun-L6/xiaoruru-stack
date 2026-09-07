package beer.xiaoruru.article;

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
}
