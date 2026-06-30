package com.apimarketplace.storage.service.file;

import com.apimarketplace.common.storage.domain.QuotaStatus;
import com.apimarketplace.common.storage.exception.QuotaExceededException;
import com.apimarketplace.common.storage.service.api.QuotaOperations;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.storage.domain.FileRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * S3-compatible file storage service.
 * Works with MinIO (development) and AWS S3 / Cloudflare R2 (production).
 *
 * File organization: {bucket}/{tenantId}/{workflowId}/{runId}/{stepAlias}/{filename}
 * Generic uploads:   {bucket}/{tenantId}/general/{category}/{uuid}_{filename}
 */
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "s3", matchIfMissing = true)
public class S3FileStorageService implements FileStorageService {

    private static final Logger logger = LoggerFactory.getLogger(S3FileStorageService.class);

    @Value("${storage.s3.endpoint:http://localhost:9000}")
    private String endpoint;

    @Value("${storage.s3.region:us-east-1}")
    private String region;

    @Value("${storage.s3.access-key:minioadmin}")
    private String accessKey;

    @Value("${storage.s3.secret-key:minioadmin}")
    private String secretKey;

    @Value("${storage.s3.bucket:workflow-files}")
    private String bucket;

    @Value("${storage.s3.path-style-access:true}")
    private boolean pathStyleAccess;

    /** Apache HTTP client max concurrent connections to S3.
     *  Streaming downloads (openStream) hold a connection for the entire
     *  client → S3 transfer (potentially seconds on slow clients). Bumping
     *  from the SDK default of 50 to 100 prevents pool starvation when many
     *  slow clients stream concurrently. Tunable via storage.s3.max-connections. */
    @Value("${storage.s3.max-connections:100}")
    private int maxConnections;

    /** How long to wait for a free connection before failing fast.
     *  SDK default is 10s. Streaming hold-times push us past 10s easily;
     *  30s gives a sensible queue without locking up Tomcat threads. */
    @Value("${storage.s3.connection-acquisition-timeout-seconds:30}")
    private int connectionAcquisitionTimeoutSeconds;

    /** Indexer for the {@code storage.storage} table - populated so the
     *  Files tab + Storage Explorer see every {@code uploadGeneric} call.
     *  Optional because some tests don't wire common-storage-service. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.common.storage.service.StorageService storageIndexService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private QuotaOperations quotaService;

    private S3Client s3Client;
    private S3Presigner s3Presigner;

    @PostConstruct
    public void init() {
        logger.info("Initializing S3 file storage: endpoint={}, bucket={}", endpoint, bucket);

        var credentials = StaticCredentialsProvider.create(
            AwsBasicCredentials.create(accessKey, secretKey)
        );

        // Apache HTTP client tuned for streaming downloads: each openStream
        // call holds a connection for the duration of the client transfer
        // (proportional to client bandwidth, not just S3 fetch time). The
        // SDK default 50 / 10s is too tight under streaming hold-times.
        ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder()
            .maxConnections(maxConnections)
            .connectionAcquisitionTimeout(Duration.ofSeconds(connectionAcquisitionTimeoutSeconds));

        var builder = S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .endpointOverride(URI.create(endpoint))
            .forcePathStyle(pathStyleAccess)
            .httpClientBuilder(httpClientBuilder);

        this.s3Client = builder.build();
        logger.info("S3 HTTP client: maxConnections={}, connectionAcquisitionTimeout={}s",
            maxConnections, connectionAcquisitionTimeoutSeconds);

        this.s3Presigner = S3Presigner.builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .endpointOverride(URI.create(endpoint))
            .build();

        ensureBucketExists();
        logger.info("S3 file storage initialized successfully");
    }

    private void ensureBucketExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            logger.debug("Bucket '{}' exists", bucket);
        } catch (NoSuchBucketException e) {
            logger.info("Creating bucket '{}'", bucket);
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (Exception e) {
            logger.warn("Could not verify bucket existence: {}", e.getMessage());
        }
    }

    @Override
    public FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                          String fileName, String mimeType, InputStream content, long size) {
        return upload(tenantId, workflowId, runId, stepAlias, fileName, mimeType, content, size,
                /* epoch */ 0, /* spawn */ 0, /* itemIndex */ null,
                com.apimarketplace.common.storage.service.StorageSourceTypes.S3_FILE);
    }

    @Override
    public FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                          String fileName, String mimeType, InputStream content, long size,
                          int epoch, int spawn, Integer itemIndex, String sourceType) {
        validateQuota(tenantId, size);
        String key = buildKey(tenantId, workflowId, runId, stepAlias, fileName);
        FileRef ref = doUpload(key, fileName, mimeType, RequestBody.fromInputStream(content, size), size);
        UUID id = indexWorkflowUpload(tenantId, workflowId, runId, stepAlias, key, fileName, mimeType, size,
                epoch, spawn, itemIndex, sourceType);
        return FileRef.of(ref.path(), ref.name(), ref.mimeType(), ref.size(), id != null ? id.toString() : null);
    }

    @Override
    public FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                          String fileName, String mimeType, byte[] content) {
        return upload(tenantId, workflowId, runId, stepAlias, fileName, mimeType, content,
                /* epoch */ 0, /* spawn */ 0, /* itemIndex */ null,
                com.apimarketplace.common.storage.service.StorageSourceTypes.S3_FILE);
    }

    @Override
    public FileRef upload(String tenantId, String workflowId, String runId, String stepAlias,
                          String fileName, String mimeType, byte[] content,
                          int epoch, int spawn, Integer itemIndex, String sourceType) {
        validateQuota(tenantId, content != null ? content.length : 0L);
        String key = buildKey(tenantId, workflowId, runId, stepAlias, fileName);
        FileRef ref = doUpload(key, fileName, mimeType, RequestBody.fromBytes(content), content.length);
        UUID id = indexWorkflowUpload(tenantId, workflowId, runId, stepAlias, key, fileName, mimeType, content.length,
                epoch, spawn, itemIndex, sourceType);
        return FileRef.of(ref.path(), ref.name(), ref.mimeType(), ref.size(), id != null ? id.toString() : null);
    }

    @Override
    public FileRef uploadGeneric(String tenantId, String category, String fileName, String mimeType,
                                 InputStream content, long size) {
        validateQuota(tenantId, size);
        String key = buildGenericKey(tenantId, category, fileName);
        FileRef ref = doUpload(key, fileName, mimeType, RequestBody.fromInputStream(content, size), size);
        UUID id = indexGenericUpload(tenantId, category, key, fileName, mimeType, size);
        return FileRef.of(ref.path(), ref.name(), ref.mimeType(), ref.size(), id != null ? id.toString() : null);
    }

    /** Folder-aware generic upload (V313): stamps {@code parent_folder_id} so the file lands
     *  in the user's current manual folder. {@code parentFolderId} null = root. */
    @Override
    public FileRef uploadGeneric(String tenantId, String category, String fileName, String mimeType,
                                 InputStream content, long size, UUID parentFolderId) {
        validateQuota(tenantId, size);
        String key = buildGenericKey(tenantId, category, fileName);
        FileRef ref = doUpload(key, fileName, mimeType, RequestBody.fromInputStream(content, size), size);
        UUID id = indexGenericUpload(tenantId, category, key, fileName, mimeType, size, parentFolderId);
        return FileRef.of(ref.path(), ref.name(), ref.mimeType(), ref.size(), id != null ? id.toString() : null);
    }

    /**
     * Register a workflow-scoped upload in {@code storage.storage} (mirror of
     * {@link #indexGenericUpload}). Pre-v3.1 only {@code uploadGeneric()}
     * indexed; the workflow path relied on {@code StorageClientAdapter} to
     * call the indexer separately. That worked for the orchestrator → storage
     * HTTP path, but bypassed indexing for any in-process call to
     * {@link #upload} (notably {@code FileController.upload} = the public
     * {@code POST /api/files/upload} REST endpoint, used by Form/Chat/Inspector
     * uploads). Symmetry now eliminates the footgun: every successful S3
     * write produces a {@code storage.storage} row regardless of caller.
     */
    UUID indexWorkflowUpload(String tenantId, String workflowId, String runId, String stepAlias,
                              String key, String fileName, String mimeType, long size,
                              int epoch, int spawn, Integer itemIndex, String sourceType) {
        if (storageIndexService == null) {
            return null; // common-storage-service not on classpath (tests).
        }
        try {
            logger.info("indexWorkflowUpload: tenant={} workflow={} run={} step={} key={} size={} "
                    + "epoch={} spawn={} itemIndex={} sourceType={}",
                    tenantId, workflowId, runId, stepAlias, key, size, epoch, spawn, itemIndex, sourceType);
            return storageIndexService.saveS3FileIndex(
                    tenantId, workflowId, runId, stepAlias,
                    key, fileName, mimeType, size,
                    epoch, spawn, itemIndex, sourceType);
        } catch (QuotaExceededException e) {
            delete(key);
            throw e;
        } catch (Exception e) {
            logger.warn("Failed to index workflow upload in storage.storage: tenant={}, key={}, error={}",
                    tenantId, key, e.getMessage());
            return null;
        }
    }

    /**
     * Register the generic upload in {@code storage.storage} so the Files
     * tab / Storage Explorer can list it. Without this every catalog-tool
     * binary output (Gemini image, OpenAI image, ElevenLabs MP3, scraped
     * PDF, …) lands in MinIO invisibly to the UI.
     *
     * <p>Failure is logged and swallowed - the upload itself already
     * succeeded; failing the call here would leak an orphan S3 object
     * and lose the agent-visible FileRef. The cleanup sweep
     * ({@link CatalogBinaryCleanupJob}) is the safety net for any
     * occasional indexing miss.
     *
     * <p>Package-private for testability - {@code S3FileStorageServiceTest}
     * verifies the indexing contract (tenantId/key/fileName/mimeType/size
     * forwarded verbatim) without spinning up an S3 client.
     */
    UUID indexGenericUpload(String tenantId, String category, String key,
                                     String fileName, String mimeType, long size) {
        if (storageIndexService == null) {
            // common-storage-service not on classpath (test profiles, …) - no-op.
            return null;
        }
        try {
            logger.info("indexGenericUpload: tenant={} category={} key={} fileName={} mimeType={} size={}",
                    tenantId, category, key, fileName, mimeType, size);
            return storageIndexService.saveS3FileIndex(
                    tenantId,
                    /* workflowId */ null,
                    /* runId      */ null,
                    /* stepKey    */ null,
                    key, fileName, mimeType, size,
                    /* epoch      */ 0);
        } catch (QuotaExceededException e) {
            delete(key);
            throw e;
        } catch (Exception e) {
            logger.warn("Failed to index generic upload in storage.storage: tenant={}, category={}, key={}, error={}",
                    tenantId, category, key, e.getMessage());
            return null;
        }
    }

    /** Folder-aware index (V313): forwards {@code parentFolderId} so the generic upload row lands
     *  in the user's current folder. The common indexer validates the folder + drops to root on miss. */
    UUID indexGenericUpload(String tenantId, String category, String key,
                            String fileName, String mimeType, long size, UUID parentFolderId) {
        if (storageIndexService == null) {
            return null;
        }
        try {
            logger.info("indexGenericUpload(folder): tenant={} category={} key={} fileName={} size={} parentFolderId={}",
                    tenantId, category, key, fileName, size, parentFolderId);
            return storageIndexService.saveS3FileIndex(
                    tenantId, /* workflowId */ null, /* runId */ null, /* stepKey */ null,
                    key, fileName, mimeType, size,
                    /* epoch */ 0, /* spawn */ 0, /* itemIndex */ null, /* sourceType */ null,
                    parentFolderId);
        } catch (QuotaExceededException e) {
            delete(key);
            throw e;
        } catch (Exception e) {
            logger.warn("Failed to index generic upload (folder) in storage.storage: tenant={}, category={}, key={}, error={}",
                    tenantId, category, key, e.getMessage());
            return null;
        }
    }

    void validateQuota(String tenantId, long size) {
        if (quotaService == null) {
            return;
        }
        // System tenants (e.g. "_publications") are not real users - skip quota enforcement
        if (tenantId != null && tenantId.startsWith("_")) {
            return;
        }
        String organizationId = TenantResolver.currentRequestOrganizationId();
        long additionalBytes = Math.max(0L, size);
        QuotaStatus status = organizationId != null && !organizationId.isBlank()
                ? quotaService.checkOrganizationQuota(organizationId, additionalBytes)
                : quotaService.checkQuota(tenantId, additionalBytes);
        if (status == QuotaStatus.HARD_LIMIT_REACHED) {
            throw new QuotaExceededException("Storage quota hard limit reached", tenantId);
        }
    }

    private FileRef doUpload(String key, String fileName, String mimeType, RequestBody body, long size) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(mimeType)
                .contentLength(size)
                .build();

            s3Client.putObject(request, body);

            logger.info("File uploaded: key={}, size={} bytes", key, size);
            return FileRef.of(key, fileName, mimeType, size);

        } catch (Exception e) {
            logger.error("Failed to upload file: key={}", key, e);
            throw new RuntimeException("Failed to upload file: " + fileName, e);
        }
    }

    @Override
    public String generateDownloadUrl(String key, Duration duration) {
        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(duration)
                .getObjectRequest(getRequest)
                .build();

            String url = s3Presigner.presignGetObject(presignRequest).url().toString();
            logger.debug("Generated presigned URL for key={}, expires in {}", key, duration);
            return url;

        } catch (Exception e) {
            logger.error("Failed to generate presigned URL: key={}", key, e);
            throw new RuntimeException("Failed to generate download URL", e);
        }
    }

    @Override
    public Optional<byte[]> download(String key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

            byte[] content = s3Client.getObjectAsBytes(request).asByteArray();
            logger.debug("File downloaded: key={}, size={} bytes", key, content.length);
            return Optional.of(content);

        } catch (NoSuchKeyException e) {
            logger.warn("File not found: key={}", key);
            return Optional.empty();
        } catch (Exception e) {
            logger.error("Failed to download file: key={}", key, e);
            throw new RuntimeException("Failed to download file", e);
        }
    }

    @Override
    public Optional<DownloadStream> openStream(String key) {
        software.amazon.awssdk.core.ResponseInputStream<GetObjectResponse> stream = null;
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

            // s3Client.getObject returns a ResponseInputStream<GetObjectResponse>
            // that streams directly from S3 - no byte[] materialised. The caller
            // is responsible for closing it (try-with-resources via the
            // DownloadStream record), which releases the SDK's underlying
            // Apache HTTP client connection.
            stream = s3Client.getObject(request);
            GetObjectResponse meta = stream.response();
            long contentLength = meta.contentLength() != null ? meta.contentLength() : -1L;
            String contentType = meta.contentType();
            logger.info("File stream opened: key={}, contentLength={}, contentType={}",
                key, contentLength, contentType);
            DownloadStream ds = new DownloadStream(stream, contentLength, contentType);
            // ds now owns the stream; null it out so the catch below does not
            // double-close it on a subsequent failure path (defensive - there
            // is no statement between this line and the return that throws,
            // but the assignment makes the intent explicit and protects future
            // edits to this method).
            stream = null;
            return Optional.of(ds);

        } catch (NoSuchKeyException e) {
            logger.warn("File not found: key={}", key);
            return Optional.empty();
        } catch (Throwable t) {
            // BLOCKER fix from audit round 1 (auditor C): if anything throws
            // between getObject() returning and the DownloadStream record
            // being handed to the caller (e.g. OOM constructing the record,
            // SDK metadata accessor blowing up), the open ResponseInputStream
            // would leak its Apache HTTP connection. Close it eagerly here.
            if (stream != null) {
                try { stream.close(); } catch (Exception closeErr) {
                    logger.warn("Failed to close S3 stream after openStream error: key={}", key, closeErr);
                }
            }
            logger.error("Failed to open stream: key={}", key, t);
            if (t instanceof RuntimeException re) throw re;
            if (t instanceof Error err) throw err;
            throw new RuntimeException("Failed to open file stream", t);
        }
    }

    @Override
    public boolean delete(String key) {
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

            s3Client.deleteObject(request);
            logger.info("File deleted: key={}", key);
            return true;

        } catch (Exception e) {
            logger.error("Failed to delete file: key={}", key, e);
            return false;
        }
    }

    @Override
    public int deleteRunFiles(String tenantId, String workflowId, String runId) {
        String prefix = String.format("%s/%s/%s/", tenantId, workflowId, runId);

        try {
            ListObjectsV2Request listRequest = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(prefix)
                .build();

            ListObjectsV2Response listResponse = s3Client.listObjectsV2(listRequest);
            List<S3Object> objects = listResponse.contents();

            if (objects.isEmpty()) {
                logger.debug("No files found for prefix: {}", prefix);
                return 0;
            }

            List<ObjectIdentifier> toDelete = objects.stream()
                .map(obj -> ObjectIdentifier.builder().key(obj.key()).build())
                .toList();

            DeleteObjectsRequest deleteRequest = DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(Delete.builder().objects(toDelete).build())
                .build();

            s3Client.deleteObjects(deleteRequest);
            logger.info("Deleted {} files for run: {}/{}/{}", objects.size(), tenantId, workflowId, runId);
            return objects.size();

        } catch (Exception e) {
            logger.error("Failed to delete run files: prefix={}", prefix, e);
            throw new RuntimeException("Failed to delete run files", e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

            s3Client.headObject(request);
            return true;

        } catch (NoSuchKeyException e) {
            return false;
        } catch (Exception e) {
            logger.error("Failed to check file existence: key={}", key, e);
            return false;
        }
    }

    private String buildKey(String tenantId, String workflowId, String runId, String stepAlias, String fileName) {
        String safeFileName = sanitizeFileName(fileName);
        String uniquePrefix = UUID.randomUUID().toString().substring(0, 8);
        return String.format("%s/%s/%s/%s/%s_%s",
            tenantId, workflowId, runId, stepAlias, uniquePrefix, safeFileName);
    }

    private String buildGenericKey(String tenantId, String category, String fileName) {
        String safeFileName = sanitizeFileName(fileName);
        String uniquePrefix = UUID.randomUUID().toString().substring(0, 8);
        return String.format("%s/general/%s/%s_%s",
            tenantId, category, uniquePrefix, safeFileName);
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "unnamed";
        }
        String name = fileName.replaceAll("[/\\\\]", "_");
        name = name.replaceAll("[^\\p{Print}]", "_");
        if (name.length() > 200) {
            name = name.substring(0, 200);
        }
        return name;
    }
}
