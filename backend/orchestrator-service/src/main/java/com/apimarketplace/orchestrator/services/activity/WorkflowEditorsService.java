package com.apimarketplace.orchestrator.services.activity;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.auth.UserSummaryDto;
import com.apimarketplace.orchestrator.controllers.dto.ResourceEditorDto;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who has recently worked on a workflow, derived from its stored plan versions.
 *
 * <p>There is no {@code updated_by} column on {@code workflows} - and adding one would be
 * WRONG here, because {@code workflows.updated_at} is bumped by every run lifecycle
 * transition as well as by edits, so whoever fired the automation last would be recorded as
 * having "modified" it. {@code workflow_plan_versions} is the better answer: a row is written
 * because someone SAVED a plan, and it already carries {@code created_by}. Which is why this
 * reads that table rather than a column on the workflow.
 *
 * <p>Better, not perfect, and the gap is worth knowing: a version row's PLAN can be refreshed
 * in place - by the layout-only path, and by the execution-time content refresh in
 * {@code WorkflowPlanVersionService} - without {@code created_at} or {@code created_by} being
 * touched. So a row's stamp is when that VERSION was first written, and content a later actor
 * produced can stay filed under the original author. The user-facing wording is hedged to
 * match ("saved a change", not "wrote what you are looking at").
 *
 * <p>Consequences worth knowing at the call site:
 * <ul>
 *   <li>The history is bounded by {@code workflow.versioning.max-versions} (older versions
 *       are purged), so {@code editCount} is "within the retained window", never "ever".</li>
 *   <li>A layout-only change refreshes its version row IN PLACE rather than minting a new
 *       one, so moving nodes around does not make someone an editor.</li>
 *   <li>Versions written before {@code created_by} was populated carry a null author and are
 *       skipped rather than attributed to anyone.</li>
 * </ul>
 */
@Service
public class WorkflowEditorsService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowEditorsService.class);

    /**
     * How many distinct people the popover lists. Past a handful the list stops answering
     * "who works on this" and starts being a changelog, which the version drawer already is.
     */
    static final int MAX_EDITORS = 5;

    private final WorkflowPlanVersionRepository versionRepository;
    private final AuthClient authClient;

    public WorkflowEditorsService(WorkflowPlanVersionRepository versionRepository,
                                  AuthClient authClient) {
        this.versionRepository = versionRepository;
        this.authClient = authClient;
    }

    /**
     * The distinct people who wrote this workflow's retained versions, most recent editor
     * first, capped at {@link #MAX_EDITORS}.
     *
     * <p>Callers MUST have verified the workflow is in the caller's active workspace first:
     * this method reads version rows by workflow id and applies no scope check of its own.
     *
     * @return an empty list when the workflow has no version carrying an author (a brand-new
     *         workflow, or one whose whole retained history predates author stamping)
     */
    public List<ResourceEditorDto> listRecentEditors(UUID workflowId) {
        List<WorkflowPlanVersionRepository.VersionAuthorProjection> authors =
                versionRepository.findVersionAuthors(workflowId);
        if (authors.isEmpty()) {
            return Collections.emptyList();
        }

        // Insertion order is the query's (version DESC), so the first row seen for a user is
        // their most recent save and the map's iteration order is already "most recently
        // active editor first". The WHOLE retained window is walked before the cap is
        // applied, deliberately: stopping at the cap-th distinct user would drop the older
        // saves of the people who ARE listed, and their tally is half of what the list says.
        Map<String, Aggregate> byUser = new LinkedHashMap<>();
        for (WorkflowPlanVersionRepository.VersionAuthorProjection row : authors) {
            String userId = row.getUserId();
            if (userId == null || userId.isBlank()) {
                // Written before created_by was stamped. Attributing it to anyone would be a
                // guess, and the person it would name is whoever happens to appear next.
                continue;
            }
            Aggregate seen = byUser.get(userId);
            if (seen != null) {
                seen.editCount++;
            } else {
                byUser.put(userId, new Aggregate(row.getEditedAt()));
            }
        }
        if (byUser.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map.Entry<String, Aggregate>> top = new ArrayList<>(byUser.entrySet());
        if (top.size() > MAX_EDITORS) {
            top = top.subList(0, MAX_EDITORS);
        }

        Map<String, UserSummaryDto> names = resolveNames(top);

        List<ResourceEditorDto> editors = new ArrayList<>(top.size());
        for (Map.Entry<String, Aggregate> entry : top) {
            UserSummaryDto summary = names.get(entry.getKey());
            editors.add(new ResourceEditorDto(
                    entry.getKey(),
                    summary != null ? summary.displayName() : null,
                    entry.getValue().editedAt,
                    entry.getValue().editCount));
        }
        return editors;
    }

    /**
     * Names for the editors being returned, in ONE cache-aware RPC.
     *
     * <p>Best-effort by design: the frontend's own roster lookup is the primary source of a
     * name here, so a failed resolve costs an ex-member's name, never the list. A throw would
     * turn "we could not name one person" into "this workflow has no editors".
     */
    private Map<String, UserSummaryDto> resolveNames(List<Map.Entry<String, Aggregate>> top) {
        Set<String> ids = new HashSet<>(top.size());
        for (Map.Entry<String, Aggregate> entry : top) {
            ids.add(entry.getKey());
        }
        try {
            return authClient.batchResolveUsers(ids);
        } catch (Exception e) {
            logger.warn("Editor name resolution failed for {} id(s), falling back to ids only: {}",
                    ids.size(), e.getMessage());
            return Collections.emptyMap();
        }
    }

    /** Mutable per-user tally while walking the version rows. */
    private static final class Aggregate {
        private final Instant editedAt;
        private int editCount = 1;

        private Aggregate(Instant editedAt) {
            this.editedAt = editedAt;
        }
    }
}
