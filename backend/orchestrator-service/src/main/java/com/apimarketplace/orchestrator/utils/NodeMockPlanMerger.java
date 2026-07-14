package com.apimarketplace.orchestrator.utils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Mirrors the per-node {@code mock} blocks of a RUN's plan into the workflow's
 * current plan.
 *
 * <p>Why: in-run mock edits ("Use as mock output", inspector changes sent with a
 * step/re-fire) land in {@code run.plan} only, while every fire refreshes
 * {@code run.plan} FROM {@code workflow.plan}
 * ({@code ReusableTriggerService} / {@code WorkflowResumeService
 * .refreshPlanFromWorkflowDefinition}) and the editor reloads from
 * {@code workflow.plan} when leaving run mode. A mock that never reaches
 * {@code workflow.plan} is therefore wiped by the next plan refresh and gone
 * when the run closes. This merger gives in-run mock edits the same durable
 * home the workflow-assistant agent already writes to ({@code workflow.plan}),
 * keeping UI and agent behavior identical. Params and other node content stay
 * run-scoped by design - ONLY the {@code mock} key is mirrored.
 *
 * <p>Node identity: section + raw {@code label}. The accepted run plan is
 * topology-compatible with the run's frozen plan (a label change IS a node-id
 * change, rejected upstream by {@code PlanTopology}), so labels of shared nodes
 * match byte-for-byte; a workflow plan that drifted structurally in the
 * meantime simply yields no match for the drifted nodes. Entries with a
 * missing/blank label or a label duplicated within their section (either side)
 * are skipped defensively.
 *
 * <p>Merge semantics per matched node:
 * <ul>
 *   <li>run entry has a non-empty {@code mock} map → copied (deep) onto the
 *       workflow entry when different;</li>
 *   <li>run entry has an EMPTY {@code mock} map ({@code {}} is the documented
 *       "cleared" form) → the workflow entry's mock is removed;</li>
 *   <li>run entry has NO {@code mock} key → the workflow entry is left
 *       untouched. Absence is not a removal: a newer mock authored in
 *       {@code workflow.plan} (e.g. by the agent) must survive a stale run
 *       payload.</li>
 * </ul>
 */
public final class NodeMockPlanMerger {

    /** Plan sections whose node entries may carry a mock (triggers/notes never do). */
    private static final List<String> MOCK_SECTIONS =
            List.of("mcps", "agents", "cores", "tables", "interfaces");

    private static final String MOCK_KEY = "mock";
    private static final String LABEL_KEY = "label";
    private static final String ALIAS_KEY = "alias";

    private NodeMockPlanMerger() {}

    /**
     * @return a DEEP COPY of {@code workflowPlan} with the run plan's mock blocks
     *         applied, or {@code null} when nothing would change (caller skips the
     *         write). Never mutates either input.
     */
    public static Map<String, Object> mergedWorkflowPlanOrNull(
            Map<String, Object> runPlan, Map<String, Object> workflowPlan) {
        if (runPlan == null || workflowPlan == null) return null;

        @SuppressWarnings("unchecked")
        Map<String, Object> copy = (Map<String, Object>) deepCopy(workflowPlan);
        boolean changed = false;

        for (String section : MOCK_SECTIONS) {
            List<Map<String, Object>> runEntries = entriesOf(runPlan.get(section));
            List<Map<String, Object>> wfEntries = entriesOf(copy.get(section));
            if (runEntries.isEmpty() || wfEntries.isEmpty()) continue;

            Map<String, Map<String, Object>> wfByLabel = indexByUniqueLabel(wfEntries);
            Set<String> runDuplicates = duplicateLabels(runEntries);

            for (Map<String, Object> runEntry : runEntries) {
                if (!runEntry.containsKey(MOCK_KEY)) continue;
                String label = labelOf(runEntry);
                if (label == null || runDuplicates.contains(label)) continue;
                Map<String, Object> wfEntry = wfByLabel.get(label);
                if (wfEntry == null) continue;

                Object mockVal = runEntry.get(MOCK_KEY);
                if (!(mockVal instanceof Map<?, ?> mockMap)) continue;

                if (mockMap.isEmpty()) {
                    // {} is the documented "cleared mock" form - propagate as removal.
                    if (wfEntry.remove(MOCK_KEY) != null) changed = true;
                } else if (!Objects.equals(wfEntry.get(MOCK_KEY), mockVal)) {
                    wfEntry.put(MOCK_KEY, deepCopy(mockVal));
                    changed = true;
                }
            }
        }

        return changed ? copy : null;
    }

    private static List<Map<String, Object>> entriesOf(Object section) {
        if (!(section instanceof List<?> list)) return List.of();
        List<Map<String, Object>> entries = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> entry = (Map<String, Object>) map;
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * Node display identity, mirroring {@code WorkflowPlanParser}'s
     * {@code firstNonBlank(label, alias)}: legacy/agent-authored mcp entries may
     * carry only an {@code alias}, and their mocks execute like any other.
     */
    private static String labelOf(Map<String, Object> entry) {
        Object label = entry.get(LABEL_KEY);
        if (label instanceof String s && !s.isBlank()) return s;
        Object alias = entry.get(ALIAS_KEY);
        if (alias instanceof String s && !s.isBlank()) return s;
        return null;
    }

    /** Index by label, dropping labels that appear more than once (ambiguous match). */
    private static Map<String, Map<String, Object>> indexByUniqueLabel(List<Map<String, Object>> entries) {
        Map<String, Map<String, Object>> byLabel = new HashMap<>();
        Set<String> duplicates = duplicateLabels(entries);
        for (Map<String, Object> entry : entries) {
            String label = labelOf(entry);
            if (label != null && !duplicates.contains(label)) {
                byLabel.put(label, entry);
            }
        }
        return byLabel;
    }

    private static Set<String> duplicateLabels(List<Map<String, Object>> entries) {
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new HashSet<>();
        for (Map<String, Object> entry : entries) {
            String label = labelOf(entry);
            if (label != null && !seen.add(label)) duplicates.add(label);
        }
        return duplicates;
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>(map.size());
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(e.getKey(), deepCopy(e.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return value;
    }
}
