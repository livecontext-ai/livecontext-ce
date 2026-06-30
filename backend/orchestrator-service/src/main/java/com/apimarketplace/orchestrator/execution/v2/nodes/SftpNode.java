package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import com.apimarketplace.orchestrator.domain.file.FileRef;
import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.apimarketplace.orchestrator.utils.file.FileNameExtractor;
import com.apimarketplace.orchestrator.utils.file.MimeTypeRegistry;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * SFTP node - File operations on remote servers via SFTP.
 *
 * Connection credentials (host, port, username, password/privateKey) are loaded
 * from the credential system (Settings > Credentials > SFTP) when a credentialId
 * is configured. Falls back to inline config fields for backward compatibility.
 *
 * Supports operations: upload, download, list, delete, rename, mkdir.
 *
 * SECURITY: Password and private key values are NEVER logged.
 */
public class SftpNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(SftpNode.class);
    private static final String SFTP_INTEGRATION = "sftp";
    private static final int DEFAULT_TIMEOUT = 30000;
    private static final long MAX_DOWNLOAD_SIZE = 50L * 1024 * 1024; // 50 MB cap

    private final Core.SftpConfig config;
    private CredentialClient credentialClient;
    private FileStorageService fileStorageService;
    private MimeTypeRegistry mimeTypeRegistry;

    public SftpNode(String nodeId, Core.SftpConfig config) {
        super(nodeId, NodeType.SFTP);
        this.config = config;
    }

    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.credentialClient = registry.getCredentialClient();
        this.fileStorageService = registry.getFileStorageService();
        this.mimeTypeRegistry = registry.getMimeTypeRegistry();
    }

    // Test seams - kept package-private to keep the surface minimal.
    void setFileStorageService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    void setMimeTypeRegistry(MimeTypeRegistry mimeTypeRegistry) {
        this.mimeTypeRegistry = mimeTypeRegistry;
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        long startTime = System.currentTimeMillis();

        if (config == null) {
            return NodeExecutionResult.failureWithOutput(nodeId,
                "SFTP configuration is required.",
                Map.of("node_type", "SFTP", "resolved_params", Map.of()),
                System.currentTimeMillis() - startTime);
        }

        // Resolve connection from credential system or inline config
        String host, username, password, privateKey, authMethod;
        int port;

        Long credentialId = config.credentialId();
        if (credentialId != null && credentialClient != null) {
            Optional<CredentialSummaryDto> cred = credentialClient.getCredentialById(context.tenantId(), credentialId);
            if (cred.isEmpty()) {
                logger.warn("SFTP credential {} not found, falling back to default", credentialId);
                cred = credentialClient.getDefaultCredential(context.tenantId(), SFTP_INTEGRATION);
            }
            if (cred.isEmpty()) {
                return NodeExecutionResult.failureWithOutput(nodeId,
                    "SFTP credential not found. Configure an SFTP credential and set it on this node before running.",
                    Map.of("node_type", "SFTP", "resolved_params", Map.of()),
                    System.currentTimeMillis() - startTime);
            }
            Map<String, Object> data = cred.get().getCredentialData();
            host = getString(data, "host");
            port = getInt(data, "port", 22);
            username = getString(data, "username");
            authMethod = getString(data, "auth_method");
            password = getString(data, "password");
            privateKey = getString(data, "private_key");
        } else {
            // Fallback: inline config (backward compatibility)
            host = resolveTemplateString(config.host(), context);
            port = config.port();
            username = resolveTemplateString(config.username(), context);
            authMethod = config.authMethod();
            password = resolveTemplateString(config.password(), context);
            privateKey = resolveTemplateString(config.privateKey(), context);
        }

        // Operation-specific fields always come from config
        String remotePath = resolveTemplateString(config.remotePath(), context);
        String localContent = resolveTemplateString(config.localContent(), context);
        String newPath = resolveTemplateString(config.newPath(), context);
        int timeout = config.timeout() != null ? config.timeout() : DEFAULT_TIMEOUT;
        String operation = config.operation();
        if (authMethod == null) authMethod = "password";

        // Snapshot resolved configuration for the inspector "Resolved parameters" panel.
        // Built once, used in every exit path. Password / private key never leak in here.
        // localContent length is summarised rather than dumped - file payloads can be huge.
        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        resolvedParams.put("host", host);
        resolvedParams.put("port", port);
        resolvedParams.put("username", username);
        resolvedParams.put("authMethod", authMethod);
        resolvedParams.put("operation", operation);
        resolvedParams.put("remotePath", remotePath);
        if (newPath != null && !newPath.isBlank()) resolvedParams.put("newPath", newPath);
        if (localContent != null) resolvedParams.put("localContentSize", localContent.length());
        resolvedParams.put("timeout", timeout);

        logger.info("SFTP node executing: nodeId={}, host={}, port={}, operation={}, remotePath={}, itemId={}",
            nodeId, host, port, operation, remotePath, context.itemId());

        // Validate required fields
        if (host == null || host.isBlank()) {
            return NodeExecutionResult.failureWithOutput(nodeId,
                "SFTP: 'host' is required. Configure it in the SFTP credential.",
                buildErrorResult(host, operation, remotePath, startTime, resolvedParams),
                System.currentTimeMillis() - startTime);
        }
        if (remotePath == null || remotePath.isBlank()) {
            return NodeExecutionResult.failureWithOutput(nodeId,
                "SFTP: 'remotePath' is required.",
                buildErrorResult(host, operation, remotePath, startTime, resolvedParams),
                System.currentTimeMillis() - startTime);
        }

        Session session = null;
        ChannelSftp channel = null;
        try {
            JSch jsch = new JSch();

            if ("privatekey".equals(authMethod) || "private_key".equals(authMethod)) {
                if (privateKey != null && !privateKey.isBlank()) {
                    jsch.addIdentity("sftp-key", privateKey.getBytes(StandardCharsets.UTF_8), null, null);
                }
            }

            session = jsch.getSession(username, host, port);

            if ("password".equals(authMethod)) {
                if (password != null) {
                    session.setPassword(password);
                }
            }

            session.setConfig("StrictHostKeyChecking", "no");
            session.setTimeout(timeout);
            session.connect(timeout);

            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(timeout);

            Map<String, Object> result = switch (operation) {
                case "upload" -> executeUpload(channel, remotePath, localContent);
                case "download" -> executeDownload(channel, remotePath, context);
                case "list" -> executeList(channel, remotePath);
                case "delete" -> executeDelete(channel, remotePath);
                case "rename" -> executeRename(channel, remotePath, newPath);
                case "mkdir" -> executeMkdir(channel, remotePath);
                default -> throw new IllegalArgumentException(
                    "Unknown SFTP operation: " + operation +
                    ". Valid: upload, download, list, delete, rename, mkdir");
            };

            long durationMs = System.currentTimeMillis() - startTime;
            result.put("node_type", "SFTP");
            result.put("resolved_params", resolvedParams);
            result.put("success", true);
            result.put("operation", operation);
            result.put("remote_path", remotePath);
            result.put("duration_ms", durationMs);

            logger.info("SFTP node completed: nodeId={}, host={}, operation={}, durationMs={}",
                nodeId, host, operation, durationMs);

            return successWithMetadata(result, context);

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            logger.error("SFTP node failed: nodeId={}, host={}, operation={}, error={}",
                nodeId, host, operation, e.getMessage());

            Map<String, Object> errorResult = buildErrorResult(host, operation, remotePath, startTime, resolvedParams);
            return NodeExecutionResult.failureWithOutput(nodeId, e.getMessage(), errorResult, durationMs);

        } finally {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeUpload(ChannelSftp channel, String remotePath, String localContent)
            throws Exception {
        if (localContent == null || localContent.isEmpty()) {
            throw new IllegalArgumentException("SFTP upload: 'localContent' is required.");
        }
        byte[] data = localContent.getBytes(StandardCharsets.UTF_8);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data)) {
            channel.put(bais, remotePath);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("uploaded_size", data.length);
        return result;
    }

    private Map<String, Object> executeDownload(ChannelSftp channel, String remotePath, ExecutionContext context)
            throws Exception {
        // Check file size before download to prevent OOM
        SftpATTRS attrs = channel.stat(remotePath);
        if (attrs.getSize() > MAX_DOWNLOAD_SIZE) {
            throw new IllegalStateException("File too large: " + attrs.getSize() +
                " bytes exceeds " + MAX_DOWNLOAD_SIZE + " byte limit.");
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        channel.get(remotePath, baos);
        return buildDownloadResult(baos.toByteArray(), remotePath, context);
    }

    /**
     * v3.0 contract: SFTP download produces a FileRef in S3 (same path as
     * DownloadFileNode / ConvertToFileNode), indexed in {@code storage.storage}
     * so the Files panel surfaces it. Falls back to inline base64 ONLY when
     * the storage service is not wired (test profile / legacy back-compat) or
     * the upload fails - the bytes still reach downstream nodes that consume
     * {@code file_content}.
     *
     * <p>Package-private so {@link SftpNodeTest} can verify the upload-path
     * branch without mocking JSch's {@code ChannelSftp}.
     */
    Map<String, Object> buildDownloadResult(byte[] data, String remotePath, ExecutionContext context) {
        Map<String, Object> result = new LinkedHashMap<>();

        String fileName = FileNameExtractor.fromUrl(remotePath);
        if (fileName == null || fileName.isBlank()) {
            fileName = "sftp-download.bin";
        }
        String mimeType = mimeTypeRegistry != null
                ? mimeTypeRegistry.resolve(fileName, data)
                : "application/octet-stream";

        if (fileStorageService != null) {
            try {
                FileRef fileRef = fileStorageService.upload(
                        context.tenantId(),
                        context.plan() != null ? context.plan().getId() : null,
                        context.runId(),
                        nodeId,
                        fileName,
                        mimeType,
                        data,
                        resolveStorageEpoch(context),
                        context.spawn(),
                        context.itemIndex(),
                        com.apimarketplace.common.storage.service.StorageSourceTypes.STEP_OUTPUT);
                // Canonical FileRef only - frontend file-proxy injector and showcase HMAC
                // rewriter both probe this shape; pre-baked URL strings would be invisible
                // to anonymous marketplace/share visitors.
                result.put("file", fileRef);
                logger.info("SFTP download stored in S3: nodeId={}, path={}, size={} bytes",
                        nodeId, fileRef.path(), fileRef.size());
                return result;
            } catch (Exception e) {
                // Non-fatal: log loudly + fall back to inline base64 so the
                // node still returns the bytes. Operators see the warning
                // and Files-panel entry will be missing for this run.
                logger.warn("SFTP download S3 upload failed for nodeId={}, falling back to inline base64: {}",
                        nodeId, e.getMessage());
            }
        }

        // Fallback - inline base64 when storage is unavailable / fails.
        result.put("file_content", Base64.getEncoder().encodeToString(data));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> executeList(ChannelSftp channel, String remotePath) throws Exception {
        Vector<ChannelSftp.LsEntry> entries = channel.ls(remotePath);
        List<Map<String, Object>> files = new ArrayList<>();
        for (ChannelSftp.LsEntry entry : entries) {
            String name = entry.getFilename();
            if (".".equals(name) || "..".equals(name)) continue;
            SftpATTRS attrs = entry.getAttrs();
            Map<String, Object> fileInfo = new LinkedHashMap<>();
            fileInfo.put("name", name);
            fileInfo.put("size", attrs.getSize());
            fileInfo.put("is_dir", attrs.isDir());
            fileInfo.put("modified", Instant.ofEpochSecond(attrs.getMTime()).toString());
            files.add(fileInfo);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("files", files);
        result.put("file_count", files.size());
        return result;
    }

    private Map<String, Object> executeDelete(ChannelSftp channel, String remotePath) throws Exception {
        channel.rm(remotePath);
        return new LinkedHashMap<>();
    }

    private Map<String, Object> executeRename(ChannelSftp channel, String remotePath, String newPath)
            throws Exception {
        if (newPath == null || newPath.isBlank()) {
            throw new IllegalArgumentException("SFTP rename: 'newPath' is required.");
        }
        channel.rename(remotePath, newPath);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("new_path", newPath);
        return result;
    }

    private Map<String, Object> executeMkdir(ChannelSftp channel, String remotePath) throws Exception {
        channel.mkdir(remotePath);
        return new LinkedHashMap<>();
    }

    private static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? String.valueOf(val) : null;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultVal; }
        }
        return defaultVal;
    }

    private Map<String, Object> buildErrorResult(String host, String operation, String remotePath, long startTime,
                                                  Map<String, Object> resolvedParams) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node_type", "SFTP");
        result.put("resolved_params", resolvedParams != null ? resolvedParams : Map.of());
        result.put("success", false);
        result.put("host", host);
        result.put("operation", operation);
        result.put("remote_path", remotePath);
        // Shape-stability with DownloadFileNode/ConvertToFileNode/CompressionNode: emit
        // `file: null` on failed download so consumers can probe `{{...output.file}}`
        // consistently. Other operations (list/delete/rename/mkdir/upload) never produce
        // a FileRef on success either, so keeping the key absent matches their spec.
        if ("download".equalsIgnoreCase(operation)) {
            result.put("file", null);
        }
        result.put("duration_ms", System.currentTimeMillis() - startTime);
        return result;
    }

    public Core.SftpConfig getConfig() {
        return config;
    }

    public static class Builder {
        private String nodeId;
        private Core.SftpConfig config;

        public Builder nodeId(String nodeId) { this.nodeId = nodeId; return this; }
        public Builder sftpConfig(Core.SftpConfig config) { this.config = config; return this; }
        public Builder templateAdapter(Object adapter) { return this; }
        public SftpNode build() { return new SftpNode(nodeId, config); }
    }

    public static Builder builder() { return new Builder(); }
}
