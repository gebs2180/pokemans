package com.pokemans.app.notify;

import com.pokemans.app.identity.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's email choices. Only the daily new-listings email can be turned off. */
@RestController
class EmailPreferencesController {

    /** @param digest whether the daily new-listings email goes to this user, in each buyer org they're in */
    record EmailPreferencesView(boolean digest) {
    }

    record EmailPreferencesBody(@NotNull Boolean digest) {
    }

    private final CurrentUser currentUser;
    private final EmailPreferences preferences;

    EmailPreferencesController(CurrentUser currentUser, EmailPreferences preferences) {
        this.currentUser = currentUser;
        this.preferences = preferences;
    }

    @GetMapping("/api/me/email-preferences")
    @Transactional
    EmailPreferencesView get() {
        return new EmailPreferencesView(preferences.digest(currentUser.id()));
    }

    @PutMapping("/api/me/email-preferences")
    @Transactional
    EmailPreferencesView put(@Valid @RequestBody EmailPreferencesBody body) {
        preferences.setDigest(currentUser.id(), body.digest());
        return new EmailPreferencesView(body.digest());
    }
}
