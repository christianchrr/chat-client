package com.localai.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Persists the light/dark choice (dark by default) in a one-word file so the next launch (and the setup window) match it. */
final class ThemePreference {
    private final Path file;

    ThemePreference(Path dataDirectory) {
        this.file = dataDirectory.resolve("config").resolve("theme.txt");
    }

    boolean isDark() {
        try {
            return !Files.isRegularFile(file)
                    || !"light".equals(Files.readString(file, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT));
        } catch (IOException e) {
            return true;
        }
    }

    void save(boolean dark) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, dark ? "dark" : "light", StandardCharsets.UTF_8);
        } catch (IOException e) {
            // A theme that cannot be remembered must never break the app.
        }
    }
}
