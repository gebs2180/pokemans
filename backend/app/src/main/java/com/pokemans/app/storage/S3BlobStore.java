package com.pokemans.app.storage;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;

/** {@link BlobStore} over any S3-compatible service. */
@Component
@EnableConfigurationProperties(StorageProperties.class)
class S3BlobStore implements BlobStore, AutoCloseable {

    /** Headers the browser sets itself and must not (or cannot) send explicitly. */
    private static final List<String> IMPLICIT_HEADERS = List.of("host", "content-length");

    private final StorageProperties properties;
    private final S3Client client;
    private final S3Presigner presigner;

    S3BlobStore(StorageProperties properties) {
        this.properties = properties;
        AwsCredentialsProvider credentials = properties.accessKey() == null || properties.accessKey().isBlank()
                ? DefaultCredentialsProvider.builder().build()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
        S3Configuration s3 = S3Configuration.builder()
                .pathStyleAccessEnabled(properties.pathStyle())
                .chunkedEncodingEnabled(properties.chunkedEncoding())
                .build();
        Region region = Region.of(properties.region());

        var clientBuilder = S3Client.builder().region(region).credentialsProvider(credentials).serviceConfiguration(s3);
        var presignerBuilder = S3Presigner.builder().region(region).credentialsProvider(credentials).serviceConfiguration(s3);
        if (properties.endpoint() != null) {
            clientBuilder.endpointOverride(properties.endpoint());
        }
        URI browserEndpoint = properties.publicEndpoint() != null ? properties.publicEndpoint() : properties.endpoint();
        if (browserEndpoint != null) {
            presignerBuilder.endpointOverride(browserEndpoint);
        }
        this.client = clientBuilder.build();
        this.presigner = presignerBuilder.build();
    }

    @Override
    public PresignedUpload presignUpload(String bucket, String key, String contentType, long contentLength) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(contentLength)
                .ifNoneMatch("*")
                .build();
        PresignedPutObjectRequest presigned = presigner.presignPutObject(r -> r
                .signatureDuration(properties.uploadUrlTtl())
                .putObjectRequest(put));

        Map<String, String> headers = new LinkedHashMap<>();
        presigned.signedHeaders().forEach((name, values) -> {
            if (!IMPLICIT_HEADERS.contains(name.toLowerCase())) {
                headers.put(name, String.join(",", values));
            }
        });
        return new PresignedUpload(URI.create(presigned.url().toString()), presigned.httpRequest().method().name(),
                headers, presigned.expiration());
    }

    @Override
    public Optional<StoredObject> head(String bucket, String key) {
        try {
            HeadObjectResponse head = client.headObject(r -> r.bucket(bucket).key(key));
            return Optional.of(new StoredObject(head.contentLength(), head.contentType()));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public byte[] read(String bucket, String key) {
        return client.getObjectAsBytes(r -> r.bucket(bucket).key(key)).asByteArray();
    }

    @Override
    public boolean putIfAbsent(String bucket, String key, byte[] bytes, String contentType) {
        try {
            client.putObject(r -> r.bucket(bucket).key(key).contentType(contentType).ifNoneMatch("*"), RequestBody.fromBytes(bytes));
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 412) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public URI presignDownload(String bucket, String key) {
        return URI.create(presigner.presignGetObject(r -> r
                .signatureDuration(properties.downloadUrlTtl())
                .getObjectRequest(g -> g.bucket(bucket).key(key))).url().toString());
    }

    /** Creates the bucket with versioning on if it does not exist. Returns true if it was created. */
    boolean ensureVersionedBucket(String bucket) {
        try {
            client.headBucket(r -> r.bucket(bucket));
            return false;
        } catch (S3Exception e) {
            if (!(e instanceof NoSuchBucketException) && e.statusCode() != 404) {
                throw e;
            }
            client.createBucket(r -> r.bucket(bucket));
            client.putBucketVersioning(r -> r.bucket(bucket)
                    .versioningConfiguration(v -> v.status(BucketVersioningStatus.ENABLED)));
            return true;
        }
    }

    @Override
    public void close() {
        presigner.close();
        client.close();
    }
}
