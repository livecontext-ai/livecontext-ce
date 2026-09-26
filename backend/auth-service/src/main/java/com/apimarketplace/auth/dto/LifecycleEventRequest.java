package com.apimarketplace.auth.dto;

import java.util.Map;

/** Body of {@code POST /api/internal/auth/lifecycle/events}: an allow-listed event name and its facts. */
public record LifecycleEventRequest(String event, Map<String, Object> payload) {
}
