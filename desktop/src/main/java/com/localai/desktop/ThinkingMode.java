package com.localai.desktop;

import java.util.Locale;
import java.util.regex.Pattern;

/** Off, On, or Auto: Auto turns reasoning on only for prompts that look hard, since it makes replies start later. */
public enum ThinkingMode {
    AUTO("Auto"), ON("On"), OFF("Off");

    private static final Pattern HARD_PROMPT = Pattern.compile(
            "\\b(solve|prove|calculate|derive|equation|algorithm|debug|refactor|optimi[sz]e|step[- ]by[- ]step"
                    + "|why does|reason|puzzle|riddle|compare and|trade-?offs?|plan|strategy|code|function|bug|sql|regex)\\b"
                    + "|\\d\\s*[-+*/^=]\\s*\\d|```");
    private static final int LONG_PROMPT_CHARS = 600;

    private final String label;

    ThinkingMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static ThinkingMode fromLabel(String text) {
        for (ThinkingMode mode : values()) {
            if (mode.label.equalsIgnoreCase(text == null ? "" : text.strip())) {
                return mode;
            }
        }
        return AUTO;
    }

    public boolean shouldThink(String prompt) {
        return switch (this) {
            case ON -> true;
            case OFF -> false;
            case AUTO -> prompt != null
                    && (prompt.length() >= LONG_PROMPT_CHARS
                    || HARD_PROMPT.matcher(prompt.toLowerCase(Locale.ROOT)).find());
        };
    }
}