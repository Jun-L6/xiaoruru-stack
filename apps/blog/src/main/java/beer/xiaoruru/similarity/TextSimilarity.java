package beer.xiaoruru.similarity;

import beer.xiaoruru.common.Hashing;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class TextSimilarity {
    private static final Pattern CODE = Pattern.compile("(?s)```.*?```");
    private static final Pattern HTML = Pattern.compile("<[^>]+>");
    private static final Pattern URL = Pattern.compile("https?://\\S+");
    private static final Pattern MARKUP = Pattern.compile("[#*_>`~\\[\\]{}|]");
    private static final Pattern SEPARATORS = Pattern.compile("[\\p{P}\\p{S}\\s]+");
    private static final int SHINGLE_SIZE = 5;

    private TextSimilarity() {}

    static Document document(String markdown) {
        String source = markdown == null ? "" : CODE.matcher(markdown).replaceAll("\n\n");
        source = HTML.matcher(source).replaceAll(" ");
        source = URL.matcher(source).replaceAll(" ");
        List<String> paragraphs = new ArrayList<>();
        List<String> displays = new ArrayList<>();
        for (String raw : source.split("(?:\\R\\s*){2,}")) {
            String value = normalize(raw);
            if (value.length() >= 20) {
                paragraphs.add(value);
                displays.add(MARKUP.matcher(raw).replaceAll(" ").replaceAll("\\s+", " ").strip());
            }
        }
        String normalized = normalize(source);
        return new Document(normalized, Hashing.sha256(normalized), paragraphs, displays, shingles(normalized));
    }

    static Score compare(Document source, Document candidate) {
        if (!source.normalized().isBlank() && source.normalizedHash().equals(candidate.normalizedHash())) {
            return new Score(1, 1, excerpt(candidate.paragraphDisplays()));
        }
        double global = jaccard(source.shingles(), candidate.shingles());
        long total = source.paragraphs().stream().mapToLong(String::length).sum();
        long matched = 0;
        String bestExcerpt = "";
        double best = 0;
        List<Set<Long>> target = candidate.paragraphs().stream().map(TextSimilarity::shingles).toList();
        for (String paragraph : source.paragraphs()) {
            Set<Long> fingerprint = shingles(paragraph);
            double paragraphBest = 0;
            int bestIndex = -1;
            for (int i = 0; i < target.size(); i++) {
                double score = jaccard(fingerprint, target.get(i));
                if (score > paragraphBest) { paragraphBest = score; bestIndex = i; }
            }
            if (paragraphBest >= 0.72) matched += paragraph.length();
            if (paragraphBest > best && bestIndex >= 0) {
                best = paragraphBest;
                bestExcerpt = candidate.paragraphDisplays().get(bestIndex);
            }
        }
        double coverage = total == 0 ? 0 : (double) matched / total;
        return new Score(global, coverage, clip(bestExcerpt, 1000));
    }

    private static String normalize(String value) {
        String text = value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        text = MARKUP.matcher(text).replaceAll(" ");
        return SEPARATORS.matcher(text).replaceAll("").strip();
    }

    private static Set<Long> shingles(String value) {
        Set<Long> result = new HashSet<>();
        if (value.isBlank()) return result;
        if (value.length() <= SHINGLE_SIZE) {
            result.add(hash(value));
            return result;
        }
        for (int i = 0; i <= value.length() - SHINGLE_SIZE; i++) {
            result.add(hash(value.substring(i, i + SHINGLE_SIZE)));
        }
        return result;
    }

    private static long hash(String value) {
        long result = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            result ^= value.charAt(i);
            result *= 0x100000001b3L;
        }
        return result;
    }

    private static double jaccard(Set<Long> left, Set<Long> right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        Set<Long> smaller = left.size() <= right.size() ? left : right;
        Set<Long> larger = left.size() <= right.size() ? right : left;
        long intersection = smaller.stream().filter(larger::contains).count();
        return (double) intersection / (left.size() + right.size() - intersection);
    }

    private static String excerpt(List<String> paragraphs) {
        return paragraphs.isEmpty() ? "" : clip(paragraphs.get(0), 1000);
    }
    private static String clip(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }

    record Document(String normalized, String normalizedHash, List<String> paragraphs,
                    List<String> paragraphDisplays, Set<Long> shingles) {}
    record Score(double global, double coverage, String matchedExcerpt) {}
}
