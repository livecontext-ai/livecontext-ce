package com.apimarketplace.agent.dto;

import com.apimarketplace.agent.domain.TaskStatusEntity;

/**
 * Wire shape of a configurable board column ({@link TaskStatusEntity}).
 * {@code isSystem} marks the seven built-ins (renamable, not deletable); the
 * frontend prefers an i18n label for those keys and falls back to {@code label}.
 */
public record TaskStatusResponse(
        String id,
        String key,
        String label,
        String category,
        int position,
        String color,
        Integer wipLimit,
        boolean isSystem,
        boolean hidden) {

    public static TaskStatusResponse from(TaskStatusEntity e) {
        return new TaskStatusResponse(
                e.getId().toString(),
                e.getKey(),
                e.getLabel(),
                e.getCategory(),
                e.getPosition(),
                e.getColor(),
                e.getWipLimit(),
                e.isSystem(),
                e.isHidden());
    }
}
