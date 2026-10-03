package com.apimarketplace.common.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.net.URI;

/**
 * Signs a dispatch to the agent bridge ({@code POST /api/bridge/execute}), LC-001 / LC-022.
 *
 * <p>The bridge (mcp/bridge/lib/bridgeSecurity.mjs) refuses any {@code /api/bridge/**} request
 * without a valid gateway HMAC (401), checks the body-bound {@link BridgeRequestSignature} against
 * the raw bytes it received, refuses a replayed signature, and refuses (403) a body whose
 * {@code tenantId}/{@code organizationId} differs from the signed headers. It runs every verified
 * request RESTRICTED (platform MCP tools only, empty cwd, no repo/shell tools) unless it was signed
 * with {@link #UNRESTRICTED_PROVIDER_ID} AND body-bound. The provider id is inside both
 * signatures, so the privilege cannot be claimed without the shared secret, and no request body
 * field can grant it.
 *
 * <p>Both Java bridge callers (conversation-service {@code BridgeClient}, agent-service
 * {@code SubAgentBridgeClient}) go through {@link #signedJsonEntity}. The JS twin constant lives
 * in {@code mcp/bridge/lib/gatewayAuth.mjs}; the literals are pinned equal by tests on both sides
 * (a divergence is silent: dispatch succeeds and the run is simply restricted).
 */
public final class BridgeDispatchSigning {

    /** Provider id of an ordinary (restricted) bridge dispatch. */
    public static final String PROVIDER_ID = "bridge-client";

    /** Provider id that unlocks the unrestricted toolset on the bridge. */
    public static final String UNRESTRICTED_PROVIDER_ID = "bridge-unrestricted";

    /**
     * Serialises exactly like RestTemplate's default JSON converter did before the body had to be
     * signed (Spring's builder defaults), so the bridge receives the same JSON as before.
     */
    private static final ObjectMapper MAPPER = Jackson2ObjectMapperBuilder.json().build();

    private BridgeDispatchSigning() {}

    /**
     * Choose the provider id for one dispatch (LC-022). Unrestricted requires ALL of:
     * <ul>
     *   <li>the deployment allowing host tools at all ({@code conversation.bridge.host-tools-enabled});</li>
     *   <li>the requesting user being a platform administrator ({@code ADMIN} in the gateway-injected
     *       {@code X-User-Roles}, the same notion {@link AdminRoleGuard} uses everywhere else).
     *       Every other user, and every run with no user roles in scope (scheduled or async
     *       dispatch), stays restricted;</li>
     *   <li>the request not being in restricted "API mode" ({@code claimsRestricted}: the
     *       model-execution-link marker, which may only tighten).</li>
     * </ul>
     */
    public static String providerIdFor(boolean hostToolsEnabled, String userRoles, boolean claimsRestricted) {
        return hostToolsEnabled && AdminRoleGuard.isAdmin(userRoles) && !claimsRestricted
                ? UNRESTRICTED_PROVIDER_ID
                : PROVIDER_ID;
    }

    /**
     * Serialise {@code body}, then stamp the v1 gateway headers AND the body-bound
     * {@link BridgeRequestSignature} over the exact bytes returned. Call it once
     * {@code X-User-ID} / {@code X-Organization-ID} / {@code X-Organization-Role} are final on
     * {@code headers}: both signatures bind them. A blank secret leaves the request unsigned
     * (launchers that run the bridge with enforcement off).
     *
     * <p>Callers MUST send the returned entity's byte[] body as-is (RestTemplate's
     * ByteArrayHttpMessageConverter does), since any re-serialisation would break the hash.
     */
    public static HttpEntity<byte[]> signedJsonEntity(Object body, HttpHeaders headers, String gatewaySecretKey,
                                                      String providerId, String method, URI uri) {
        byte[] bytes;
        try {
            bytes = MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot serialise bridge request", e);
        }
        InternalGatewaySigner.stamp(headers, providerId, gatewaySecretKey);
        if (gatewaySecretKey != null && !gatewaySecretKey.isBlank()) {
            headers.set(BridgeRequestSignature.HEADER, BridgeRequestSignature.sign(
                    gatewaySecretKey, method, uri.getRawPath(), uri.getRawQuery(), headers::get,
                    headers.getFirst(InternalGatewaySigner.HEADER_TIMESTAMP), bytes));
        }
        return new HttpEntity<>(bytes, headers);
    }
}
