package beer.xiaoruru.similarity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextSimilarityTest {
    @Test
    void detectsFormattingAndLightEditingWithoutDependingOnSourceIds() {
        var original = TextSimilarity.document("""
                ## Docker 构建优化

                在小内存服务器上构建应用时，应限制并发并充分利用缓存。

                第二部分介绍如何把依赖下载和源码编译拆成不同层。
                """);
        var edited = TextSimilarity.document("""
                # Docker 构建优化！

                在小内存服务器上构建应用时，应该限制并发，并充分利用缓存。

                第二部分：把依赖下载与源码编译拆分为不同的层。
                """);

        var result = TextSimilarity.compare(original, edited);

        assertThat(result.global()).isGreaterThan(0.40);
    }

    @Test
    void unrelatedTextDoesNotMatch() {
        var first = TextSimilarity.document("春天沿着河边散步，远处的云慢慢散开。天气很适合拍照。 ");
        var second = TextSimilarity.document("Java 虚拟机通过垃圾回收器管理堆内存，并记录对象之间的引用关系。");

        assertThat(TextSimilarity.compare(first, second).global()).isLessThan(0.1);
    }

    @Test
    void codeBlocksAloneDoNotMakeArticlesDuplicates() {
        var first = TextSimilarity.document("第一篇文章的独立说明文字足够长。\n\n```java\nSystem.out.println(1);\n```");
        var second = TextSimilarity.document("另一篇文章讨论完全不同的主题内容。\n\n```java\nSystem.out.println(1);\n```");

        assertThat(TextSimilarity.compare(first, second).global()).isLessThan(0.2);
    }
}
