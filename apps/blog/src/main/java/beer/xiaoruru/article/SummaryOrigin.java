package beer.xiaoruru.article;

/** 摘要来源，保证作者手写摘要不会被自动流程覆盖。 */
public enum SummaryOrigin {
    /** 作者手动填写。 */
    MANUAL,
    /** 大模型生成。 */
    AI,
    /** 系统从正文本地截取生成。 */
    GENERATED
}
