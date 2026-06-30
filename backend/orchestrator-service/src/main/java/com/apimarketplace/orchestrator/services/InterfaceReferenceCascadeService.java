package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Scrubs references to a deleted interface from every workflow plan that
 * still mentions it, so the {@code interface.interfaces} row can be deleted
 * without leaving dangling {@code plan.interfaces[]} or {@code plan.edges[]}
 * entries behind.
 *
 * <p>Called synchronously by {@code interface-service} (see
 * {@code InterfaceService.deleteInterface}) BEFORE the row delete - if the
 * cascade fails, the caller aborts the delete (atomicity over availability
 * for plan integrity).
 *
 * <p>The orchestrator-only schema constraint (CLAUDE.md) is respected: this
 * service queries {@code orchestrator.workflows} only.
 */
@Service
public class InterfaceReferenceCascadeService {

    private static final Logger log = LoggerFactory.getLogger(InterfaceReferenceCascadeService.class);

    private final WorkflowRepository workflowRepository;

    public InterfaceReferenceCascadeService(WorkflowRepository workflowRepository) {
        this.workflowRepository = workflowRepository;
    }

    /**
     * Strip every reference to {@code interfaceId} from {@code plan.interfaces[]}
     * and {@code plan.edges[]} across all workflows in the same tenant. Returns
     * a summary of what was scrubbed, so the caller can audit/log the cascade.
     *
     * <p>The cascade walks every workflow in the tenant whose plan still
     * mentions the id; for each one it (1) drops the matching
     * {@code plan.interfaces[]} entry, (2) drops any {@code plan.edges[]}
     * whose normalized {@code interface:<label>} key resolved to the deleted
     * row AND no other interface entry shares the same label, (3) saves the
     * workflow with a fresh {@code updatedAt}.
     *
     * @param tenantId tenant scope (must match every workflow touched)
     * @param interfaceId UUID string of the deleted interface
     * @return summary of affected workflows + scrubbed entries
     */
    @Transactional
    public CascadeResult stripReferences(String tenantId, String interfaceId) {
        if (tenantId == null || tenantId.isBlank() || interfaceId == null || interfaceId.isBlank()) {
            return new CascadeResult(0, 0, 0, List.of());
        }

        List<WorkflowEntity> referencing = workflowRepository.findByPlanInterfaceId(interfaceId);
        if (referencing.isEmpty()) {
            return new CascadeResult(0, 0, 0, List.of());
        }

        int workflowsTouched = 0;
        int entriesRemoved = 0;
        int edgesRemoved = 0;
        List<String> touchedIds = new ArrayList<>(referencing.size());

        for (WorkflowEntity workflow : referencing) {
            if (!tenantId.equals(workflow.getTenantId())) {
                // Cross-tenant defense: never silently mutate another tenant's
                // plan even if the JSONB query happened to surface their row.
                log.warn("[InterfaceCascade] Skipping workflow {} (tenant mismatch: workflow={} caller={})",
                    workflow.getId(), workflow.getTenantId(), tenantId);
                continue;
            }

            // APPLICATION workflows are frozen acquired marketplace clones. Their plan
            // is the contract the acquirer received - including any references to the
            // (now deleted) interface. Scrubbing them in this cascade would silently
            // drift the plan from basePlan with no way for the user to recover. We
            // accept the orphan reference in the frozen plan (the runtime path that
            // resolves interfaces handles missing entities gracefully). If the acquirer
            // wants the orphan ref gone, they can POST /workflows/{id}/reset-plan to
            // restore basePlan (which still references the deleted interface - same
            // outcome but with explicit user intent).
            if (workflow.isApplication()) {
                log.info("[InterfaceCascade] Skipping APPLICATION workflow {} (frozen acquired clone - orphan ref intentionally preserved)",
                    workflow.getId());
                continue;
            }

            ScrubOutcome outcome = scrubPlan(workflow.getPlan(), interfaceId);
            if (outcome.entriesRemoved() == 0 && outcome.edgesRemoved() == 0) {
                continue;
            }

            workflow.setPlan(outcome.plan());
            workflow.setUpdatedAt(Instant.now());
            workflowRepository.save(workflow);

            workflowsTouched++;
            entriesRemoved += outcome.entriesRemoved();
            edgesRemoved += outcome.edgesRemoved();
            touchedIds.add(workflow.getId().toString());

            log.info("[InterfaceCascade] Scrubbed interface {} from workflow {} (entries={} edges={})",
                interfaceId, workflow.getId(), outcome.entriesRemoved(), outcome.edgesRemoved());
        }

        return new CascadeResult(workflowsTouched, entriesRemoved, edgesRemoved, touchedIds);
    }

    /**
     * Pure function - given a plan map and an interfaceId, returns a new plan
     * with all matching {@code interfaces[]} entries removed and all
     * {@code edges[]} that reference the deleted interface's normalized key
     * dropped (only when no surviving interface entry shares the label).
     *
     * <p>Package-private so unit tests can exercise the scrub logic directly.
     */
    @SuppressWarnings("unchecked")
    ScrubOutcome scrubPlan(Map<String, Object> originalPlan, String interfaceId) {
        if (originalPlan == null) {
            return new ScrubOutcome(null, 0, 0);
        }

        Object interfacesObj = originalPlan.get("interfaces");
        if (!(interfacesObj instanceof List<?> interfacesList) || interfacesList.isEmpty()) {
            return new ScrubOutcome(originalPlan, 0, 0);
        }

        // Pass 1 - partition interfaces[] into kept vs removed; capture the
        // labels of the removed entries so we know which edges to scrub.
        List<Map<String, Object>> kept = new ArrayList<>(interfacesList.size());
        Set<String> removedLabels = new HashSet<>();
        Set<String> survivingLabels = new HashSet<>();
        int entriesRemoved = 0;

        for (Object element : interfacesList) {
            if (!(element instanceof Map<?, ?> mapElement)) continue;
            Map<String, Object> entry = (Map<String, Object>) mapElement;
            String entryId = stringValue(entry.get("id"));
            String entryLabel = stringValue(entry.get("label"));

            if (interfaceId.equals(entryId)) {
                entriesRemoved++;
                if (entryLabel != null && !entryLabel.isBlank()) {
                    removedLabels.add(entryLabel);
                }
            } else {
                kept.add(entry);
                if (entryLabel != null && !entryLabel.isBlank()) {
                    survivingLabels.add(entryLabel);
                }
            }
        }

        if (entriesRemoved == 0) {
            return new ScrubOutcome(originalPlan, 0, 0);
        }

        // Pass 2 - only strip edges whose interface key resolves to a label
        // that has NO surviving entry. This protects plans where two interfaces
        // happen to share a label (different ids, edge case).
        Set<String> edgeKeysToStrip = new HashSet<>();
        for (String label : removedLabels) {
            if (survivingLabels.contains(label)) continue;
            String key = LabelNormalizer.interfaceKey(label);
            if (key != null) {
                edgeKeysToStrip.add(key);
            }
        }

        Object edgesObj = originalPlan.get("edges");
        List<Map<String, Object>> newEdges;
        int edgesRemoved = 0;
        if (edgesObj instanceof List<?> edgesList && !edgesList.isEmpty() && !edgeKeysToStrip.isEmpty()) {
            newEdges = new ArrayList<>(edgesList.size());
            for (Object edgeElement : edgesList) {
                if (!(edgeElement instanceof Map<?, ?> mapEdge)) {
                    newEdges.add((Map<String, Object>) edgeElement);
                    continue;
                }
                Map<String, Object> edge = (Map<String, Object>) mapEdge;
                String to = stripPort(stringValue(edge.get("to")));
                String from = stripPort(stringValue(edge.get("from")));
                if (edgeKeysToStrip.contains(to) || edgeKeysToStrip.contains(from)) {
                    edgesRemoved++;
                    continue;
                }
                newEdges.add(edge);
            }
        } else {
            newEdges = null; // signal: no edge change
        }

        // Build a shallow copy so the original plan reference (which JPA may
        // have hydrated) is not mutated in place - the caller relies on
        // setPlan(...) being a fresh map for JSONB dirty-checking.
        Map<String, Object> newPlan = new java.util.LinkedHashMap<>(originalPlan);
        newPlan.put("interfaces", kept);
        if (newEdges != null) {
            newPlan.put("edges", newEdges);
        }

        return new ScrubOutcome(newPlan, entriesRemoved, edgesRemoved);
    }

    private static String stringValue(Object o) {
        return o == null ? null : Objects.toString(o, null);
    }

    /**
     * Edge endpoints can carry a port suffix (e.g. {@code interface:foo:default}).
     * Strip it so equality matches the bare interface key.
     */
    private static String stripPort(String key) {
        if (key == null) return null;
        int firstColon = key.indexOf(':');
        if (firstColon < 0) return key;
        int secondColon = key.indexOf(':', firstColon + 1);
        return secondColon < 0 ? key : key.substring(0, secondColon);
    }

    /**
     * Cascade summary returned to the caller (and serialized in the REST
     * response) - useful for observability and for the agent help text that
     * tells the LLM how many plans were touched by a delete.
     */
    public record CascadeResult(
        int workflowsTouched,
        int interfaceEntriesRemoved,
        int edgesRemoved,
        List<String> workflowIds
    ) {}

    /**
     * Outcome of a single-plan scrub. The returned {@code plan} is a SHALLOW
     * copy of the input: only the {@code interfaces} and {@code edges} keys
     * point at fresh lists; every other plan key (triggers, mcps, cores, …)
     * still aliases the original. Callers MUST NOT mutate sibling lists on
     * the returned plan or it will bleed back into the cached entity plan.
     */
    record ScrubOutcome(Map<String, Object> plan, int entriesRemoved, int edgesRemoved) {}
}
