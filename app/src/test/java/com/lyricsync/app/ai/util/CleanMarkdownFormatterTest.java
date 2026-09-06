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
        assertTrue("Should contain <b>Title</b>", html.contains("<b>Title</b>"));
        assertTrue("Should contain <b>bold</b>", html.contains("<b>bold</b>"));
        assertTrue("Should contain <i>italic</i>", html.contains("<i>italic</i>"));
        assertTrue("Should contain <tt>code</tt>", html.contains("<tt>code</tt>"));
        assertTrue("Should contain &bull; item 1", html.contains("&bull; item 1"));
        assertFalse("Should not contain raw **", html.contains("**"));
        assertFalse("Should not contain raw ###", html.contains("###"));
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
}
