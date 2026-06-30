package com.apimarketplace.publication.service.resource;

import com.apimarketplace.datasource.client.dto.ColumnMappingSpecDto;
import com.apimarketplace.datasource.client.dto.ColumnTypeDto;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Copies S3/object-storage files referenced by a DataSource when that DataSource
 * is cloned across tenants.
 *
 * <p>Two sources of external paths exist:
 * <ul>
 *   <li><b>sourceConfig.file_path</b> - set when the DataSource's sourceType is FILE
 *       (see {@code DataSourceModels.FileConfig}).</li>
 *   <li><b>Row data, for FILE / IMAGE columns</b> - the row value is either a string
 *       path or a {@code {path,name,mimeType}} map, mirroring the DataInput shape.</li>
 * </ul>
 *
 * <p>Both are re-uploaded via {@link OrchestratorInternalClient#copyFile} under the
 * acquirer's tenant and the paths are rewritten in place. External URLs (http/https)
 * and non-storage references are left untouched.
 *
 * <p>The service is best-effort: a failed copy logs a warning but does not abort
 * the acquisition (the row/file reference simply points to the original path).
 * Callers that need hard failure semantics should check the returned counters.
 */
@Service
public class DataSourceFileCloneService {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceFileCloneService.class);

    private static final String RUN_ID = "publication";
    private static final String STEP_ALIAS_SOURCE_CONFIG = "datasource-source";
    private static final String STEP_ALIAS_ROW = "datasource-row";

    private final OrchestratorInternalClient orchestratorClient;

    public DataSourceFileCloneService(OrchestratorInternalClient orchestratorClient) {
        this.orchestratorClient = orchestratorClient;
    }

    /**
     * Rewrite S3 file paths in {@code sourceConfig} and {@code items} in place so they
     * point to files owned by the acquiring tenant.
     *
     * @param sourceType   DataSource source type, case-insensitive ("FILE" triggers the
     *                     sourceConfig rewrite, everything else is skipped for that step)
     * @param sourceConfig mutable source config map - may carry {@code file_path}
     * @param items        row snapshots. Each entry may be {@code {data:{...}, priority:N}}
     *                     (the canonical shape from {@code DataSourceItemDto}) or the raw
     *                     column→value map itself
     * @param mappingSpec  column name → mapping spec, used to find FILE / IMAGE columns
     * @param tenantId     acquiring tenant - written as the new owner
     * @param scopeId      scope used in the new storage path (typically the publication ID)
     * @param organizationId acquirer's org - forwarded so the new storage-row index is
     *                       org-scoped (without it the copy yields an id-less, unrenderable cell)
     */
    public void rewriteFilePaths(String sourceType,
                                 Map<String, Object> sourceConfig,
                                 List<Map<String, Object>> items,
                                 Map<String, ColumnMappingSpecDto> mappingSpec,
                                 String tenantId,
                                 String scopeId,
                                 String organizationId) {
        if (tenantId == null || scopeId == null) {
            logger.warn("DataSourceFileCloneService skipped: missing tenantId or scopeId");
            return;
        }
        if ("FILE".equalsIgnoreCase(sourceType) && sourceConfig != null) {
            rewriteSourceConfigFilePath(sourceConfig, tenantId, scopeId, organizationId);
        }
        if (items != null && !items.isEmpty() && mappingSpec != null && !mappingSpec.isEmpty()) {
            Set<String> fileColumns = collectFileColumns(mappingSpec);
            if (!fileColumns.isEmpty()) {
                for (Map<String, Object> item : items) {
                    if (item == null) continue;
                    rewriteItemFilePaths(item, fileColumns, tenantId, scopeId, organizationId);
                }
            }
        }
    }

    private void rewriteSourceConfigFilePath(Map<String, Object> sourceConfig, String tenantId,
                                             String scopeId, String organizationId) {
        Object pathRaw = sourceConfig.get("file_path");
        if (!(pathRaw instanceof String pathStr) || pathStr.isBlank()) return;
        if (!isCopyable(pathStr)) return;

        String newPath = copyFile(pathStr, tenantId, scopeId, STEP_ALIAS_SOURCE_CONFIG,
                extractFileName(pathStr), "application/octet-stream", organizationId);
        if (newPath != null) {
            sourceConfig.put("file_path", newPath);
            logger.info("Rewrote DataSource sourceConfig.file_path {} -> {}", pathStr, newPath);
        }
    }

    @SuppressWarnings("unchecked")
    private void rewriteItemFilePaths(Map<String, Object> item, Set<String> fileColumns,
                                      String tenantId, String scopeId, String organizationId) {
        // Canonical shape: {"data": {col: val, ...}, "priority": N}.
        // Some callers hand the raw column map directly - handle both.
        Object dataRaw = item.get("data");
        Map<String, Object> data;
        if (dataRaw instanceof Map<?, ?> dataMap) {
            data = (Map<String, Object>) dataMap;
        } else {
            data = item;
        }
        for (String col : fileColumns) {
            if (!data.containsKey(col)) continue;
            Object val = data.get(col);
            Object rewritten = copyValueIfFile(val, tenantId, scopeId, organizationId);
            if (rewritten != val) {
                data.put(col, rewritten);
            }
        }
    }

    /**
     * Recursively rewrite a cell value that may be a path string, a {path,name,mimeType}
     * map, or a list of either. Returns the original reference unchanged when nothing
     * needs copying, so callers can detect mutation via {@code !=}.
     */
    @SuppressWarnings("unchecked")
    private Object copyValueIfFile(Object val, String tenantId, String scopeId, String organizationId) {
        if (val == null) return null;
        if (val instanceof String s) {
            if (!isCopyable(s)) return val;
            String newPath = copyFile(s, tenantId, scopeId, STEP_ALIAS_ROW,
                    extractFileName(s), "application/octet-stream", organizationId);
            return newPath != null ? newPath : val;
        }
        if (val instanceof Map<?, ?> m) {
            Map<String, Object> fileMap = (Map<String, Object>) m;
            Object pathRaw = fileMap.get("path");
            if (!(pathRaw instanceof String pathStr) || !isCopyable(pathStr)) return val;

            String name = fileMap.get("name") != null ? fileMap.get("name").toString()
                    : extractFileName(pathStr);
            String mime = fileMap.get("mimeType") != null ? fileMap.get("mimeType").toString()
                    : "application/octet-stream";

            Map<String, Object> result = copyFileResult(pathStr, tenantId, scopeId, STEP_ALIAS_ROW, name, mime, organizationId);
            if (result == null || result.get("newPath") == null) return val;

            Map<String, Object> copy = new LinkedHashMap<>(fileMap);
            copy.put("path", result.get("newPath").toString());
            // Adopt the NEW storage-row id so the opaque by-id URL resolves in the acquirer's
            // tenant; a leftover source id would 403/404 cross-tenant. Drop a stale id otherwise.
            if (result.get("newId") instanceof String newId) {
                copy.put("id", newId);
            } else {
                copy.remove("id");
            }
            return copy;
        }
        if (val instanceof List<?> list) {
            boolean changed = false;
            List<Object> out = new ArrayList<>(list.size());
            for (Object element : list) {
                Object rewritten = copyValueIfFile(element, tenantId, scopeId, organizationId);
                if (rewritten != element) changed = true;
                out.add(rewritten);
            }
            return changed ? out : val;
        }
        return val;
    }

    private Set<String> collectFileColumns(Map<String, ColumnMappingSpecDto> mappingSpec) {
        Set<String> columns = new HashSet<>();
        for (Map.Entry<String, ColumnMappingSpecDto> entry : mappingSpec.entrySet()) {
            ColumnMappingSpecDto spec = entry.getValue();
            if (spec == null) continue;
            ColumnTypeDto type = spec.type();
            if (type == ColumnTypeDto.FILE || type == ColumnTypeDto.IMAGE) {
                columns.add(entry.getKey());
            }
        }
        return columns;
    }

    /**
     * A path is copyable when it is stored by our platform (bucket key or storage-prefixed
     * URI). External URLs pass through untouched - the publisher intended an external link.
     */
    private boolean isCopyable(String path) {
        if (path == null || path.isBlank()) return false;
        if (path.startsWith("http://") || path.startsWith("https://")) return false;
        // Any scheme other than http(s) - e.g. s3://, gs:// - is treated as storage and copied.
        // Paths with no scheme are treated as bucket keys (the canonical internal form).
        return true;
    }

    private String extractFileName(String path) {
        if (path == null) return "file";
        int idx = path.lastIndexOf('/');
        if (idx >= 0 && idx < path.length() - 1) {
            return path.substring(idx + 1);
        }
        return path;
    }

    private String copyFile(String sourcePath, String tenantId, String scopeId,
                             String stepAlias, String fileName, String mimeType, String organizationId) {
        Map<String, Object> result = copyFileResult(sourcePath, tenantId, scopeId, stepAlias, fileName, mimeType, organizationId);
        return result == null ? null : result.get("newPath").toString();
    }

    /**
     * Re-upload the file and return the orchestrator's full {newPath, newId} response (or null).
     * The {@code newId} is the NEW storage-row id; a FileRef cell MUST adopt it so its opaque by-id
     * URL resolves in the acquirer's tenant instead of 403/404-ing against the publisher's row.
     * {@code organizationId} (acquirer's org) is forwarded so the new storage index row is
     * org-scoped - without it the index insert fails and {@code newId} comes back null.
     */
    private Map<String, Object> copyFileResult(String sourcePath, String tenantId, String scopeId,
                                                String stepAlias, String fileName, String mimeType,
                                                String organizationId) {
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("sourcePath", sourcePath);
            request.put("sourceTenantId", sourceTenantIdForPath(sourcePath));
            request.put("tenantId", tenantId);
            request.put("workflowId", scopeId);
            request.put("runId", RUN_ID);
            request.put("stepAlias", stepAlias);
            request.put("fileName", fileName);
            request.put("mimeType", mimeType);

            Map<String, Object> result = orchestratorClient.copyFile(request, organizationId);
            if (result == null || result.get("newPath") == null) {
                logger.warn("copyFile returned no newPath for source={}", sourcePath);
                return null;
            }
            return result;
        } catch (Exception e) {
            logger.error("Failed to copy DataSource file {}: {}", sourcePath, e.getMessage());
            return null;
        }
    }

    private String sourceTenantIdForPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        if (path.contains("://")) {
            return null;
        }
        int idx = path.indexOf('/');
        if (idx <= 0) {
            return null;
        }
        return path.substring(0, idx);
    }
}
