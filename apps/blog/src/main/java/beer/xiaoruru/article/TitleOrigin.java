package beer.xiaoruru.article;

/** 标题来源，用来判断轻量内容是否应展示标题。 */
public enum TitleOrigin {
    /** 作者明确填写的标题。 */
    MANUAL,
    /** 系统根据正文生成的内部标题。 */
    GENERATED
}
