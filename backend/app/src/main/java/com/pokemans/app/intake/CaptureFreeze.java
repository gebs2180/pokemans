package com.pokemans.app.intake;

import java.util.Optional;
import java.util.UUID;

/**
 * Says when a card's photos may no longer change, because they are what a buyer was shown: once it is
 * posted to buyers or sold. Implemented by the modules that know (offers), so intake needn't depend on
 * them. Photo uploads and confirms check every implementation, holding the card's row lock.
 */
public interface CaptureFreeze {

    /** Why the card's photos are frozen, in words for its owner, or empty while they may still change. */
    Optional<String> frozenReason(UUID cardItemId);
}
