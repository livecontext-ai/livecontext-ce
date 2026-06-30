package com.apimarketplace.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/ce-link/{installId}/heartbeat}. Sent by CE side
 * every few minutes (doc §3.5). {@code installId} comes from the URL path -
 * keeping it out of the body prevents path-vs-body mismatch attacks.
 */
public record CeLinkHeartbeatRequest(
        @NotBlank @Size(max = 32) String ceVersion
) {}
