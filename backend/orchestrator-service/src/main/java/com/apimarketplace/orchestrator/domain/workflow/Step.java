package com.apimarketplace.orchestrator.domain.workflow;

import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Step du workflow (MCP tool calls and CRUD operations).
 *
 * Step types (field 'type'):
 * - "mcp": Regular MCP/API call (default)
 * - "crud-create-row": Create row in datasource
 * - "crud-read-row": Read rows from datasource
 * - "crud-update-row": Update rows in datasource
 * - "crud-delete-row": Delete rows from datasource
 * - "crud-create-column": Create column in datasource
 * - "crud-find": Query rows + split parallel per row (hybrid CRUD+Split, creates FindNode)
 *
 * Note: Transform and Wait are in Core, not Step.
 */
public record Step(String id,
                   String type,
                   String label,
                   String parentLoopId,
                   Map<String, Object> params,
                   Long dataSourceId,
                   CrudConfig crud,
                   String graphNodeId,
                   Long selectedCredentialId,
                   CredentialSource credentialSource,
                   Long platformCredentialId,
                   String credentialSelector) {

    // Valid step types
    private static final Set<String> VALID_TYPES = Set.of(
        "mcp",
        "crud-create-row", "crud-read-row", "crud-update-row", "crud-delete-row", "crud-create-column",
        "crud-find"
    );

    // CRUD types
    private static final Set<String> CRUD_TYPES = Set.of(
        "crud-create-row", "crud-read-row", "crud-update-row", "crud-delete-row", "crud-create-column",
        "crud-find"
    );

    /**
     * Configuration for CRUD operations (create-row, read-row, update-row, delete-row, create-column, find).
     */
    public record CrudConfig(
        WhereCondition where,
        SimilarityConfig similarity,
        Map<String, Object> set,
        List<RowData> rows,
        List<ColumnDefinition> columns,
        Integer limit,
        Integer offset,
        // limit / offset / similarity.topK / similarity.threshold written as {{...}} templates, by
        // field name: an Integer cannot hold one, and the parser used to drop it for the default
        // (500 rows) with no word. The node resolves them at run time. Never serialized.
        @com.fasterxml.jackson.annotation.JsonIgnore
        Map<String, String> deferredScalars
    ) {
        public CrudConfig {
            set = set == null ? Map.of() : Map.copyOf(set);
            rows = rows == null ? List.of() : List.copyOf(rows);
            columns = columns == null ? List.of() : List.copyOf(columns);
            deferredScalars = deferredScalars == null ? Map.of() : Map.copyOf(deferredScalars);
        }

        /** The constructor before {@link #deferredScalars}: no templated limit, offset or similarity bound. */
        public CrudConfig(WhereCondition where, SimilarityConfig similarity, Map<String, Object> set,
                          List<RowData> rows, List<ColumnDefinition> columns, Integer limit, Integer offset) {
            this(where, similarity, set, rows, columns, limit, offset, Map.of());
        }

        /**
         * WHERE condition for read, update, delete operations.
         */
        public record WhereCondition(String column, String operator, Object value) {}

        /**
         * Row data for create-row operation.
         */
        public record RowData(String id, Map<String, Object> columns) {
            public RowData {
                columns = columns == null ? Map.of() : Map.copyOf(columns);
            }
        }

        /**
         * Column definition for create-column operation.
         */
        public record ColumnDefinition(String name, String type, Object defaultValue) {}

        /**
         * Similarity search configuration for vector queries.
         */
        public record SimilarityConfig(String column, String queryVector, Integer topK, Double threshold) {}
    }

    public Step {
        id = normalizeNullable(id);
        type = normalizeType(type);
        label = normalizeMandatory(label, "step label");
        parentLoopId = normalizeNullable(parentLoopId);
        params = params == null ? Map.of() : Map.copyOf(params);
        // graphNodeId is optional - passed from frontend plan, kept as-is (not normalized)
        credentialSource = credentialSource == null ? CredentialSource.USER : credentialSource;
        // Only trimmed, never collapsed to null when blank. Absent means the step
        // has no dynamic selection at all; PRESENT BUT BLANK means the author chose
        // dynamic mode and left it empty, which has to fail loudly at run time.
        // Collapsing the two made clearing the field in the builder fall back to the
        // auto-filled pin, turning a multi-account step back into a single-account
        // one with nothing to show for it.
        credentialSelector = credentialSelector == null ? null : credentialSelector.trim();
        if (credentialSource.isPlatform() && platformCredentialId == null) {
            throw new IllegalArgumentException(
                    "Step '" + label + "' has credentialSource=platform but no platformCredentialId");
        }
    }

    /**
     * Back-compat constructor - callers that predate platform-markup pricing build
     * steps with the user-owned credential semantics.
     */
    public Step(String id,
                String type,
                String label,
                String parentLoopId,
                Map<String, Object> params,
                Long dataSourceId,
                CrudConfig crud,
                String graphNodeId) {
        this(id, type, label, parentLoopId, params, dataSourceId, crud, graphNodeId,
                null, CredentialSource.USER, null, null);
    }

    /**
     * Back-compat constructor for every caller that predates the dynamic
     * credential selector. A step built through it has no selector, which is
     * the state of every plan written before the field existed: the credential
     * is resolved exactly as it was, from {@code selectedCredentialId} or the
     * account default.
     */
    public Step(String id,
                String type,
                String label,
                String parentLoopId,
                Map<String, Object> params,
                Long dataSourceId,
                CrudConfig crud,
                String graphNodeId,
                Long selectedCredentialId,
                CredentialSource credentialSource,
                Long platformCredentialId) {
        this(id, type, label, parentLoopId, params, dataSourceId, crud, graphNodeId,
                selectedCredentialId, credentialSource, platformCredentialId, null);
    }

    /**
     * Back-compat constructor for callers that predate workflow user-credential
     * pinning but already pass the platform credential fields.
     */
    public Step(String id,
                String type,
                String label,
                String parentLoopId,
                Map<String, Object> params,
                Long dataSourceId,
                CrudConfig crud,
                String graphNodeId,
                CredentialSource credentialSource,
                Long platformCredentialId) {
        this(id, type, label, parentLoopId, params, dataSourceId, crud, graphNodeId,
                null, credentialSource, platformCredentialId);
    }

    public boolean usesPlatformCredential() {
        return credentialSource == CredentialSource.PLATFORM;
    }

    /**
     * True when this step decides its credential at RUN time rather than at
     * authoring time.
     *
     * <p>This is the single discriminator between the two modes, and it is set
     * only by a deliberate gesture (the builder's toggle, or an explicit
     * parameter). It is deliberately NOT inferred from
     * {@code selectedCredentialId}, which the node inspector auto-fills with the
     * account's default key on first render, with no user action: "a pin exists"
     * has never meant "the author chose one".
     */
    public boolean hasCredentialSelector() {
        return credentialSelector != null;
    }

    /**
     * Returns true if this is a CRUD step.
     */
    public boolean isCrudStep() {
        return type != null && CRUD_TYPES.contains(type);
    }

    /**
     * Returns true if this is a CRUD find step (read + split).
     */
    public boolean isFindStep() {
        return "crud-find".equals(type) || "crud-read-row".equals(type);
    }

    /**
     * Returns true if this is a regular MCP step.
     */
    public boolean isMcpStep() {
        return "mcp".equals(type);
    }

    /**
     * Returns the CRUD operation type (create-row, read-row, update-row, delete-row, create-column).
     * Returns null if not a CRUD step.
     */
    public String getCrudOperation() {
        if (!isCrudStep()) {
            return null;
        }
        // type is "crud-create-row" -> return "create-row"
        return type.substring("crud-".length());
    }

    public String normalizedLabel() {
        return LabelNormalizer.normalizeLabel(label);
    }

    public String getNormalizedKey() {
        String normalized = LabelNormalizer.normalizeLabel(label);
        if (normalized == null) return null;
        String prefix = isCrudStep() ? "table" : "mcp";
        return prefix + ":" + normalized;
    }

    private static String normalizeMandatory(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Missing " + field);
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Empty " + field);
        }
        return trimmed;
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String normalizeType(String value) {
        if (value == null || value.isBlank()) {
            return "mcp"; // Default type
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!VALID_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("Invalid step type: " + value + ". Valid types: " + VALID_TYPES);
        }
        return normalized;
    }

    public Step withParams(Map<String, Object> newParams) {
        return new Step(id, type, label, parentLoopId, newParams, dataSourceId, crud, graphNodeId,
                selectedCredentialId, credentialSource, platformCredentialId, credentialSelector);
    }
}
