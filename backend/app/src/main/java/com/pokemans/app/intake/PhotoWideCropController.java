package com.pokemans.app.intake;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only (by path): the one-off making of wide crops for photos checked before they were kept. */
@RestController
@RequestMapping("/api/admin/photos/wide-crops")
class PhotoWideCropController {

    private final PhotoWideCropBackfill backfill;

    PhotoWideCropController(PhotoWideCropBackfill backfill) {
        this.backfill = backfill;
    }

    /** Makes up to {@code limit} wide crops (at most 1,000); run again until {@code remaining} is 0. */
    @PostMapping
    PhotoWideCropBackfill.Result run(@RequestParam(defaultValue = "200") int limit) {
        return backfill.run(limit);
    }
}
