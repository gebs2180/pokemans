package com.pokemans.app.condition;

import com.pokemans.app.identity.CurrentUser;
import com.pokemans.app.intake.CardItems;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Defects a reviewer marked on a card's surface view. The admin adds, lists and removes them (admin-only by path); the
 * collector reads the marks on their own cards. Marks are shown only: they change neither the condition distribution
 * nor any price.
 */
@RestController
@RequestMapping("/api")
class DefectMarkController {

    /**
     * @param x position as a share of the surface crop's width, 0 (left) to 1
     * @param y position as a share of its height, 0 (top) to 1
     */
    record MarkRequest(@NotNull UUID photoId, @NotNull @DecimalMin("0") @DecimalMax("1") Double x,
                       @NotNull @DecimalMin("0") @DecimalMax("1") Double y, @NotNull DefectMark.Type type,
                       @NotNull DefectMark.Severity severity, @Size(max = 500) String note) {
    }

    /** @param side FRONT, BACK or DETAIL: the photo's side */
    record MarkView(UUID id, UUID photoId, String side, double x, double y, DefectMark.Type type, DefectMark.Severity severity,
                    String note, Instant createdAt) {
    }

    /** What the admin sees: who made it as well. */
    record AdminMarkView(UUID id, UUID photoId, String side, double x, double y, DefectMark.Type type, DefectMark.Severity severity,
                         String note, UUID createdBy, Instant createdAt) {
    }

    private final DefectMarkRepository marks;
    private final CardItems cardItems;
    private final CurrentUser currentUser;

    DefectMarkController(DefectMarkRepository marks, CardItems cardItems, CurrentUser currentUser) {
        this.marks = marks;
        this.cardItems = cardItems;
        this.currentUser = currentUser;
    }

    @GetMapping("/admin/orgs/{orgId}/cards/{cardId}/defect-marks")
    @Transactional(readOnly = true)
    List<AdminMarkView> adminList(@PathVariable UUID orgId, @PathVariable UUID cardId) {
        cardItems.requireAccessible(orgId, cardId);
        return marks.findByCardItemIdAndDeletedAtIsNullOrderByCreatedAtAsc(cardId).stream().map(DefectMarkController::adminView).toList();
    }

    @PostMapping("/admin/orgs/{orgId}/cards/{cardId}/defect-marks")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    AdminMarkView add(@PathVariable UUID orgId, @PathVariable UUID cardId, @Valid @RequestBody MarkRequest request) {
        CardItems.CardItemRef card = cardItems.requireAccessible(orgId, cardId);
        CardItems.PhotoRef photo = cardItems.passedPhotos(cardId).stream().filter(p -> p.id().equals(request.photoId())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found on this card"));
        String note = request.note() == null || request.note().isBlank() ? null : request.note().trim();
        DefectMark mark = new DefectMark(card.orgId(), cardId, photo.id(), photo.side(), request.x(), request.y(), request.type(),
                request.severity(), note, currentUser.id());
        return adminView(marks.save(mark));
    }

    /** Soft delete: the row stays, marked as removed by whom and when. Removing one already removed is a no-op. */
    @DeleteMapping("/admin/orgs/{orgId}/cards/{cardId}/defect-marks/{markId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void remove(@PathVariable UUID orgId, @PathVariable UUID cardId, @PathVariable UUID markId) {
        cardItems.requireAccessible(orgId, cardId);
        DefectMark mark = marks.findById(markId).filter(m -> m.getCardItemId().equals(cardId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mark not found"));
        mark.softDelete(currentUser.id());
    }

    /** The marks on a card, for its collector (any member of its org) and the admin. */
    @GetMapping("/orgs/{orgId}/cards/{cardId}/defect-marks")
    @Transactional(readOnly = true)
    List<MarkView> list(@PathVariable UUID orgId, @PathVariable UUID cardId) {
        cardItems.requireAccessible(orgId, cardId);
        return marks.findByCardItemIdAndDeletedAtIsNullOrderByCreatedAtAsc(cardId).stream().map(DefectMarkController::view).toList();
    }

    private static MarkView view(DefectMark m) {
        return new MarkView(m.getId(), m.getPhotoId(), m.getSide(), m.getX(), m.getY(), m.getType(), m.getSeverity(), m.getNote(),
                m.getCreatedAt());
    }

    private static AdminMarkView adminView(DefectMark m) {
        return new AdminMarkView(m.getId(), m.getPhotoId(), m.getSide(), m.getX(), m.getY(), m.getType(), m.getSeverity(), m.getNote(),
                m.getCreatedBy(), m.getCreatedAt());
    }
}
