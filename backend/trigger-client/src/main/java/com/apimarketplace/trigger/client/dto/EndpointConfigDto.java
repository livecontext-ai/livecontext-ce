package com.apimarketplace.trigger.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EndpointConfigDto(int maxPerUser, long currentCount) {
}
