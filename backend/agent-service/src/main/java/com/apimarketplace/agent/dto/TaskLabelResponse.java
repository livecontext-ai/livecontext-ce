package com.apimarketplace.agent.dto;

import com.apimarketplace.agent.domain.TaskLabelEntity;

/** Wire shape of a board label ({@link TaskLabelEntity}). */
public record TaskLabelResponse(String id, String name, String color) {

    public static TaskLabelResponse from(TaskLabelEntity e) {
        return new TaskLabelResponse(e.getId().toString(), e.getName(), e.getColor());
    }
}
