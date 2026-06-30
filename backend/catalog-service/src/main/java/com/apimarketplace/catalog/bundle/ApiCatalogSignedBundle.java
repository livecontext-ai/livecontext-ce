package com.apimarketplace.catalog.bundle;

/**
 * Signed API-catalog bundle envelope delivered to CE instances.
 *
 * <p>Unlike the LLM model bundle ({@code agent-service SignedBundle}), the API
 * catalog payload is large (600+ APIs with tools/parameters/responses), so the
 * canonical JSON is <b>gzipped before signing</b>: {@code payloadBase64} is the
 * base64 of the GZIP bytes, and {@code checksum}/{@code signature} were
 * computed over those same GZIP bytes. CE must therefore:
 * <ol>
 *   <li>base64-decode {@code payloadBase64},</li>
 *   <li>verify SHA-256 + Ed25519 against the decoded (still-gzipped) bytes,</li>
 *   <li>gunzip the verified bytes to obtain the canonical JSON.</li>
 * </ol>
 *
 * <p>{@code rawBytesSize} is the size of the UNCOMPRESSED canonical JSON -
 * useful for operators to gauge catalog size; verification never uses it.
 */
public record ApiCatalogSignedBundle(
        long version,
        int schemaVersion,
        String checksum,
        String signature,
        String signingKeyId,
        String issuer,
        int apiCount,
        int toolCount,
        long rawBytesSize,
        String payloadBase64
) {}
