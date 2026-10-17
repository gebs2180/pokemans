package com.pokemans.app.identity;

import java.util.Locale;
import java.util.Optional;

/** Application roles (HLD §7). Spring authorities are {@code ROLE_<name>}. */
public enum Role {
    ADMIN, PARTNER, COLLECTOR, CONTRIBUTOR;

    public String authority() {
        return "ROLE_" + name();
    }

    /** Case-insensitive lookup; unknown role names are ignored rather than trusted. */
    public static Optional<Role> parse(String value) {
        try {
            return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
