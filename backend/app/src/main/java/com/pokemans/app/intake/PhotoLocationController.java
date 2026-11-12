package com.pokemans.app.intake;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only (by path): the one-off removal of location from originals uploaded before it ran at upload. */
@RestController
@RequestMapping("/api/admin/photos/remove-location")
class PhotoLocationController {

    private final PhotoLocationBackfill backfill;

    PhotoLocationController(PhotoLocationBackfill backfill) {
        this.backfill = backfill;
    }

    /** Checks up to {@code limit} unchecked originals (at most 1,000); run again until {@code remaining} is 0. */
    @PostMapping
    PhotoLocationBackfill.Result run(@RequestParam(defaultValue = "200") int limit) {
        return backfill.run(limit);
    }
}
