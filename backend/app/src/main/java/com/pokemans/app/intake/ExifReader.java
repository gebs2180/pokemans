package com.pokemans.app.intake;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.TimeZone;

/** Reads when a photo was taken (EXIF DateTimeOriginal). EXIF has no zone, so it is read as UTC. */
final class ExifReader {

    private ExifReader() {
    }

    static Instant capturedAt(byte[] imageBytes) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(imageBytes));
            ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
            if (exif == null) {
                return null;
            }
            Date date = exif.getDateOriginal(TimeZone.getTimeZone("UTC"));
            return date == null ? null : date.toInstant();
        } catch (ImageProcessingException | IOException e) {
            return null;
        }
    }
}
