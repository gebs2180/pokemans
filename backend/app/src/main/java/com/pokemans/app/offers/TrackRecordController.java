package com.pokemans.app.offers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin: the instant-offer track record. /api/admin/** needs the admin role (SecurityConfig). */
@RestController
class TrackRecordController {

    private final TrackRecordService trackRecord;

    TrackRecordController(TrackRecordService trackRecord) {
        this.trackRecord = trackRecord;
    }

    @GetMapping("/api/admin/offers/track-record")
    TrackRecordService.TrackRecord trackRecord() {
        return trackRecord.trackRecord();
    }
}
