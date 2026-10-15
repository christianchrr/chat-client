package com.localai.desktop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ThemePreferenceTest {
    @TempDir
    Path dir;

    @Test
    void defaultsToDarkAndPersistsChoice() {
        assertTrue(new ThemePreference(dir).isDark());
        new ThemePreference(dir).save(false);
        assertFalse(new ThemePreference(dir).isDark());
        new ThemePreference(dir).save(true);
        assertTrue(new ThemePreference(dir).isDark());
    }
}
