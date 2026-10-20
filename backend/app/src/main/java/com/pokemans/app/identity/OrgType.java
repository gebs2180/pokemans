package com.pokemans.app.identity;

/** What kind of org this is; members get the matching role. */
public enum OrgType {
    PARTNER, COLLECTOR,
    /** Shares raw photos of cards it had graded, with the grades, for training (consent required). */
    CONTRIBUTOR,
    /** A shop or dealer that takes up instant offers on members' cards (buyer terms required). */
    BUYER;

    public Role role() {
        return switch (this) {
            case PARTNER -> Role.PARTNER;
            case COLLECTOR -> Role.COLLECTOR;
            case CONTRIBUTOR -> Role.CONTRIBUTOR;
            case BUYER -> Role.BUYER;
        };
    }
}
