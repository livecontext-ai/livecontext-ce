package com.apimarketplace.orchestrator.services.interfaces;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Utility for extracting interface-related data from WorkflowPlan.
 * Extracted from InterfaceSnapshotService (which moved to interface-service).
 */
@Component
public class InterfacePlanExtractor {

    private static final Logger logger = LoggerFactory.getLogger(InterfacePlanExtractor.class);

    /**
     * Extract unique interface UUIDs from the workflow plan.
     */
    @SuppressWarnings("unchecked")
    public Set<UUID> extractInterfaceIds(WorkflowPlan plan) {
        Set<UUID> interfaceIds = new HashSet<>();
        Map<String, Object> originalPlan = plan.getOriginalPlan();

        Map<String, UUID> labelToUuidMap = new HashMap<>();

        // Strategy 1: Extract from interfaces[] array
        if (originalPlan != null && originalPlan.containsKey("interfaces")) {
            Object interfacesObj = originalPlan.get("interfaces");
            if (interfacesObj instanceof List<?> interfacesList) {
                for (Object item : interfacesList) {
                    if (item instanceof Map<?, ?> interfaceMap) {
                        Object idObj = interfaceMap.get("id");
                        Object labelObj = interfaceMap.get("label");
                        if (idObj != null) {
                            UUID uuid = tryParseUuid(idObj.toString());
                            if (uuid != null) {
                                interfaceIds.add(uuid);
                                if (labelObj != null) {
                                    String normalizedLabel = LabelNormalizer.normalizeLabel(labelObj.toString());
                                    labelToUuidMap.put(normalizedLabel, uuid);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Strategy 2: Extract from edges[] where "to" starts with "interface:"
        if (originalPlan != null && originalPlan.containsKey("edges")) {
            Object edgesObj = originalPlan.get("edges");
            if (edgesObj instanceof List<?> edgesList) {
                for (Object item : edgesList) {
                    if (item instanceof Map<?, ?> edgeMap) {
                        Object toObj = edgeMap.get("to");
                        if (toObj != null) {
                            String toRef = toObj.toString();
                            if (toRef.startsWith("interface:")) {
                                String interfaceLabel = toRef.substring("interface:".length());
                                UUID uuid = labelToUuidMap.get(interfaceLabel);
                                if (uuid != null) {
                                    interfaceIds.add(uuid);
                                } else {
                                    UUID parsed = tryParseUuid(interfaceLabel);
                                    if (parsed != null) interfaceIds.add(parsed);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Strategy 3 (deprecated): triggers[].interfaceIds[]
        if (originalPlan != null && originalPlan.containsKey("triggers")) {
            Object triggersObj = originalPlan.get("triggers");
            if (triggersObj instanceof List<?> triggersList) {
                for (Object item : triggersList) {
                    if (item instanceof Map<?, ?> triggerMap) {
                        Object ifaceIdsObj = triggerMap.get("interfaceIds");
                        if (ifaceIdsObj instanceof List<?> ifaceIdsList) {
                            for (Object idObj : ifaceIdsList) {
                                if (idObj != null) {
                                    UUID uuid = tryParseUuid(idObj.toString());
                                    if (uuid != null) interfaceIds.add(uuid);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Strategy 4 (deprecated): mcps[].interfaceIds[]
        if (originalPlan != null && originalPlan.containsKey("mcps")) {
            Object mcpsObj = originalPlan.get("mcps");
            if (mcpsObj instanceof List<?> mcpsList) {
                for (Object item : mcpsList) {
                    if (item instanceof Map<?, ?> mcpMap) {
                        Object ifaceIdsObj = mcpMap.get("interfaceIds");
                        if (ifaceIdsObj instanceof List<?> ifaceIdsList) {
                            for (Object idObj : ifaceIdsList) {
                                if (idObj != null) {
                                    UUID uuid = tryParseUuid(idObj.toString());
                                    if (uuid != null) interfaceIds.add(uuid);
                                }
                            }
                        }
                    }
                }
            }
        }

        logger.debug("Extracted interface IDs from plan: {}", interfaceIds);
        return interfaceIds;
    }

    /**
     * Extract variable mappings for each interface from the workflow plan.
     */
    @SuppressWarnings("unchecked")
    public Map<UUID, Map<String, String>> extractMappingsFromPlan(WorkflowPlan plan) {
        Map<UUID, Map<String, String>> result = new HashMap<>();
        Map<String, Object> originalPlan = plan.getOriginalPlan();
        if (originalPlan == null || !originalPlan.containsKey("interfaces")) {
            return result;
        }

        Object interfacesObj = originalPlan.get("interfaces");
        if (!(interfacesObj instanceof List<?> interfacesList)) {
            return result;
        }

        for (Object item : interfacesList) {
            if (!(item instanceof Map<?, ?> interfaceMap)) continue;

            Object idObj = interfaceMap.get("id");
            if (idObj == null) continue;

            UUID uuid = tryParseUuid(idObj.toString());
            if (uuid == null) continue;

            Object mappingObj = interfaceMap.get("variableMapping");
            if (mappingObj instanceof Map<?, ?> rawMapping) {
                Map<String, String> mapping = new HashMap<>();
                for (Map.Entry<?, ?> entry : rawMapping.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        mapping.put(entry.getKey().toString(), entry.getValue().toString());
                    }
                }
                if (!mapping.isEmpty()) {
                    result.put(uuid, mapping);
                }
            }
        }

        return result;
    }

    /**
     * Extract action mappings for each interface from the workflow plan.
     */
    @SuppressWarnings("unchecked")
    public Map<UUID, Map<String, String>> extractActionMappingsFromPlan(WorkflowPlan plan) {
        Map<UUID, Map<String, String>> result = new HashMap<>();
        Map<String, Object> originalPlan = plan.getOriginalPlan();
        if (originalPlan == null || !originalPlan.containsKey("interfaces")) {
            return result;
        }

        Object interfacesObj = originalPlan.get("interfaces");
        if (!(interfacesObj instanceof List<?> interfacesList)) {
            return result;
        }

        for (Object item : interfacesList) {
            if (!(item instanceof Map<?, ?> interfaceMap)) continue;

            Object idObj = interfaceMap.get("id");
            if (idObj == null) continue;

            UUID uuid = tryParseUuid(idObj.toString());
            if (uuid == null) continue;

            Object mappingObj = interfaceMap.get("actionMapping");
            if (mappingObj instanceof Map<?, ?> rawMapping) {
                Map<String, String> mapping = new HashMap<>();
                for (Map.Entry<?, ?> entry : rawMapping.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        String selectorKey = entry.getKey().toString().trim();
                        if (selectorKey.length() >= 2 &&
                            ((selectorKey.startsWith("'") && selectorKey.endsWith("'")) ||
                             (selectorKey.startsWith("\"") && selectorKey.endsWith("\"")))) {
                            selectorKey = selectorKey.substring(1, selectorKey.length() - 1);
                        }
                        mapping.put(selectorKey, entry.getValue().toString());
                    }
                }
                if (!mapping.isEmpty()) {
                    result.put(uuid, mapping);
                }
            }
        }

        return result;
    }

    private UUID tryParseUuid(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
