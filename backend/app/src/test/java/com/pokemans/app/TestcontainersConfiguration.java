package com.pokemans.app;

import org.springframework.boot.test.context.TestConfiguration;
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

    /**
     * One pair of containers for the whole test JVM. They were beans, so every distinct Spring context started its
     * own pair (about 18 in a full run). They are not beans now: Spring must not stop them when a context closes,
     * and Ryuk removes them when the JVM exits. The properties come from the registrar below.
     */
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withCommand("postgres", "-c", "max_connections=500");

    /** MinIO no longer publishes images to Docker Hub; Chainguard's free build is a drop-in. */
    private static final MinIOContainer MINIO =
            new MinIOContainer(DockerImageName.parse("cgr.dev/chainguard/minio:latest").asCompatibleSubstituteFor("minio/minio"));

    private static synchronized void startOnce() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
        if (!MINIO.isRunning()) {
            MINIO.start();
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger DATABASES = new java.util.concurrent.atomic.AtomicInteger();

    private static String newDatabase() {
        String name = "ctx_" + DATABASES.incrementAndGet();
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                java.sql.Statement s = c.createStatement()) {
            s.execute("create database " + name);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Could not create test database " + name, e);
        }
        return name;
    }

    @Bean
    DynamicPropertyRegistrar containerProperties() {
        return registry -> {
            startOnce();
            // Each Spring context gets its own database in the shared server, so tests still start from empty tables.
            String database = newDatabase();
            registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database));
            registry.add("spring.datasource.username", POSTGRES::getUsername);
            registry.add("spring.datasource.password", POSTGRES::getPassword);
            registry.add("pokemans.storage.endpoint", MINIO::getS3URL);
            registry.add("pokemans.storage.access-key", MINIO::getUserName);
            registry.add("pokemans.storage.secret-key", MINIO::getPassword);
            registry.add("pokemans.storage.photo-bucket", () -> BUCKET);
            registry.add("pokemans.storage.auto-create-buckets", () -> "true");
        };
    }
}
