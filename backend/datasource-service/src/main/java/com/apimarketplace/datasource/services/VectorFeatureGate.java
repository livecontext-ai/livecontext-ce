package com.apimarketplace.datasource.services;

import com.apimarketplace.common.web.AppEditionProvider;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Edition gate for vector (embedding) columns: allowed on self-hosted
 * deployments only (Community Edition and Self-Hosted Enterprise), blocked on
 * managed cloud (CLOUD and DEDICATED_CLOUD).
 *
 * <p><b>Why:</b> embeddings live in {@code datasource.data_source_vectors} on
 * the shared managed-cloud Postgres. A single tenant ingesting a real RAG
 * corpus (10k+ × 6 KB vectors) competes for cache, CPU and disk with every
 * other schema on the instance - including {@code workflow_runs.state_snapshot}
 * (the execution engine's hot path). Self-hosted users own their database, so
 * the trade-off is theirs to make.
 *
 * <p>Edition resolution delegates to the auto-configured
 * {@link AppEditionProvider} bean (the SSOT: {@code app.edition} property,
 * falling back to the {@code ce} Spring profile) - common-lib registers it in
 * every service via {@code AppEditionAutoConfiguration}.
 *
 * <p>Enforced at every path that can create or use a vector column
 * (column-definition validation chokepoint, raw {@code mappingSpec} on
 * datasource creation, snapshot clone import, similarity search, vector-value
 * inserts). The error message is written for the chat agent: it states the
 * constraint and the available alternative, nothing it cannot act on.
 */
@Component
public class VectorFeatureGate {

    private static final Logger log = LoggerFactory.getLogger(VectorFeatureGate.class);

    /**
     * Single user/agent-facing message for every rejection path. Mentions no
     * REST paths or internals - the agent's only valid move is to use a
     * non-vector column type (or the user to switch to a self-hosted edition).
     */
    public static final String DISABLED_MESSAGE =
            "Vector (embedding) columns are not available on this deployment - they are a "
                    + "self-hosted (Community Edition) feature. Use text columns with keyword "
                    + "filters instead of similarity search.";

    private final boolean vectorAllowed;

    public VectorFeatureGate(AppEditionProvider editionProvider) {
        this.vectorAllowed = editionProvider.isSelfHosted();
        log.info("[VectorFeatureGate] vector columns {}",
                vectorAllowed ? "ENABLED (self-hosted)" : "DISABLED (managed cloud)");
    }

    public boolean isVectorAllowed() {
        return vectorAllowed;
    }

    /**
     * Snapshot-clone sanitizer: returns the mappingSpec with VECTOR columns
     * removed when the gate is closed (identity otherwise, including null).
     * Used by the publication acquire/clone path - rejecting the whole clone
     * because the published table carries an embedding column would break the
     * acquisition of an otherwise-valid marketplace workflow; the non-vector
     * columns survive and the workflow's similarity steps fail at run time
     * with {@link #DISABLED_MESSAGE}.
     */
    public Map<String, ColumnMappingSpec> stripDisallowedVectorColumns(
            Map<String, ColumnMappingSpec> mappingSpec) {
        List<String> vectorColumns = disallowedVectorColumns(mappingSpec);
        if (vectorColumns.isEmpty()) {
            return mappingSpec;
        }
        Map<String, ColumnMappingSpec> filtered = new LinkedHashMap<>(mappingSpec);
        vectorColumns.forEach(filtered::remove);
        log.warn("[VectorFeatureGate] stripped {} vector column(s) {} from cloned snapshot (managed cloud)",
                vectorColumns.size(), vectorColumns);
        return filtered;
    }

    /**
     * Vector column names that the active edition refuses (empty when allowed
     * or none present). Companion of {@link #stripDisallowedVectorColumns} so
     * callers can also purge the same names from {@code columnOrder} - a
     * stripped column left in the order list renders as a ghost column in the
     * table UI.
     */
    public List<String> disallowedVectorColumns(Map<String, ColumnMappingSpec> mappingSpec) {
        if (vectorAllowed || mappingSpec == null || mappingSpec.isEmpty()) {
            return List.of();
        }
        return mappingSpec.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().type() == ColumnType.VECTOR)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** First VECTOR column name in the supplied mappingSpec, or null. */
    public static String findVectorColumn(Map<String, ColumnMappingSpec> mappingSpec) {
        if (mappingSpec == null) {
            return null;
        }
        return mappingSpec.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue().type() == ColumnType.VECTOR)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }
}
