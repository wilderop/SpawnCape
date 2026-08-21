package com.lawlessmc.spawncape.manager;

import java.util.Locale;

public enum GlideMode {
    FORCE,
    JUMP,
    ASSIST;

    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static GlideMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "force" -> FORCE;
            case "jump" -> JUMP;
            case "assist" -> ASSIST;
            default -> null;
        };
    }
}
