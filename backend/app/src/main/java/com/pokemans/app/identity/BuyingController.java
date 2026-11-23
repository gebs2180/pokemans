package com.pokemans.app.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where the caller stands on buying, and the admin's queue of requests to buy ({@code /api/admin/**} is admin-only by
 * path in SecurityConfig). Asking to buy is in the offers module, since it records the ship-to address with it.
 */
@RestController
class BuyingController {

    record DeclineBody(@NotBlank @Size(max = 2000) String note) {
    }

    private final Buying buying;

    BuyingController(Buying buying) {
        this.buying = buying;
    }

    @GetMapping("/api/me/buying")
    Buying.State state() {
        return buying.state();
    }

    @GetMapping("/api/admin/buyers")
    List<Buying.Request> requests() {
        return buying.requests();
    }

    @PostMapping("/api/admin/buyers/{requestId}/approve")
    Buying.Request approve(@PathVariable UUID requestId) {
        return buying.decide(requestId, true, null);
    }

    @PostMapping("/api/admin/buyers/{requestId}/decline")
    Buying.Request decline(@PathVariable UUID requestId, @Valid @RequestBody DeclineBody body) {
        return buying.decide(requestId, false, body.note());
    }
}
