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
    public void testNullOrEmpty() {
        assertEquals("", CleanMarkdownFormatter.stripMarkdown(null));
        assertEquals("", CleanMarkdownFormatter.stripMarkdown("   "));
    }
}
