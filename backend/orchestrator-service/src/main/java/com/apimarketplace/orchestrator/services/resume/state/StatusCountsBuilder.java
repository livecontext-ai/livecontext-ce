package com.apimarketplace.orchestrator.services.resume.state;

import com.apimarketplace.orchestrator.domain.execution.StatusCounts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Converts StatusCounts (from StateSnapshot) to Map format for API responses.
 *
 * <p>StatusCounts are now computed from StateSnapshot (single source of truth).
 * This builder only handles format conversion and key lookup.
 */
public class StatusCountsBuilder {

    private static final Logger logger = LoggerFactory.getLogger(StatusCountsBuilder.class);

    private final StateReconstructorHelper helper;

    public StatusCountsBuilder(StateReconstructorHelper helper) {
        this.helper = helper;
    }

    /**
     * Gets status counts as a Map<String, Integer> for a step, trying multiple key formats.
     *
     * <p>Looks up counts by stepId first, then alias, then stripped prefix.
     * This handles cases where StateSnapshot stores counts under different key formats.
     */
    public Map<String, Integer> getStatusCountsMap(String stepId, String alias, Map<String, StatusCounts> stepStatusCounts) {
        // Try to find counts using different key formats
        StatusCounts counts = stepStatusCounts.get(stepId);
        if (counts == null) {
            counts = stepStatusCounts.get(alias);
        }
        if (counts == null) {
            // Try without prefix
            String withoutPrefix = stepId.replaceFirst("^(mcp:|trigger:|core:|agent:|table:|interface:|note:)", "");
            counts = stepStatusCounts.get(withoutPrefix);
        }

        if (counts != null) {
            Map<String, Integer> result = new LinkedHashMap<>();
            result.put("running", counts.getRunning());
            result.put("completed", counts.getCompleted());
            result.put("failed", counts.getFailed());
            result.put("skipped", counts.getSkipped());
            result.put("total", counts.getTotal());
            return result;
        }

        return null;
    }
}
