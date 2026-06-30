package com.apimarketplace.orchestrator.execution.v2.engine;

import java.util.Map;

/**
 * A single trigger item to be executed through the workflow.
 */
public record TriggerItem(
    String itemId,
    int index,
    Map<String, Object> data
) {

    public static TriggerItem create(String itemId, int index, Map<String, Object> data) {
        return new TriggerItem(itemId, index, data);
    }
}
