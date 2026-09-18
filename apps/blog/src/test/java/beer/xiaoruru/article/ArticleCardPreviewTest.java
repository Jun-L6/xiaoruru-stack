package beer.xiaoruru.article;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ArticleCardPreviewTest {

    @Test
    void preservesPoemLinesAndParagraphBreaksWhileRemovingMarkup() {
        String html = "<p>年岁渐长<br>悲喜渐淡<br>浮名如梦</p>\n"
                + "<p><strong>安守寻常</strong>，自得宽心。</p>";

        assertThat(ArticleCardPreview.fromRenderedHtml(html, "fallback"))
                .isEqualTo("年岁渐长\n悲喜渐淡\n浮名如梦\n\n安守寻常，自得宽心。");
    }

    @Test
    void decodesEntitiesAndFallsBackToSourceContent() {
        assertThat(ArticleCardPreview.fromRenderedHtml("<blockquote>山风 &amp; 明月</blockquote>", "fallback"))
                .isEqualTo("山风 & 明月");
        assertThat(ArticleCardPreview.fromRenderedHtml(null, "第一行\n第二行"))
                .isEqualTo("第一行\n第二行");
    }
}
