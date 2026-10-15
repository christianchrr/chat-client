package com.localai.desktop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThinkingModeTest {
    @Test
    void autoThinksOnlyForHardPrompts() {
        assertFalse(ThinkingMode.AUTO.shouldThink("hi, how are you?"));
        assertFalse(ThinkingMode.AUTO.shouldThink("Help me write an email to my landlord"));
        assertTrue(ThinkingMode.AUTO.shouldThink("Solve 12 * 7 + 3"));
        assertTrue(ThinkingMode.AUTO.shouldThink("Why does my function throw a NullPointerException?"));
        assertTrue(ThinkingMode.AUTO.shouldThink("x".repeat(700)));
    }

    @Test
    void onAndOffIgnoreThePrompt() {
        assertTrue(ThinkingMode.ON.shouldThink("hi"));
        assertFalse(ThinkingMode.OFF.shouldThink("Solve 12 * 7 + 3"));
    }

    @Test
    void parsesLabelsAndDefaultsToAuto() {
        assertEquals(ThinkingMode.ON, ThinkingMode.fromLabel("on"));
        assertEquals(ThinkingMode.OFF, ThinkingMode.fromLabel(" Off "));
        assertEquals(ThinkingMode.AUTO, ThinkingMode.fromLabel(null));
        assertEquals(ThinkingMode.AUTO, ThinkingMode.fromLabel("junk"));
    }
}