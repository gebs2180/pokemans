package com.pokemans.app;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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

    /** MinIO no longer publishes images to Docker Hub; Chainguard's free build is a drop-in. */
    @Bean
    MinIOContainer minio() {
        return new MinIOContainer(DockerImageName.parse("cgr.dev/chainguard/minio:latest").asCompatibleSubstituteFor("minio/minio"));
    }

    @Bean
    DynamicPropertyRegistrar storageProperties(MinIOContainer minio) {
        return registry -> {
            registry.add("pokemans.storage.endpoint", minio::getS3URL);
            registry.add("pokemans.storage.access-key", minio::getUserName);
            registry.add("pokemans.storage.secret-key", minio::getPassword);
            registry.add("pokemans.storage.photo-bucket", () -> BUCKET);
            registry.add("pokemans.storage.auto-create-buckets", () -> "true");
        };
    }
}
