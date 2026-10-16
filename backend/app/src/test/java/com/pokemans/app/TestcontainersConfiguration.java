package com.pokemans.app;

import java.net.URI;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Real Postgres and MinIO for integration tests. Test classes that import this are annotated
 * {@code @Testcontainers(disabledWithoutDocker = true)}, so they are skipped where Docker is missing
 * and always run in CI.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    public static final String BUCKET = "photos";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:18");
    }

    @Bean
    MinIOContainer minio() {
        return new MinIOContainer("minio/minio:RELEASE.2025-09-07T16-13-09Z");
    }

    @Bean
    DynamicPropertyRegistrar storageProperties(MinIOContainer minio) {
        return registry -> {
            createBucket(minio);
            registry.add("pokemans.storage.endpoint", minio::getS3URL);
            registry.add("pokemans.storage.access-key", minio::getUserName);
            registry.add("pokemans.storage.secret-key", minio::getPassword);
            registry.add("pokemans.storage.photo-bucket", () -> BUCKET);
        };
    }

    private static void createBucket(MinIOContainer minio) {
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(minio.getS3URL()))
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(minio.getUserName(), minio.getPassword())))
                .forcePathStyle(true)
                .build()) {
            if (s3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(BUCKET))) {
                s3.createBucket(r -> r.bucket(BUCKET));
            }
        }
    }
}
