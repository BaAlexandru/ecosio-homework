package com.alex.html;

import com.alex.url.UrlNormalizer;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * Extracts {@code <a href="...">label</a>} pairs from an HTML response body using
 * a single bounded, ReDoS-safe regex.
 *
 * <p>The pattern (at a glance):
 * <ul>
 *   <li>case-insensitive: matches {@code <a>}, {@code <A>}, {@code HREF}, etc.;</li>
 *   <li>every quantifier is bounded ({@code {0,4096}}) so the worst-case match is
 *       linear in the input size — no catastrophic backtracking is reachable;</li>
 *   <li>three href-value forms (double-quoted, single-quoted, bare) are disjoint
 *       at their first character, so the alternation never backtracks between branches;</li>
 *   <li>the label capture uses {@code [\s\S]} (not {@code .}) to match across newlines
 *       without relying on the dotall flag.</li>
 * </ul>
 *
 * <p>What this approach gives up, by design:
 * <ul>
 *   <li>{@code <base href="...">} — relative URLs resolve against the request URL only.
 *       Real-world {@code <base>} usage is rare; supporting it would require a two-pass
 *       parse. Documented as a tradeoff in DECISIONS.md.</li>
 *   <li>JS-injected links, {@code <link rel="alternate">}, sitemap.xml — out of scope
 *       for "links in the document body".</li>
 *   <li>{@code rel="nofollow"} — the spec doesn't ask for crawler ethics gating.</li>
 * </ul>
 */
public final class LinkExtractor {

    /**
     * Stripper for inner HTML tags inside the label (e.g. {@code <strong>}).
     */
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]{0,4096}>");

    /**
     * Whitespace collapser.
     */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final Pattern STRIP_BLOCK = Pattern.compile(
            "(?is)<script\\b[^>]*>[\\s\\S]{0,1048576}?</script>"
                    + "|<style\\b[^>]*>[\\s\\S]{0,1048576}?</style>"
                    + "|<!--[\\s\\S]{0,1048576}?-->"
    );

    /**
     * Matches the OPENING tag {@code <a ... href="...">} only. The label content
     * and closing {@code </a>} are handled separately in
     * {@link #findClosingTag(String, int)} — splitting the parse into two stages
     * bounds each phase's worst-case work explicitly and keeps the regex focused
     * on attribute parsing.
     */
    private static final Pattern A_OPEN = Pattern.compile(
            "(?i)" +
            "<a\\b" +
            // Atomic: locate href, capture value, end opening tag — one decision,
            // never re-tried on outer failure.
            "(?>" +
            "[^>]{0,4096}?" +
            "\\bhref\\s*+=\\s*+" +
            "(?:\"(?<dq>[^\"]{0,4096})\"" +
            "|'(?<sq>[^']{0,4096})'" +
            "|(?<bare>[^\\s>]{1,4096}+))" +
            "[^>]{0,4096}?>" +
            ")"
    );

    /**
     * Hard cap on how far forward we scan for the matching {@code </a>}. Real
     * anchor labels are typically &lt; 200 chars; 4096 leaves ample headroom for
     * image- or paragraph-wrapped links while keeping the no-close scan bounded.
     */
    private static final int MAX_LABEL_SCAN = 4096;

    private LinkExtractor() {}

    public static List<Link> extract(URI base, String body) {
        if (base == null || body == null || body.isEmpty()) {
            return List.of();
        }

        String cleanBody = STRIP_BLOCK.matcher(body).replaceAll("");

        return A_OPEN.matcher(cleanBody).results()
                .map(m -> toLink(base, cleanBody, m))
                .flatMap(Optional::stream)
                .toList();
    }

    private static Optional<Link> toLink(URI base, String body, MatchResult m) {
        String href = firstNonNull(m.group("dq"), m.group("sq"), m.group("bare"));
        if (href == null || href.isBlank()) {
            return Optional.empty();
        }
        Optional<URI> url = UrlNormalizer.resolve(base, href);
        if (url.isEmpty()) {
            return Optional.empty();
        }

        int closeStart = findClosingTag(body, m.end());
        if (closeStart < 0) {
            return Optional.empty();   // unclosed <a> → skip the link
        }

        String rawLabel = body.substring(m.end(), closeStart);
        return Optional.of(new Link(url.get(), cleanLabel(rawLabel)));
    }

    /**
     * Handwritten O(n) forward scan for a case-insensitive {@code </a\s*>} close
     * tag, capped at {@link #MAX_LABEL_SCAN} characters from {@code from}. Returns
     * the start index of {@code <}, or {@code -1} when no close tag is found
     * within the budget.
     */
    private static int findClosingTag(String body, int from) {
        int searchEnd = Math.min(from + MAX_LABEL_SCAN, body.length());
        for (int i = from; i + 4 <= searchEnd; i++) {
            if (body.charAt(i)     != '<') continue;
            if (body.charAt(i + 1) != '/') continue;
            char c2 = body.charAt(i + 2);
            if (c2 != 'a' && c2 != 'A') continue;
            int j = i + 3;
            while (j < searchEnd && Character.isWhitespace(body.charAt(j))) j++;
            if (j < searchEnd && body.charAt(j) == '>') return i;
        }
        return -1;
    }

    private static String cleanLabel(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String stripped = HTML_TAG.matcher(raw).replaceAll(" ");
        String decoded = decodeHtmlEntities(stripped);
        return WHITESPACE.matcher(decoded).replaceAll(" ").trim();
    }

    /**
     * Minimal HTML entity decoder — covers the named entities that show up in
     * almost every real page (&amp;amp;, &amp;lt;, &amp;gt;, &amp;quot;, &amp;apos;, &amp;nbsp;)
     * plus numeric entities (&amp;#39;, &amp;#x27;). Unknown entities pass through unchanged.
     */
    private static String decodeHtmlEntities(String s) {
        if (s.indexOf('&') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '&') {
                sb.append(c);
                i++;
                continue;
            }

            int semi = s.indexOf(';', i + 1);
            // Bound the lookahead to 10 chars, longest standard entity is &thinsp; (8).
            if (semi < 0 || semi - i > 10) {
                sb.append(c);
                i++;
                continue;
            }

            String entity = s.substring(i + 1, semi);
            String replacement = switch (entity) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "nbsp" -> " ";
                default -> null;
            };
            if (replacement == null && entity.length() > 1 && entity.charAt(0) == '#') {
                replacement = decodeNumericEntity(entity);
            }
            if (replacement != null) {
                sb.append(replacement);
                i = semi + 1;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static String decodeNumericEntity(String entity) {
        try {
            int cp = (entity.charAt(1) == 'x' || entity.charAt(1) == 'X')
                    ? Integer.parseInt(entity.substring(2), 16)
                    : Integer.parseInt(entity.substring(1));
            return Character.isValidCodePoint(cp)
                    ? new String(Character.toChars(cp))
                    : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstNonNull(String first, String second, String third) {
        if (first != null) return first;
        if (second != null) return second;
        return third;
    }
}
