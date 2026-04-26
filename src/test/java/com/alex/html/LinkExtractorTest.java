package com.alex.html;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkExtractorTest {

    private static final URI BASE = URI.create("https://example.com/");

    @Test
    void extractsDoubleQuotedHref() {
        List<Link> links = LinkExtractor.extract(BASE, "<a href=\"/page\">About</a>");
        assertEquals(1, links.size());
        assertEquals(URI.create("https://example.com/page"), links.getFirst().url());
        assertEquals("About", links.getFirst().label());
    }

    @Test
    void extractsSingleQuotedHref() {
        List<Link> links = LinkExtractor.extract(BASE, "<a href='/page'>About</a>");
        assertEquals(URI.create("https://example.com/page"), links.getFirst().url());
    }

    @Test
    void extractsBareHref() {
        List<Link> links = LinkExtractor.extract(BASE, "<a href=/page>About</a>");
        assertEquals(URI.create("https://example.com/page"), links.getFirst().url());
    }

    @Test
    void extractsMultipleAnchorsInOrder() {
        String body = "<a href=\"/a\">A</a><a href=\"/b\">B</a><a href=\"/c\">C</a>";
        List<Link> links = LinkExtractor.extract(BASE, body);
        assertEquals(List.of("A", "B", "C"), links.stream().map(Link::label).toList());
    }

    // -------- case insensitivity --------

    @Test
    void caseInsensitiveTagAndAttribute() {
        List<Link> links = LinkExtractor.extract(BASE, "<A HREF=\"/page\">About</A>");
        assertEquals(1, links.size());
    }

    // -------- attribute placement & whitespace --------

    @Test
    void handlesAttributesBeforeHref() {
        String body = "<a class=\"nav\" id=\"x\" href=\"/page\">About</a>";
        assertEquals("/page", links(body).getFirst().url().getPath());
    }

    @Test
    void handlesAttributesAfterHref() {
        String body = "<a href=\"/page\" class=\"nav\" target=\"_blank\">About</a>";
        assertEquals("/page", links(body).getFirst().url().getPath());
    }

    @Test
    void handlesNewlinesAndWhitespaceInsideTag() {
        String body = "<a\n  class=\"x\"\n  href=\"/page\"\n  >About</a>";
        assertEquals("/page", links(body).getFirst().url().getPath());
    }

    @Test
    void handlesWhitespaceAroundEquals() {
        List<Link> links = LinkExtractor.extract(BASE, "<a href = \"/page\">About</a>");
        assertEquals(1, links.size());
    }

    // -------- label cleanup --------

    @Test
    void stripsInnerHtmlFromLabel() {
        String body = "<a href=\"/x\"><strong>Bold</strong> link</a>";
        assertEquals("Bold link", links(body).getFirst().label());
    }

    @Test
    void decodesNamedEntities() {
        String body = "<a href=\"/x\">Tom &amp; Jerry &lt;3&gt;</a>";
        assertEquals("Tom & Jerry <3>", links(body).getFirst().label());
    }

    @Test
    void decodesDecimalNumericEntities() {
        String body = "<a href=\"/x\">It&#39;s here</a>";
        assertEquals("It's here", links(body).getFirst().label());
    }

    @Test
    void decodesHexNumericEntities() {
        String body = "<a href=\"/x\">It&#x27;s here</a>";
        assertEquals("It's here", links(body).getFirst().label());
    }

    @Test
    void unknownEntityPassesThroughUnchanged() {
        String body = "<a href=\"/x\">caf&eacute;</a>";
        assertEquals("caf&eacute;", links(body).getFirst().label());
    }

    @Test
    void collapsesAndTrimsLabelWhitespace() {
        String body = "<a href=\"/x\">   Multi\n   word    label  </a>";
        assertEquals("Multi word label", links(body).getFirst().label());
    }

    @Test
    void allowsEmptyLabelForImageOnlyLink() {
        String body = "<a href=\"/x\"><img src=\"/icon.png\"/></a>";
        assertEquals("", links(body).getFirst().label());
    }

    // -------- URL filtering (delegated to UrlNormalizer) --------

    @Test
    void filtersOutMailtoLinks() {
        assertTrue(LinkExtractor.extract(BASE, "<a href=\"mailto:hi@x.com\">Email</a>").isEmpty());
    }

    @Test
    void filtersOutJavascriptLinks() {
        assertTrue(LinkExtractor.extract(BASE, "<a href=\"javascript:void(0)\">JS</a>").isEmpty());
    }

    @Test
    void filtersOutTelLinks() {
        assertTrue(LinkExtractor.extract(BASE, "<a href=\"tel:+43123\">Call</a>").isEmpty());
    }

    @Test
    void resolvesRelativeUrls() {
        URI base = URI.create("https://example.com/dir/");
        List<Link> links = LinkExtractor.extract(base, "<a href=\"page\">P</a>");
        assertEquals(URI.create("https://example.com/dir/page"), links.getFirst().url());
    }

    @Test
    void resolvesProtocolRelativeUrls() {
        List<Link> links = LinkExtractor.extract(BASE, "<a href=\"//other.com/x\">X</a>");
        assertEquals(URI.create("https://other.com/x"), links.getFirst().url());
    }

    @Test
    void normalizationStripsFragment() {
        List<Link> links = LinkExtractor.extract(BASE, "<a href=\"/page#section\">S</a>");
        assertEquals(URI.create("https://example.com/page"), links.getFirst().url());
    }

    @Test
    void normalizationStripsUserinfo() {
        String body = "<a href=\"https://user:pass@example.com/page\">U</a>";
        assertEquals(URI.create("https://example.com/page"), links(body).getFirst().url());
    }

    // -------- edge cases --------

    @Test
    void emptyBodyReturnsEmptyList() {
        assertTrue(LinkExtractor.extract(BASE, "").isEmpty());
    }

    @Test
    void nullBodyReturnsEmptyList() {
        assertTrue(LinkExtractor.extract(BASE, null).isEmpty());
    }

    @Test
    void nullBaseReturnsEmptyList() {
        assertTrue(LinkExtractor.extract(null, "<a href=\"/x\">X</a>").isEmpty());
    }

    @Test
    void documentWithoutAnchorsReturnsEmptyList() {
        assertTrue(LinkExtractor.extract(BASE, "<p>just paragraphs</p>").isEmpty());
    }

    @Test
    void anchorWithoutHrefIsSkipped() {
        assertTrue(LinkExtractor.extract(BASE, "<a name=\"bookmark\">Anchor</a>").isEmpty());
    }

    @Test
    void anchorWithoutClosingTagIsSkipped() {
        assertTrue(LinkExtractor.extract(BASE, "<a href=\"/x\">Unclosed").isEmpty());
    }

    @Test
    void doesNotMatchAbbrTagBecauseOfWordBoundary() {
        assertTrue(LinkExtractor.extract(BASE, "<abbr href=\"/x\">A</abbr>").isEmpty());
    }

    @Test
    void sameUrlWithDifferentLabelsBothReturned() {
        String body = "<a href=\"/x\">Logo</a><a href=\"/x\">Home</a>";
        List<Link> links = LinkExtractor.extract(BASE, body);
        assertEquals(2, links.size());
        assertEquals("Logo", links.getFirst().label());
        assertEquals("Home", links.get(1).label());
    }

    // -------- ReDoS resilience --------

    @Test
    void terminatesQuicklyOnPathologicalInputWithNoClosingTag() {
        String body = "<a href=\"/x\">".repeat(10_000);
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> LinkExtractor.extract(BASE, body));
    }

    // -------- helper --------

    private static List<Link> links(String body) {
        return LinkExtractor.extract(BASE, body);
    }
}
