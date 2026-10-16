package com.pokemans.app.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

/**
 * Local development and tests: creates the photo bucket with versioning on if it is missing.
 * Production buckets are provisioned outside the app, with this turned off.
 */
@Component
@ConditionalOnBooleanProperty("pokemans.storage.auto-create-buckets")
class BucketInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BucketInitializer.class);

    private final S3BlobStore store;
    private final StorageProperties properties;

    BucketInitializer(S3BlobStore store, StorageProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (store.ensureVersionedBucket(properties.photoBucket())) {
            log.info("Created versioned bucket {}", properties.photoBucket());
        }
    }
}
