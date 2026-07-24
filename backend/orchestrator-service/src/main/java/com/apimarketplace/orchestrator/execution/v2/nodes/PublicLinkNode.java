package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.file.PublicLinkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public Link node - turns a FileRef produced by an upstream node into a PUBLIC,
 * time-limited, HMAC-signed download URL on the platform's own storage.
 *
 * <p>Purpose: APIs that refuse file uploads and instead PULL from a URL (Instagram media
 * containers, TikTok PULL_FROM_URL, link previews) need a URL their servers can fetch with
 * zero credentials. The platform's FileRef URLs are authenticated; this node mints the
 * temporary public equivalent, without copying the file to any third-party host.</p>
 *
 * <p>Security: the node refuses to sign a key that does not belong to the executing
 * workflow's tenant (same foreign-key guard as the marketplace showcase rewriter), so a
 * crafted plan cannot mint links for another tenant's files. The link expires on its own;
 * there is no revocation in v1.</p>
 */
public class PublicLinkNode extends BaseNode {

    private static final Logger logger = LoggerFactory.getLogger(PublicLinkNode.class);

    private final String fileExpression;
    private final Integer ttlMinutes;
    private final String disposition;

    // Injected via ServiceRegistry
    private PublicLinkService publicLinkService;

    public PublicLinkNode(String nodeId, String fileExpression, Integer ttlMinutes, String disposition) {
        super(nodeId, NodeType.PUBLIC_LINK);
        this.fileExpression = fileExpression;
        this.ttlMinutes = ttlMinutes;
        this.disposition = disposition;
    }

    public void setPublicLinkService(PublicLinkService publicLinkService) {
        this.publicLinkService = publicLinkService;
    }

    @Override
    public void acceptServices(ServiceRegistry registry) {
        super.acceptServices(registry);
        this.publicLinkService = registry.getPublicLinkService();
    }

    @Override
    public NodeExecutionResult execute(ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        int effectiveTtl = PublicLinkService.clampTtlMinutes(ttlMinutes);
        String effectiveDisposition = "attachment".equalsIgnoreCase(disposition) ? "attachment" : "inline";

        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        resolvedParams.put("file_expression", fileExpression);
        resolvedParams.put("ttl_minutes", effectiveTtl);
        resolvedParams.put("disposition", effectiveDisposition);

        try {
            if (publicLinkService == null || !publicLinkService.isEnabled()) {
                return failure(context, resolvedParams, startTime,
                    "Public links are not enabled on this installation (signing secret or public URL missing)");
            }
            if (fileExpression == null || fileExpression.isBlank()) {
                return failure(context, resolvedParams, startTime, "file is required");
            }

            // Resolve the file expression to the RAW object (a FileRef serialised as a map:
            // {_type:'file', path, name, mimeType, size}) - stringifying it would lose the key.
            Object resolved = resolveRaw(fileExpression, context);
            Map<String, Object> fileRef = asFileRefMap(resolved);
            if (fileRef == null) {
                return failure(context, resolvedParams, startTime,
                    "file did not resolve to a file reference (map the WHOLE FileRef output, e.g. {{core:download.output.file}})");
            }
            String storageKey = String.valueOf(fileRef.get("path"));
            if (storageKey == null || storageKey.isBlank() || "null".equals(storageKey)) {
                return failure(context, resolvedParams, startTime, "resolved file reference has no storage path");
            }

            // Defense-in-depth: S3/MinIO keys are opaque (no ".." resolution today), but the
            // input map is plan-shapeable (a code node can emit any {path}); reject traversal
            // segments outright so a future filesystem-backed storage cannot be walked.
            if (storageKey.contains("..")) {
                return failure(context, resolvedParams, startTime,
                    "file path contains an invalid segment - refusing to mint a public link");
            }

            // Ownership guard: only sign keys namespaced under the executing tenant. Mirrors
            // isKeyOwnedByTenant + the showcase rewriter's foreign-key guard.
            String tenantPrefix = context.tenantId() + "/";
            if (!storageKey.startsWith(tenantPrefix)) {
                logger.warn("Public link REFUSED for foreign key: nodeId={}, tenant={}, key={}",
                    nodeId, context.tenantId(), storageKey);
                return failure(context, resolvedParams, startTime,
                    "file does not belong to this workflow's tenant - refusing to mint a public link");
            }

            PublicLinkService.PublicLink link = publicLinkService.mint(storageKey, effectiveTtl, effectiveDisposition);
            if (link == null) {
                return failure(context, resolvedParams, startTime, "public link minting failed");
            }

            logger.info("Public link minted: nodeId={}, key={}, ttlMinutes={}, expiresAt={}",
                nodeId, storageKey, effectiveTtl, link.expiresAt());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", link.url());
            result.put("expires_at", link.expiresAt().toString());
            result.put("ttl_minutes", effectiveTtl);
            result.put("file", fileRef);
            result.put("node_type", "PUBLIC_LINK");
            result.put("resolved_params", resolvedParams);
            return NodeExecutionResult.success(nodeId, result);

        } catch (Exception e) {
            logger.error("Public link node failed: nodeId={}, error={}", nodeId, e.getMessage(), e);
            return failure(context, resolvedParams, startTime, "Public link failed: " + e.getMessage());
        }
    }

    private NodeExecutionResult failure(ExecutionContext context, Map<String, Object> resolvedParams,
                                        long startTime, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", null);
        out.put("expires_at", null);
        out.put("file", null);
        out.put("resolved_params", resolvedParams);
        return NodeExecutionResult.failureWithOutput(nodeId, message,
            enrichWithMetadata(out, context), System.currentTimeMillis() - startTime);
    }

    /** Resolve a template expression keeping the RAW object type (not stringified). */
    private Object resolveRaw(String expression, ExecutionContext context) {
        if (templateAdapter == null) {
            return expression;
        }
        try {
            Map<String, Object> resolved = templateAdapter.resolveTemplates(Map.of("__expr__", expression), context);
            return resolved.get("__expr__");
        } catch (Exception e) {
            logger.warn("Failed to resolve file expression: {} - {}", expression, e.getMessage());
            return null;
        }
    }

    /** Accept a FileRef-shaped map ({_type:'file'} or at least a 'path' key). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asFileRefMap(Object resolved) {
        if (resolved instanceof Map<?, ?> map && map.get("path") instanceof String) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    public String getFileExpression() {
        return fileExpression;
    }

    public Integer getTtlMinutes() {
        return ttlMinutes;
    }

    public String getDisposition() {
        return disposition;
    }
}
