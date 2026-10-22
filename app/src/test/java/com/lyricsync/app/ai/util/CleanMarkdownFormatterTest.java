package com.lyricsync.app.ai.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class CleanMarkdownFormatterTest {

    @Test
    public void testStripHeaders() {
        String input = "## Latar Belakang Lagu\nIni penjelasannya.";
        String clean = CleanMarkdownFormatter.stripMarkdown(input);
        assertFalse("Should not contain ##", clean.contains("##"));
        assertTrue("Should contain header title", clean.contains("Latar Belakang Lagu"));
    }

    @Test
    public void testStripBoldAndItalics() {
        String input = "Lagu ini menceritakan tentang **harapan** dan *cinta*.";
        String clean = CleanMarkdownFormatter.stripMarkdown(input);
        assertFalse("Should not contain **", clean.contains("**"));
        assertFalse("Should not contain single *", clean.contains("*"));
        assertEquals("Lagu ini menceritakan tentang harapan dan cinta.", clean.trim());
    }

    @Test
    public void testFormatBulletLists() {
        String input = "* Poin pertama\n* Poin kedua\n- Poin ketiga";
        String clean = CleanMarkdownFormatter.stripMarkdown(input);
        assertFalse("Should not contain raw bullet asterisk", clean.startsWith("* "));
        assertTrue("Should contain bullet symbol", clean.contains("• Poin pertama"));
        assertTrue("Should contain bullet symbol", clean.contains("• Poin kedua"));
        assertTrue("Should contain bullet symbol", clean.contains("• Poin ketiga"));
    }

    @Test
    public void testMarkdownToHtml() {
        String input = "### Title\nThis has **bold** and *italic* and `code`.\n* item 1\n* item 2";
        String html = CleanMarkdownFormatter.markdownToHtml(input);
        assertTrue("Should contain Title header", html.contains("<h3>Title</h3>") || html.contains("<b>Title</b>"));
        assertTrue("Should contain <b>bold</b>", html.contains("<b>bold</b>"));
        assertTrue("Should contain <i>italic</i>", html.contains("<i>italic</i>"));
        assertTrue("Should contain code", html.contains("<tt>code</tt>") || html.contains("<code>code</code>"));
        assertTrue("Should contain &bull; item 1", html.contains("&bull; item 1"));
        assertFalse("Should not contain raw **", html.contains("**"));
        assertFalse("Should not contain raw ###", html.contains("###"));
    }

    @Test
    public void testMarkdownHeadersHierarchyHtml() {
        String input = "# Main Title\n## Section Subtitle\n### Details";
        String html = CleanMarkdownFormatter.markdownToHtml(input);
        assertTrue("Should contain h1 or large title", html.contains("<h1>Main Title</h1>") || html.contains("<b><font size=\"+2\">Main Title</font></b>"));
        assertTrue("Should contain h2 or section", html.contains("<h2>Section Subtitle</h2>") || html.contains("<b><font size=\"+1\">Section Subtitle</font></b>"));
        assertTrue("Should contain h3", html.contains("<h3>Details</h3>") || html.contains("<b>Details</b>"));
    }

    @Test
    public void testMarkdownBlockquotesHtml() {
        String input = "> This is a song quote\n> Second line of quote";
        String html = CleanMarkdownFormatter.markdownToHtml(input);
        assertTrue("Should contain blockquote tag", html.contains("<blockquote>") && html.contains("</blockquote>"));
        assertFalse("Should not contain raw > marker", html.contains("&gt; This is"));
    }

    @Test
    public void testMarkdownLinksHtml() {
        String input = "Check out [Queen Official](https://queenonline.com) for tour dates.";
        String html = CleanMarkdownFormatter.markdownToHtml(input);
        assertTrue("Should contain anchor tag", html.contains("<a href=\"https://queenonline.com\">Queen Official</a>"));
        assertFalse("Should not contain raw markdown link syntax", html.contains("[Queen Official]"));
    }

    @Test
    public void testMarkdownStrikethroughHtml() {
        String input = "This was ~~cancelled~~ rescheduled.";
        String html = CleanMarkdownFormatter.markdownToHtml(input);
        assertTrue("Should contain strikethrough s or del tag", html.contains("<s>cancelled</s>") || html.contains("<del>cancelled</del>"));
        assertFalse("Should not contain raw ~~", html.contains("~~"));
    }

    @Test
    public void testMarkdownCodeFencePreHtml() {
        String input = "```java\nint speed = 100;\n```";
        String html = CleanMarkdownFormatter.markdownToHtml(input);
        assertTrue("Should contain pre or code container", html.contains("<pre><code>") || html.contains("<tt>"));
        assertFalse("Should not contain raw ```", html.contains("```"));
    }

    @Test
    public void testCleanStreamingTail() {
        // Trailing unclosed bold at the very end
        String unclosedBold = "Streaming in progress **";
        String cleaned = CleanMarkdownFormatter.cleanStreamingTail(unclosedBold);
        assertFalse("Should not leak raw trailing **", cleaned.endsWith("**"));

        // Trailing unclosed code fence is properly closed for rendering
        String unclosedFence = "Here is the code:\n```java\nint a = 1;";
        String cleanedFence = CleanMarkdownFormatter.cleanStreamingTail(unclosedFence);
        assertTrue("Should close unclosed code fence", cleanedFence.endsWith("```"));
        CharSequence formatted = CleanMarkdownFormatter.formatClean(unclosedFence, true);
        assertFalse("Formatted output should not leak raw backticks", formatted.toString().contains("```"));
    }

    @Test
    public void testFormatCleanNotNull() {
        CharSequence result = CleanMarkdownFormatter.formatClean("**Bold text** and *italic*");
        assertNotNull(result);
        assertFalse("Should not contain **", result.toString().contains("**"));
    }

    @Test
    public void testNullOrEmpty() {
        assertEquals("", CleanMarkdownFormatter.stripMarkdown(null));
        assertEquals("", CleanMarkdownFormatter.stripMarkdown("   "));
        assertEquals("", CleanMarkdownFormatter.formatClean(null).toString());
        assertEquals("", CleanMarkdownFormatter.formatClean("   ").toString());
    }

    @Test
    public void testResolveSourceChipWithCombinedTitleAndUrl() {
        CleanMarkdownFormatter.SourceChipData chip = CleanMarkdownFormatter.resolveSourceChip(
                "Wikipedia|https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQFx", null);
        assertNotNull(chip);
        assertEquals("Wikipedia", chip.displayLabel);
        assertEquals("https://vertexaisearch.cloud.google.com/grounding-api-redirect/AUZIYQFx", chip.targetUrl);
    }

    @Test
    public void testResolveSourceChipWithVertexRedirectParam() {
        CleanMarkdownFormatter.SourceChipData chip = CleanMarkdownFormatter.resolveSourceChip(
                "https://vertexaisearch.cloud.google.com/grounding-api-redirect/abc?url=https%3A%2F%2Fwww.rollingstone.com%2Fmusic", null);
        assertNotNull(chip);
        assertEquals("rollingstone.com", chip.displayLabel);
        assertEquals("https://www.rollingstone.com/music", chip.targetUrl);
    }

    @Test
    public void testResolveSourceChipWithRawDomain() {
        CleanMarkdownFormatter.SourceChipData chip = CleanMarkdownFormatter.resolveSourceChip("wikipedia.org", null);
        assertNotNull(chip);
        assertEquals("wikipedia.org", chip.displayLabel);
        assertEquals("https://wikipedia.org", chip.targetUrl);
    }

    @Test
    public void testResolveSourceChipEliminatesVertexHost() {
        CleanMarkdownFormatter.SourceChipData chip = CleanMarkdownFormatter.resolveSourceChip(
                "https://vertexaisearch.cloud.google.com/grounding-api-redirect/xyz123", null);
        assertNotNull(chip);
        assertFalse("Display label must never contain vertex", chip.displayLabel.toLowerCase().contains("vertex"));
        assertEquals("Web Source", chip.displayLabel);
    }

    @Test
    public void testApplyMarkdownNullAndEmptySafety() {
        // Should safely no-op when TextView is null
        CleanMarkdownFormatter.applyMarkdown(null, "**test**", false);
        CleanMarkdownFormatter.applyMarkdown(null, null, false);
    }

    @Test
    public void testFormatCleanWithContextNullFallback() {
        CharSequence res = CleanMarkdownFormatter.formatClean(null, "**Bold** text", false);
        assertNotNull(res);
        assertFalse("Should format without raw **", res.toString().contains("**"));
        assertTrue("Should retain text content", res.toString().contains("Bold"));
    }

    @Test
    public void testMarkwonNullContextReturnsNull() {
        assertNull("getMarkwon with null context should return null", CleanMarkdownFormatter.getMarkwon(null));
    }
}
