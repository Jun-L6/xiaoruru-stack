package beer.xiaoruru.conversation;

import java.util.List;

record GeneratedPost(String title, String summary, String markdown, String categorySlug,
                     String contentForm, List<String> tags) {}
