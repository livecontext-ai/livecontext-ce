package com.apimarketplace.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Body of {@code POST /api/ce-link/register}. The {@code install_id} field
 * must match the {@code X-LiveContext-Install-Id} header on the request -
 * mismatch produces 400 INVALID_REQUEST (audit I-Q16 r3, doc §3.2).
 *
 * <p>The {@code label} field is user-friendly only (displayed in the cloud
 * /settings/cloud-account list); defaults to "CE install" when blank.
 */
public record CeLinkRegisterRequest(
        @NotNull UUID installId,
        @NotBlank @Size(max = 32) String ceVersion,
        @Size(max = 128) String label
) {}
