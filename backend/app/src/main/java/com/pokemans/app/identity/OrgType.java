package com.pokemans.app.identity;

/** What kind of org this is; members get the matching role (PARTNER or COLLECTOR). */
public enum OrgType {
    PARTNER, COLLECTOR;

    public Role role() {
        return this == PARTNER ? Role.PARTNER : Role.COLLECTOR;
    }
}
