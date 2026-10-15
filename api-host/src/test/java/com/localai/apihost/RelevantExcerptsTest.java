package com.localai.apihost;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelevantExcerptsTest {
    private static String longDocument() {
        StringBuilder text = new StringBuilder("Annual report introduction.\n");
        for (int i = 0; i < 60; i++) {
            text.append("Section ").append(i).append(" covers general company news and routine updates. ".repeat(6))
                    .append('\n');
        }
        text.append("The warranty period for the turbine is exactly seven years from delivery.\n");
        for (int i = 0; i < 60; i++) {
            text.append("Appendix ").append(i).append(" lists unrelated office supplies. ".repeat(6)).append('\n');
        }
        return text.toString();
    }

    @Test
    void keepsShortTextUnchanged() {
        assertEquals("small text", RelevantExcerpts.select("small text", "anything", 100));
    }

    @Test
    void picksTheSectionsThatMatchTheQuestion() {
        String text = longDocument();
        String picked = RelevantExcerpts.select(text, "What is the turbine warranty period?", 6_000);
        assertTrue(picked.length() <= 6_000);
        assertTrue(picked.contains("seven years from delivery"));
        assertTrue(picked.startsWith("Annual report introduction."));
    }

    @Test
    void fallsBackToTheStartWithoutMatches() {
        String text = longDocument();
        String picked = RelevantExcerpts.select(text, "zzzzqqq", 3_000);
        assertEquals(text.substring(0, 3_000), picked);
        assertFalse(picked.contains("warranty"));
    }
}