package com.apimarketplace.orchestrator.services.impl;

import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Filesystem-based implementation of FileStorageService for CE monolith mode.
 * Stores files on the local filesystem instead of S3/MinIO.
 * <p>
 * Activated by: storage.type=local
 */
@Service
@ConditionalOnProperty(name = "storage.type", havingValue = "local")
public class LocalFileStorageService implements FileStorageService {

    private static final Logger logger = LoggerFactory.getLogger(LocalFileStorageService.class);

    private final Path basePath;

    /** Indexer for the {@code storage.storage} table - populated so the
     *  Files panel / Storage Explorer surfaces every {@code upload()}
     *  call in CE monolith deployments (where this class is active).
     *  Without this every local upload was invisible to the UI. */
    @Autowired(required = false)
    private StorageService storageIndexService;

    public LocalFileStorageService(@Value("${storage.local.base-path:./data/files}") String basePath) {
        this.basePath = Path.of(basePath);
        try {
            Files.createDirectories(this.basePath);
            logger.info("LocalFileStorageService initialized at: {}", this.basePath.toAbsolutePath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create local storage directory: " + basePath, e);
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
        String key = buildKey(tenantId, workflowId, runId, stepAlias, fileName);
        Path filePath = resolveWithinBase(key);

        try {
            Files.createDirectories(filePath.getParent());
            Files.copy(content, filePath, StandardCopyOption.REPLACE_EXISTING);
            long actualSize = Files.size(filePath);
            logger.debug("Stored file: key={}, size={} bytes", key, actualSize);
            indexUpload(tenantId, workflowId, runId, stepAlias, key, fileName, mimeType, actualSize,
                    epoch, spawn, itemIndex, sourceType);
            return FileRef.of(key, fileName, mimeType, actualSize);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write file: " + key, e);
        }
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
        String key = buildKey(tenantId, workflowId, runId, stepAlias, fileName);
        Path filePath = resolveWithinBase(key);

        try {
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, content);
            logger.debug("Stored file: key={}, size={} bytes", key, content.length);
            indexUpload(tenantId, workflowId, runId, stepAlias, key, fileName, mimeType, content.length,
                    epoch, spawn, itemIndex, sourceType);
            return FileRef.of(key, fileName, mimeType, content.length);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write file: " + key, e);
        }
    }

    /**
     * Mirror of {@code S3FileStorageService.indexWorkflowUpload}. Without this
     * call CE monolith deployments (storage.type=local) never populate
     * {@code storage.storage}, breaking the Files panel entirely. Failure is
     * logged + swallowed - the upload itself already succeeded.
     */
    private void indexUpload(String tenantId, String workflowId, String runId, String stepAlias,
                              String key, String fileName, String mimeType, long size,
                              int epoch, int spawn, Integer itemIndex, String sourceType) {
        if (storageIndexService == null) return;
        try {
            storageIndexService.saveS3FileIndex(
                    tenantId, workflowId, runId, stepAlias,
                    key, fileName, mimeType, size, epoch, spawn, itemIndex, sourceType);
        } catch (Exception e) {
            logger.warn("Failed to index local upload in storage.storage: tenant={}, key={}, error={}",
                    tenantId, key, e.getMessage());
        }
    }

    @Override
    public String generateDownloadUrl(String key, Duration duration) {
        // In CE mode, files are served directly by the application via FileDownloadController.
        // The token parameter provides anti-hotlink protection.
        return "/api/files/download?key=" + key;
    }

    @Override
    public Optional<byte[]> download(String key) {
        Path filePath = resolveWithinBase(key);
        if (!Files.exists(filePath)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(filePath));
        } catch (IOException e) {
            logger.error("Failed to read file: {}", key, e);
            return Optional.empty();
        }
    }

    @Override
    public boolean delete(String key) {
        Path filePath = resolveWithinBase(key);
        try {
            boolean deleted = Files.deleteIfExists(filePath);
            if (deleted) {
                logger.debug("Deleted file: {}", key);
            }
            return deleted;
        } catch (IOException e) {
            logger.error("Failed to delete file: {}", key, e);
            return false;
        }
    }

    @Override
    public int deleteRunFiles(String tenantId, String workflowId, String runId) {
        Path runDir = resolveWithinBase(sanitizeSegment(tenantId) + "/" + sanitizeSegment(workflowId)
                + "/" + sanitizeSegment(runId));
        if (!Files.exists(runDir)) {
            return 0;
        }

        int[] count = {0};
        try (Stream<Path> walk = Files.walk(runDir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        // Decide file-vs-directory BEFORE deleting: afterwards the path no longer
                        // exists, isDirectory() is false for everything, and directories were
                        // counted as deleted files.
                        boolean isFile = !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
                        if (Files.deleteIfExists(path) && isFile) count[0]++;
                    } catch (IOException e) {
                        logger.warn("Failed to delete: {}", path, e);
                    }
                });
        } catch (IOException e) {
            logger.error("Failed to walk directory: {}", runDir, e);
        }

        logger.info("Deleted {} files for run: {}/{}/{}", count[0], tenantId, workflowId, runId);
        return count[0];
    }

    @Override
    public boolean exists(String key) {
        return Files.exists(resolveWithinBase(key));
    }

    /**
     * Builds the storage key for an upload.
     *
     * <p>Every segment is sanitized, not only the file name: {@code stepAlias} is an
     * author-chosen label and {@code workflowId}/{@code runId} are plain strings on this
     * signature, so any of them could carry a separator. The S3 sibling
     * ({@code S3FileStorageService.sanitizeFileName}) already did this for the file name;
     * this implementation interpolated it raw, and {@link Path#resolve} collapses a parent
     * reference, so an uploaded file whose name walked up the tree was written anywhere the
     * service account could write whenever {@code storage.type=local} (LC-062).
     */
    private String buildKey(String tenantId, String workflowId, String runId, String stepAlias, String fileName) {
        String uniquePrefix = UUID.randomUUID().toString().substring(0, 8);
        return String.format("%s/%s/%s/%s/%s_%s",
            sanitizeSegment(tenantId), sanitizeSegment(workflowId), sanitizeSegment(runId),
            sanitizeSegment(stepAlias), uniquePrefix, sanitizeSegment(fileName));
    }

    /**
     * Reduces one key segment to a name that cannot escape its parent directory: separators and
     * non-printable characters become {@code _}, and a segment made only of dots (the traversal
     * primitive) is replaced outright. Mirrors {@code S3FileStorageService.sanitizeFileName},
     * extended with the dots-only case, which matters here because these segments become real
     * directory names on a filesystem rather than opaque object-key text.
     */
    static String sanitizeSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            return "unnamed";
        }
        if (segment.chars().allMatch(c -> c == '.')) {
            return "unnamed";
        }
        String name = segment.replaceAll("[/\\\\]", "_");
        name = name.replaceAll("[^\\p{Print}]", "_");
        // Collapse any remaining parent reference. Flattening the separators alone leaves
        // "..", which no longer traverses but is still an illegal name on Windows (a segment
        // may not end in a dot), so an otherwise-harmless upload failed to write at all.
        while (name.contains("..")) {
            name = name.replace("..", "_");
        }
        name = name.replaceAll("^[.\\s]+|[.\\s]+$", "");
        if (name.isEmpty()) {
            return "unnamed";
        }
        if (name.length() > 200) {
            name = name.substring(0, 200);
        }
        return name;
    }

    /**
     * Resolves a key under {@link #basePath} and PROVES the result is still inside it.
     *
     * <p>Sanitizing {@link #buildKey} closes the write path this service creates, but every
     * read/delete/exists entry point takes a key from a caller (a stored row, an API parameter),
     * so containment is asserted here rather than trusted upstream. {@code normalize()} collapses
     * a parent reference before the check, so a key that walks out fails loud instead of touching
     * an unrelated file (LC-062).
     *
     * <p>The lexical check alone does not see a symbolic link: {@code root/a/link} normalizes to a
     * path under the root while the link points anywhere. So the deepest part of the path that
     * already EXISTS is resolved with {@code toRealPath()} and must still sit under the root's real
     * path; a dangling link (which {@code toRealPath()} cannot resolve) is refused too. A key that
     * is not a valid path on this filesystem (NUL byte, reserved characters) is refused cleanly
     * instead of surfacing an {@code InvalidPathException}.
     */
    private Path resolveWithinBase(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Storage key is blank");
        }
        Path root = basePath.toAbsolutePath().normalize();
        Path resolved;
        try {
            resolved = root.resolve(key).normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Storage key is not a valid path: " + key, e);
        }
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException("Storage key escapes the storage root: " + key);
        }
        assertNoLinkEscape(root, resolved, key);
        return resolved;
    }

    private static void assertNoLinkEscape(Path root, Path resolved, String key) {
        Path existing = resolved;
        while (existing != null && existing.startsWith(root)
                && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null || !existing.startsWith(root)) {
            return; // nothing under the root exists: the lexical check above already decided
        }
        try {
            Path realRoot = root.toRealPath();
            if (!existing.toRealPath().startsWith(realRoot)) {
                throw new IllegalArgumentException(
                        "Storage key escapes the storage root through a link: " + key);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Storage key cannot be resolved safely: " + key, e);
        }
    }
}
