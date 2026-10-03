package com.apimarketplace.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;

import java.net.URI;
import java.nio.file.Files;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LC-001 / LC-022: how a bridge dispatch is signed. The bridge verifies both signatures and derives
 * the unrestricted toolset ONLY from the signed provider id, so these decide both "can the call
 * reach the bridge at all" and "does it get a host shell".
 */
@DisplayName("BridgeDispatchSigning")
class BridgeDispatchSigningTest {

    private static final String SECRET = "test-gateway-hmac-key-for-unit-tests";
    private static final URI URI_EXECUTE = URI.create("http://10.0.9.4:8093/api/bridge/execute");

    @Test
    @DisplayName("unrestricted only for a platform ADMIN, on a deployment that allows host tools, outside API mode")
    void providerIdMatrix() {
        assertThat(BridgeDispatchSigning.providerIdFor(true, "ADMIN,USER", false)).isEqualTo("bridge-unrestricted");
        assertThat(BridgeDispatchSigning.providerIdFor(true, "USER", false)).isEqualTo("bridge-client");
        assertThat(BridgeDispatchSigning.providerIdFor(true, null, false)).isEqualTo("bridge-client");
        assertThat(BridgeDispatchSigning.providerIdFor(true, "ADMIN", true)).isEqualTo("bridge-client");
        assertThat(BridgeDispatchSigning.providerIdFor(false, "ADMIN", false)).isEqualTo("bridge-client");
    }

    @Test
    @DisplayName("signedJsonEntity: v1 over the identity headers AND the body signature over the exact bytes returned")
    void signsHeadersAndExactBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-ID", "tenant-1");
        headers.set("X-Organization-ID", "org_7");
        headers.set("X-Organization-Role", "OWNER");

        HttpEntity<byte[]> entity = BridgeDispatchSigning.signedJsonEntity(
            Map.of("prompt", "hi", "tenantId", "tenant-1"), headers, SECRET, "bridge-client", "POST", URI_EXECUTE);

        String ts = headers.getFirst(InternalGatewaySigner.HEADER_TIMESTAMP);
        assertThat(headers.getFirst(InternalGatewaySigner.HEADER_SECRET))
            .isEqualTo(InternalGatewaySigner.sign("bridge-client", "tenant-1", "org_7", ts, SECRET));
        assertThat(headers.getFirst(BridgeRequestSignature.HEADER)).isEqualTo(BridgeRequestSignature.sign(
            SECRET, "POST", "/api/bridge/execute", null, headers::get, ts, entity.getBody()));
        assertThat(new String(entity.getBody())).contains("\"prompt\":\"hi\"");
    }

    @Test
    @DisplayName("a blank secret leaves the dispatch unsigned instead of signing with an empty key")
    void blankSecretLeavesUnsigned() {
        HttpHeaders headers = new HttpHeaders();
        BridgeDispatchSigning.signedJsonEntity(Map.of(), headers, "", "bridge-client", "POST", URI_EXECUTE);
        assertThat(headers.containsKey(InternalGatewaySigner.HEADER_SECRET)).isFalse();
        assertThat(headers.containsKey(BridgeRequestSignature.HEADER)).isFalse();
    }

    @Test
    @DisplayName("the unrestricted provider id literal equals the bridge's JS constant (drift is silent)")
    void literalMatchesBridge() throws Exception {
        String js = Files.readString(BridgeRequestSignatureParityTest.locate("mcp/bridge/lib/gatewayAuth.mjs"));
        assertThat(js).contains("export const UNRESTRICTED_PROVIDER_ID = '"
            + BridgeDispatchSigning.UNRESTRICTED_PROVIDER_ID + "';");
    }
}
