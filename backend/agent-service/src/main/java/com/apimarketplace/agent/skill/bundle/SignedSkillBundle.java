package com.apimarketplace.agent.skill.bundle;

/**
 * Signed skill-bundle envelope delivered to CE instances.
 *
 * <p>{@code payloadBase64} is the base64-encoded canonical JSON bytes; the signature
 * was computed over the raw decoded bytes, so CE must verify against
 * {@code Base64.decode(payloadBase64)} - NOT the string itself. Mirrors
 * {@code com.apimarketplace.agent.catalog.bundle.SignedBundle}.
 */
public record SignedSkillBundle(
        long version,
        int schemaVersion,
        String checksum,
        String signature,
        String signingKeyId,
        String issuer,
        int skillCount,
        int rawBytesSize,
        String payloadBase64
) {}
