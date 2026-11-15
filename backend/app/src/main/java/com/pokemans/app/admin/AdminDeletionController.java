package com.pokemans.app.admin;

import com.pokemans.app.admin.AdminDeletionService.Done;
import com.pokemans.app.admin.AdminDeletionService.Plan;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only (enforced by path in SecurityConfig): the hard delete of a test or junk card or org. The preview says what
 * would go, or what holds it; the delete takes the card's or org's name, typed, as {@code confirm}.
 */
@RestController
@RequestMapping("/api/admin/orgs/{orgId}")
class AdminDeletionController {

    private final AdminDeletionService deletions;

    AdminDeletionController(AdminDeletionService deletions) {
        this.deletions = deletions;
    }

    @GetMapping("/deletion-preview")
    Plan previewOrg(@PathVariable UUID orgId) {
        return deletions.previewOrg(orgId);
    }

    @DeleteMapping
    Done deleteOrg(@PathVariable UUID orgId, @RequestParam(required = false) String confirm) {
        return deletions.deleteOrg(orgId, confirm);
    }

    @GetMapping("/cards/{cardId}/deletion-preview")
    Plan previewCard(@PathVariable UUID orgId, @PathVariable UUID cardId) {
        return deletions.previewCard(orgId, cardId);
    }

    @DeleteMapping("/cards/{cardId}")
    Done deleteCard(@PathVariable UUID orgId, @PathVariable UUID cardId, @RequestParam(required = false) String confirm) {
        return deletions.deleteCard(orgId, cardId, confirm);
    }
}
