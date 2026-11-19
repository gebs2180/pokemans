package com.pokemans.app.intake;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only (by path): the reference back the surface view's "Clean back" is made against. Building a new version makes
 * no clean backs itself; the wide-crop backfill makes them.
 */
@RestController
@RequestMapping("/api/admin/clean-backs/reference")
class CleanBackController {

    /** @param photoIds BACK photos to build from, chosen as clean English backs; empty or absent picks them automatically */
    record RebuildRequest(List<UUID> photoIds) {
    }

    private final CleanBackService cleanBacks;

    CleanBackController(CleanBackService cleanBacks) {
        this.cleanBacks = cleanBacks;
    }

    @GetMapping
    CleanBackService.ReferenceView current() {
        return cleanBacks.current();
    }

    /** Builds a new reference version, or says how many more backs are needed. */
    @PostMapping
    CleanBackService.ReferenceView rebuild(@RequestBody(required = false) RebuildRequest request) {
        return cleanBacks.rebuild(request == null ? null : request.photoIds());
    }
}
