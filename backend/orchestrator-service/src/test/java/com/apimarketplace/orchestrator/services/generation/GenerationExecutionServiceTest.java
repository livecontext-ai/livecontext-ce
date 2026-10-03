package com.apimarketplace.orchestrator.services.generation;

import com.apimarketplace.orchestrator.services.generation.GenerationExecutionService.GenerationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GenerationExecutionService}.
 *
 * <p>What is pinned here is the shape of the call into catalog-service, because
 * that shape is what decides who gets charged and how much: the run scope on the
 * headers is what attaches the credit debit to this run and this step, and the
 * ABSENCE of any quantity in the body is what keeps the billable size derived on
 * the catalog side rather than chosen by a workflow author.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GenerationExecutionService")
class GenerationExecutionServiceTest {

    @Mock private RestTemplate restTemplate;

    @SuppressWarnings("rawtypes")
    @Captor private ArgumentCaptor<HttpEntity> entityCaptor;

    private org.springframework.web.client.RestTemplate readRestTemplate;
    private GenerationExecutionService service;

    @BeforeEach
    void setUp() {
        // Two templates on purpose: the generation call needs a 25 minute read
        // window, and a plain read must never inherit it.
        readRestTemplate = org.mockito.Mockito.mock(org.springframework.web.client.RestTemplate.class);
        service = new GenerationExecutionService(restTemplate, readRestTemplate, "http://catalog:8081",
                GATEWAY_SECRET);
    }

    private static final String GATEWAY_SECRET = "test-gateway-secret-key-0123456789";

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubResponse(Map<String, Object> body) {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(Map.class))).thenReturn((ResponseEntity) ResponseEntity.ok(body));
    }

    private static Map<String, Object> successBody() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("file", Map.of("path", "tenant-1/generated/clip.mp4"));
        data.put("model", "seedance-2.0-fast");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedBody() {
        return (Map<String, Object>) entityCaptor.getValue().getBody();
    }

    @Test
    @DisplayName("posts model + params to the catalog generation endpoint and returns its data")
    void postsAndReturnsData() {
        stubResponse(successBody());

        GenerationResult result = service.generate("tenant-1", "run-1", "agent:make_clip",
                "seedance-2.0-fast", Map.of("prompt", "a boat", "duration_seconds", 10), null, null);

        assertTrue(result.success());
        assertEquals("seedance-2.0-fast", result.data().get("model"));

        verify(restTemplate).exchange(
                eq("http://catalog:8081/api/internal/catalog/generation/execute"),
                eq(HttpMethod.POST), entityCaptor.capture(), eq(Map.class));

        Map<String, Object> body = capturedBody();
        assertEquals("seedance-2.0-fast", body.get("model"));
        assertEquals(Map.of("prompt", "a boat", "duration_seconds", 10), body.get("params"));
    }

    @Test
    @DisplayName("never sends a quantity or a price: the billable size stays derived on the catalog side")
    void neverSendsAQuantity() {
        stubResponse(successBody());

        service.generate("tenant-1", "run-1", "agent:make_clip", "seedance-2.0-fast",
                Map.of("duration_seconds", 10), null, null);

        verify(restTemplate).exchange(any(String.class), eq(HttpMethod.POST),
                entityCaptor.capture(), eq(Map.class));

        Map<String, Object> body = capturedBody();
        // A quantity that travels from the caller is a quantity the caller can set
        // to zero, which would be a free generation.
        assertFalse(body.containsKey("quantity"));
        assertFalse(body.containsKey("generationQuantity"));
        assertFalse(body.containsKey("credits"));
        assertEquals(Map.of("model", "seedance-2.0-fast", "params", Map.of("duration_seconds", 10)), body);
    }

    @Test
    @DisplayName("scopes the credit debit to the RUN and the step via the billing headers")
    void sendsRunScopedBillingHeaders() {
        stubResponse(successBody());

        service.generate("tenant-1", "run-1", "agent:make_clip", "seedance-2.0-fast", Map.of(), null, null);

        verify(restTemplate).exchange(any(String.class), eq(HttpMethod.POST),
                entityCaptor.capture(), eq(Map.class));

        var headers = entityCaptor.getValue().getHeaders();
        assertEquals("tenant-1", headers.getFirst("X-User-ID"));
        assertEquals("RUN", headers.getFirst("X-Lc-Billing-Scope-Kind"));
        assertEquals("run-1", headers.getFirst("X-Lc-Billing-Scope-Id"));
        assertEquals("agent:make_clip", headers.getFirst("X-Lc-Billing-Step-Id"));
    }

    @Test
    @DisplayName("forwards an explicit credential source, and omits it when the author made no choice")
    void forwardsCredentialSourceOnlyWhenChosen() {
        stubResponse(successBody());

        service.generate("tenant-1", "run-1", "agent:make_clip", "m", Map.of(), "user", null);
        verify(restTemplate).exchange(any(String.class), eq(HttpMethod.POST),
                entityCaptor.capture(), eq(Map.class));
        assertEquals("user", capturedBody().get("credential_source"));

        service.generate("tenant-1", "run-1", "agent:make_clip", "m", Map.of(), null, null);
        verify(restTemplate, org.mockito.Mockito.times(2)).exchange(any(String.class), eq(HttpMethod.POST),
                entityCaptor.capture(), eq(Map.class));
        assertFalse(capturedBody().containsKey("credential_source"));
    }

    @Test
    @DisplayName("forwards WHICH own key the node pinned, and omits it when none was pinned")
    void forwardsThePinnedCredentialIdOnlyWhenPinned() {
        stubResponse(successBody());

        service.generate("tenant-1", "run-1", "agent:make_clip", "m", Map.of(), "user", 42L);
        verify(restTemplate).exchange(any(String.class), eq(HttpMethod.POST),
                entityCaptor.capture(), eq(Map.class));
        assertEquals(42L, capturedBody().get("credential_id"));

        // Absent means the account's default key for the provider. Writing a
        // null into the body instead would be a value the reader has to
        // interpret rather than the plain absence it is.
        service.generate("tenant-1", "run-1", "agent:make_clip", "m", Map.of(), "user", null);
        verify(restTemplate, org.mockito.Mockito.times(2)).exchange(any(String.class), eq(HttpMethod.POST),
                entityCaptor.capture(), eq(Map.class));
        assertFalse(capturedBody().containsKey("credential_id"));
    }

    @Test
    @DisplayName("a refusal from the catalog is returned verbatim, so the accepted values reach the author")
    void refusalIsReturnedVerbatim() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", "model 'x' does not accept 'voice'. It accepts: prompt, duration_seconds");
        stubResponse(body);

        GenerationResult result = service.generate("tenant-1", "run-1", "agent:make_clip", "x", Map.of(), null, null);

        assertFalse(result.success());
        assertEquals("model 'x' does not accept 'voice'. It accepts: prompt, duration_seconds", result.error());
    }

    @Test
    @DisplayName("a FAILED generation keeps the link to the asset it was already charged for")
    void aFailureCarriesItsRecoverableAsset() {
        // Billing commits before the asset is fetched and stored, so a
        // transient fetch failure leaves a paid-for asset sitting at the
        // provider behind a short-lived link. The generation endpoint puts that
        // link on the FAILING response deliberately; reading only `error` threw
        // it away, and a charged asset then became unrecoverable with nothing
        // but a sentence to show for it.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", "The asset could not be stored.");
        body.put("data", Map.of("asset_url", "https://provider.example/tmp/abc123"));
        stubResponse(body);

        GenerationResult result = service.generate("tenant-1", "run-1", "agent:make_clip", "x", Map.of(), null, null);

        assertFalse(result.success());
        assertEquals("https://provider.example/tmp/abc123", result.recoverableAssetUrl());
    }

    @Test
    @DisplayName("an ordinary refusal carries no asset link, because nothing was produced or charged")
    void anOrdinaryRefusalHasNothingToRecover() {
        // The counterpart that stops the assertion above from passing on any
        // failure: a refusal happens BEFORE the provider is called, so offering
        // a recovery link there would tell the reader to chase something that
        // was never made.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", "PLATFORM_NOT_AVAILABLE: this model is not sold on the platform key.");
        stubResponse(body);

        GenerationResult result = service.generate("tenant-1", "run-1", "agent:make_clip", "x", Map.of(), null, null);

        assertFalse(result.success());
        assertNull(result.recoverableAssetUrl());
    }

    @Test
    @DisplayName("an unreachable service does NOT claim the call was free, because it may have run")
    void anUnreachableServiceDoesNotDenyTheCharge() {
        // The request left this process. A read timeout, a reset or a dropped
        // connection says the ANSWER did not come back, never that nothing
        // happened: the generation may be running and billing commits on the
        // catalog side. A message implying it did not run invites a re-run,
        // which pays for a second one.
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(new org.springframework.web.client.ResourceAccessException("Read timed out"));

        GenerationResult result = service.generate("tenant-1", "run-1", "agent:make_clip", "x", Map.of(), null, null);

        assertFalse(result.success());
        assertTrue(result.error().contains("unknown whether this generation ran"), result.error());
        assertTrue(result.error().contains("already been charged"), result.error());
    }

    @Test
    @DisplayName("an upstream refusal keeps ITS OWN words, not just the HTTP number")
    void anUpstreamRefusalKeepsItsSentence() {
        // Every refusal this path can carry names its cause and its remedy; an
        // HTTP status names neither. The generation endpoint answers 200 today,
        // so this branch is rare, but its sibling controller answers a real 402:
        // if they converge, replacing the body would delete every one of those
        // sentences at once.
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.PAYMENT_REQUIRED, "Payment Required",
                        org.springframework.http.HttpHeaders.EMPTY,
                        "PLATFORM_NOT_AVAILABLE: no price is published. Use your own provider key."
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.UTF_8));

        GenerationResult result = service.generate("tenant-1", "run-1", "agent:make_clip", "x", Map.of(), null, null);

        assertFalse(result.success());
        assertTrue(result.error().contains("PLATFORM_NOT_AVAILABLE"), result.error());
        assertTrue(result.error().contains("your own provider key"), result.error());
    }

    @Test
    @DisplayName("a blank asset link is no link at all, so the reader is not sent after an empty URL")
    void aBlankAssetUrlIsNotOffered() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("error", "The asset could not be stored.");
        body.put("data", Map.of("asset_url", "   "));
        stubResponse(body);

        GenerationResult result = service.generate("tenant-1", "run-1", "agent:make_clip", "x", Map.of(), null, null);

        assertNull(result.recoverableAssetUrl());
    }

    @Test
    @DisplayName("a success with no data is a failure: there is nothing for the next node to use")
    void successWithoutDataIsAFailure() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        stubResponse(body);

        GenerationResult result = service.generate("tenant-1", "run-1", "agent:make_clip", "x", Map.of(), null, null);

        assertFalse(result.success());
        assertTrue(result.error().contains("no result"), result.error());
    }

    @Test
    @DisplayName("404 means generation is not enabled here, and says so instead of reporting a transport error")
    void notFoundReportsGenerationDisabled() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(Map.class))).thenThrow(HttpClientErrorException.create(
                        HttpStatus.NOT_FOUND, "Not Found", null, null, null));

        GenerationResult result = service.generate("tenant-1", "run-1", "agent:make_clip", "x", Map.of(), null, null);

        assertFalse(result.success());
        assertEquals(GenerationExecutionService.GENERATION_UNAVAILABLE_MESSAGE, result.error());
    }

    @Test
    @DisplayName("an unreachable catalog fails with the transport reason, never a silent empty result")
    void transportFailureIsReported() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.POST), any(HttpEntity.class),
                eq(Map.class))).thenThrow(new ResourceAccessException("connection refused"));

        GenerationResult result = service.generate("tenant-1", "run-1", "agent:make_clip", "x", Map.of(), null, null);

        assertFalse(result.success());
        assertTrue(result.error().contains("could not be reached"), result.error());
        assertTrue(result.data().isEmpty());
        assertNull(result.data().get("file"));
    }

    @Test
    @DisplayName("no run id (a preview call) sends no billing scope rather than an empty one")
    void noRunIdSendsNoScope() {
        stubResponse(successBody());

        service.generate("tenant-1", null, "agent:make_clip", "x", Map.of(), null, null);

        verify(restTemplate).exchange(any(String.class), eq(HttpMethod.POST),
                entityCaptor.capture(), eq(Map.class));
        var headers = entityCaptor.getValue().getHeaders();
        assertNull(headers.getFirst("X-Lc-Billing-Scope-Kind"));
        assertNull(headers.getFirst("X-Lc-Billing-Scope-Id"));
    }

    @Test
    @DisplayName("listing models uses the SHORT-timeout template, never the generation one")
    void listingDoesNotInheritTheLongWindow() {
        // generationRestTemplate carries a 25 minute read window sized for a
        // provider finishing a video, and its own comment says no other call may
        // inherit it. A stalled catalogue would otherwise hang the agent's help
        // request for twenty-five minutes instead of failing.
        org.mockito.Mockito.when(readRestTemplate.exchange(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(Map.class)))
            .thenReturn(org.springframework.http.ResponseEntity.ok(
                    Map.of("models", java.util.List.of(Map.of("model", "m")))));

        assertEquals(1, service.readModels().models().size());
        assertTrue(service.readModels().served());
        org.mockito.Mockito.verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("regression unsigned model listing: the read is signed v1+v2 and passes catalog's filter with accept-v1=false")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void listingIsSignedForCatalogsGatewayFilter() throws Exception {
        // /api/generation/models is not a catalog public path: unsigned, the filter answered 401
        // and readModels() reported "served, no models" on every cloud install.
        when(readRestTemplate.exchange(eq("http://catalog:8081/api/generation/models"), eq(HttpMethod.GET),
                entityCaptor.capture(), eq(Map.class)))
            .thenReturn((ResponseEntity) ResponseEntity.ok(Map.of("models", java.util.List.of(Map.of("model", "m")))));

        assertEquals(1, service.readModels().models().size());

        org.springframework.http.HttpHeaders sent = entityCaptor.getValue().getHeaders();
        assertEquals(GenerationExecutionService.MODELS_PROVIDER_ID, sent.getFirst("X-Provider-ID"));
        assertEquals(200, replayThroughCatalogFilter(sent, "/api/generation/models", false));
        // And with no headers at all (the pre-fix call), the same filter refuses it.
        assertEquals(401, replayThroughCatalogFilter(new org.springframework.http.HttpHeaders(),
                "/api/generation/models", true));
    }

    @Test
    @DisplayName("blank gateway secret (verification off): the listing goes out unsigned, as before")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void blankSecretListsUnsigned() {
        service = new GenerationExecutionService(restTemplate, readRestTemplate, "http://catalog:8081", "");
        when(readRestTemplate.exchange(any(String.class), eq(HttpMethod.GET), entityCaptor.capture(), eq(Map.class)))
            .thenReturn((ResponseEntity) ResponseEntity.ok(Map.of("models", java.util.List.of())));

        service.readModels();

        org.springframework.http.HttpHeaders sent = entityCaptor.getValue().getHeaders();
        assertNull(sent.getFirst("X-Gateway-Secret"));
        assertNull(sent.getFirst(com.apimarketplace.common.web.GatewaySignatureV2.HEADER));
    }

    /** Status catalog-service's GatewayAuthenticationFilter (its real public paths) answers. */
    private static int replayThroughCatalogFilter(org.springframework.http.HttpHeaders headers, String path,
                                                  boolean acceptV1) throws Exception {
        com.apimarketplace.common.web.GatewayFilterProperties props =
                new com.apimarketplace.common.web.GatewayFilterProperties();
        props.setSecretKey(GATEWAY_SECRET);
        props.setVerificationEnabled(true);
        // Mirrors catalog-service application.yml gateway.filter.public-paths: no /api/generation.
        props.setPublicPaths(java.util.List.of("/health", "/actuator", "/api/internal/", "/api/agent-tools",
                "/api/tools", "/api/catalog", "/api/v1", "/catalog/v1", "/api/tool-responses",
                "/api/mcp", "/api/tool-categories", "/api/apis"));
        var filter = new com.apimarketplace.common.web.GatewayAuthenticationFilter(props, acceptV1);
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", path);
        headers.forEach((name, values) -> values.forEach(v -> request.addHeader(name, v)));
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        filter.doFilter(request, response, new org.springframework.mock.web.MockFilterChain());
        return response.getStatus();
    }

    @Test
    @DisplayName("a 404 means generation is OFF here, which a reader may act on")
    void aNotFoundMeansGenerationIsOff() {
        org.mockito.Mockito.when(readRestTemplate.exchange(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(Map.class)))
            .thenThrow(new org.springframework.web.client.HttpClientErrorException(
                    org.springframework.http.HttpStatus.NOT_FOUND));

        assertTrue(service.readModels().models().isEmpty());
        assertFalse(service.readModels().served());
    }

    @Test
    @DisplayName("regression: an unreachable catalogue says NOTHING about the installation")
    void unreachableIsNotAbsent() {
        org.mockito.Mockito.when(readRestTemplate.exchange(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(org.springframework.http.HttpMethod.GET),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(Map.class)))
            .thenThrow(new org.springframework.web.client.ResourceAccessException("connection reset"));

        // Folded into "no models here", a three second blip tells the agent to
        // abandon a feature that works.
        assertTrue(service.readModels().models().isEmpty());
        assertNull(service.readModels().served());
    }
}
