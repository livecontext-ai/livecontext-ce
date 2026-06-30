package com.apimarketplace.auth.web.dto;

import com.apimarketplace.auth.domain.OrganizationMemberQuotaLimit;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * PR11c - wire shape for the per-member quota cap CRUD endpoints.
 *
 * <p>NULL on any cap dimension = "no cap on that dim" (unchanged from
 * the entity). The PUT endpoint uses the SAME shape for partial updates:
 * passing {@code period_credits=null} means "clear the credits cap"
 * (the row stays for audit continuity of the other dims; use DELETE
 * to remove the whole row).
 *
 * <p>{@code createdByUserId} + timestamps are read-only on the wire;
 * the server fills them from the request context on PUT.
 */
public record MemberQuotaDto(
        UUID orgId,
        Long userId,
        BigDecimal periodCredits,
        Long periodStorageBytes,
        Long periodLlmTokens,
        String resetCadence,
        Long createdByUserId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static MemberQuotaDto from(OrganizationMemberQuotaLimit entity) {
        return new MemberQuotaDto(
                entity.getOrgId(),
                entity.getUserId(),
                entity.getPeriodCredits(),
                entity.getPeriodStorageBytes(),
                entity.getPeriodLlmTokens(),
                entity.getResetCadence(),
                entity.getCreatedByUserId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
