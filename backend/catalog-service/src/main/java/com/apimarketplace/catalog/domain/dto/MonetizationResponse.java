package com.apimarketplace.catalog.domain.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * DTO for monetization response
 */
public record MonetizationResponse(
    UUID id,
    UUID apiToolId,
    String monetizationType, // FREEMIUM, PAID
    String planName, // BASIC, PRO, ULTRA, MEGA
    Integer rateLimitRequests,
    String rateLimitPeriod,
    Integer freeRequests,
    String freeRequestsType, // "per-user" or "global" for FREEMIUM
    Integer mauValue,
    BigDecimal pricePerMau,
    Integer calls, // Number of calls per MAU
    Integer quota,
    BigDecimal price,
    BigDecimal overusageCost,
    Boolean hardLimit,
    Long createdAt,
    Long updatedAt
) {}
