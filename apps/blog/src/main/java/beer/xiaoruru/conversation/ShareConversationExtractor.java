package beer.xiaoruru.conversation;

import java.net.URI;

interface ShareConversationExtractor {
    boolean supports(URI source);
    ConversationSnapshot extract(URI source);
}
