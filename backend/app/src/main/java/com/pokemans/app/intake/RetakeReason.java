package com.pokemans.app.intake;

/** Why a photo has to be retaken. The capture UI shows {@link #message()}. */
enum RetakeReason {
    UNREADABLE("The file isn't a readable JPEG or PNG image."),
    SIZE_MISMATCH("The upload didn't match the file size that was announced."),
    RESOLUTION_TOO_LOW("Too low resolution. Move closer or use the main camera."),
    CARD_NOT_FOUND("Couldn't see the whole card. Get all four corners in the frame with a little space around the card, "
            + "on a plain background that contrasts with the card's border."),
    CARD_TOO_SMALL("The card is too small in the frame. Move closer, keeping a little space around it."),
    BLURRY("Out of focus. Hold steady and tap to focus on the card."),
    GLARE("Glare on the card. Tilt it or move away from direct light."),
    DUPLICATE("This exact image was already uploaded for another card.");

    private final String message;

    RetakeReason(String message) {
        this.message = message;
    }

    String message() {
        return message;
    }
}
