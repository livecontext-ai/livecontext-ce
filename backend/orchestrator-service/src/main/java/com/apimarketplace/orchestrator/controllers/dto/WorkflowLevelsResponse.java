package com.apimarketplace.orchestrator.controllers.dto;

import java.util.List;
import java.util.Map;

public class WorkflowLevelsResponse {

    private final List<Map<String, Object>> levels;
    private final int maxLevel;
    private final int totalSteps;
    private final boolean hasLoops;
    private final boolean hasConditionalLogic;

    public WorkflowLevelsResponse(List<Map<String, Object>> levels,
                                  int maxLevel,
                                  int totalSteps,
                                  boolean hasLoops,
                                  boolean hasConditionalLogic) {
        this.levels = levels;
        this.maxLevel = maxLevel;
        this.totalSteps = totalSteps;
        this.hasLoops = hasLoops;
        this.hasConditionalLogic = hasConditionalLogic;
    }

    public List<Map<String, Object>> getLevels() {
        return levels;
    }

    public int getMaxLevel() {
        return maxLevel;
    }

    public int getTotalSteps() {
        return totalSteps;
    }

    public boolean isHasLoops() {
        return hasLoops;
    }

    public boolean isHasConditionalLogic() {
        return hasConditionalLogic;
    }
}
