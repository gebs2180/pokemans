package com.pokemans.app.intake;

import com.pokemans.app.intake.IntakeViews.CardView;
import com.pokemans.app.intake.IntakeViews.ContainerDetail;
import com.pokemans.app.intake.IntakeViews.ContainerSummary;
import com.pokemans.app.intake.IntakeViews.PhotoView;
import com.pokemans.app.intake.IntakeViews.UploadSlot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Org-scoped intake API. Batches live in partner orgs, collections in collector orgs; cards and
 * photos work the same in both. Membership is checked per request by OrgAccess.
 */
@RestController
@RequestMapping("/api/orgs/{orgId}")
@EnableConfigurationProperties(QualityProperties.class)
class IntakeController {

    record CreateContainer(@NotBlank @Size(max = 200) String name) {
    }

    record CreateCard(@NotBlank @Size(max = 300) String label, boolean lot, @Min(1) @Max(100_000) Integer quantity) {
        int quantityOrOne() {
            return quantity == null ? 1 : quantity;
        }
    }

    record RequestUpload(@NotNull Photo.Side side, @NotBlank String contentType, @Positive long sizeBytes) {
    }

    private final IntakeService intake;
    private final PhotoService photos;

    IntakeController(IntakeService intake, PhotoService photos) {
        this.intake = intake;
        this.photos = photos;
    }

    @PostMapping("/batches")
    @ResponseStatus(HttpStatus.CREATED)
    ContainerSummary createBatch(@PathVariable UUID orgId, @Valid @RequestBody CreateContainer request) {
        return intake.createBatch(orgId, request.name().trim());
    }

    @GetMapping("/batches")
    List<ContainerSummary> batches(@PathVariable UUID orgId) {
        return intake.listBatches(orgId);
    }

    @GetMapping("/batches/{batchId}")
    ContainerDetail batch(@PathVariable UUID orgId, @PathVariable UUID batchId) {
        return intake.batch(orgId, batchId);
    }

    @PostMapping("/batches/{batchId}/cards")
    @ResponseStatus(HttpStatus.CREATED)
    CardView addBatchCard(@PathVariable UUID orgId, @PathVariable UUID batchId, @Valid @RequestBody CreateCard request) {
        return intake.addCardToBatch(orgId, batchId, request.label().trim(), request.lot(), request.quantityOrOne());
    }

    @PostMapping("/collections")
    @ResponseStatus(HttpStatus.CREATED)
    ContainerSummary createCollection(@PathVariable UUID orgId, @Valid @RequestBody CreateContainer request) {
        return intake.createCollection(orgId, request.name().trim());
    }

    @GetMapping("/collections")
    List<ContainerSummary> collections(@PathVariable UUID orgId) {
        return intake.listCollections(orgId);
    }

    @GetMapping("/collections/{collectionId}")
    ContainerDetail collection(@PathVariable UUID orgId, @PathVariable UUID collectionId) {
        return intake.collection(orgId, collectionId);
    }

    @PostMapping("/collections/{collectionId}/cards")
    @ResponseStatus(HttpStatus.CREATED)
    CardView addCollectionCard(@PathVariable UUID orgId, @PathVariable UUID collectionId, @Valid @RequestBody CreateCard request) {
        return intake.addCardToCollection(orgId, collectionId, request.label().trim(), request.lot(), request.quantityOrOne());
    }

    @GetMapping("/cards/{cardId}")
    CardView card(@PathVariable UUID orgId, @PathVariable UUID cardId) {
        return intake.card(orgId, cardId);
    }

    /** Step 1 of an upload: get a presigned URL. PUT the file there with the returned headers. */
    @PostMapping("/cards/{cardId}/photos")
    @ResponseStatus(HttpStatus.CREATED)
    UploadSlot requestUpload(@PathVariable UUID orgId, @PathVariable UUID cardId, @Valid @RequestBody RequestUpload request) {
        return photos.requestUpload(orgId, cardId, request.side(), request.contentType(), request.sizeBytes());
    }

    /** Step 2: after the PUT succeeds, confirm to run the quality checks. */
    @PostMapping("/photos/{photoId}/confirm")
    PhotoView confirm(@PathVariable UUID orgId, @PathVariable UUID photoId) {
        return photos.confirm(orgId, photoId);
    }
}
