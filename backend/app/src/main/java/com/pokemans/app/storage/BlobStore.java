package com.pokemans.app.storage;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Object storage port (HLD: {@code BlobStore}). Objects are written once and never replaced. */
public interface BlobStore {

    /**
     * A presigned PUT the client uses to upload directly to storage. The signature binds the content
     * type and exact length, and {@code If-None-Match: *} so an existing object cannot be overwritten.
     */
    PresignedUpload presignUpload(String bucket, String key, String contentType, long contentLength);

    /** Size and type of a stored object, or empty if nothing has been uploaded. */
    Optional<StoredObject> head(String bucket, String key);

    byte[] read(String bucket, String key);

    record PresignedUpload(URI url, String method, Map<String, String> headers, Instant expiresAt) {
    }

    record StoredObject(long size, String contentType) {
    }
}
