package com.apimarketplace.orchestrator.services.impl;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.services.TypeCastingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The wire contract for a credential chosen at run time.
 *
 * <p>The node decides and the catalog enforces, so these two fields are the whole
 * conversation between them. A name that does not travel means the catalog
 * resolves the account default and the step publishes to the wrong place; a
 * strict flag that does not travel means the catalog keeps its forgiving
 * fallback and does exactly the same thing. Both failures are silent and both
 * look like a working run, which is why they are pinned here rather than left to
 * the integration.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogToolsGateway - run-time credential markers")
class CatalogToolsGatewayCredentialSelectorTest {

    @Mock private RestTemplate restTemplate;
    @Mock private TypeCastingService typeCastingService;
    @Mock private CrudToolExecutor crudToolExecutor;

    private CatalogToolsGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new CatalogToolsGateway(
                restTemplate, "http://localhost:8081", typeCastingService, crudToolExecutor);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> forwardedPayload(Map<String, Object> markers) {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), any(Class.class)))
                .thenReturn(ResponseEntity.ok(null));

        gateway.executeTool(new ToolRef("instagram/publish", 1), Map.of("caption", "hi"),
                "tenant-1", markers);

        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), any(Class.class));
        return captor.getValue().getBody();
    }

    private static Map<String, Object> markers(Object... kv) {
        Map<String, Object> markers = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            markers.put((String) kv[i], kv[i + 1]);
        }
        return markers;
    }

    @Test
    @DisplayName("a named account and its strict flag both reach the catalog")
    void nameAndStrictAreForwarded() {
        Map<String, Object> payload = forwardedPayload(markers(
                "__credentialSource__", "user",
                "__selectedCredentialName__", "Client B",
                "__credentialSelectionStrict__", true));

        assertThat(payload).containsEntry("credentialSource", "user");
        assertThat(payload).containsEntry("selectedCredentialName", "Client B");
        assertThat(payload).containsEntry("credentialSelectionStrict", true);
    }

    @Test
    @DisplayName("a step with no run-time choice sends neither field")
    void staticStepSendsNeither() {
        // The no-regression half: every existing workflow takes this branch, and an
        // unexpected field here would change what the catalog resolves for all of them.
        Map<String, Object> payload = forwardedPayload(markers(
                "__credentialSource__", "user",
                "__selectedCredentialId__", 42L));

        assertThat(payload).containsEntry("selectedCredentialId", 42L);
        assertThat(payload).doesNotContainKeys("selectedCredentialName", "credentialSelectionStrict");
    }

    @Test
    @DisplayName("a name is never forwarded on the platform branch")
    void nameIsNotForwardedForPlatform() {
        // A name identifies one of the OWNER's own credentials, so it means nothing
        // against the platform pool. Forwarding it there would state a choice the
        // catalog cannot honour, on a branch that bills differently.
        Map<String, Object> payload = forwardedPayload(markers(
                "__credentialSource__", "platform",
                "__platformCredentialId__", 7L,
                "__selectedCredentialName__", "Client B"));

        assertThat(payload).containsEntry("platformCredentialId", 7L);
        assertThat(payload).doesNotContainKey("selectedCredentialName");
    }

    @Test
    @DisplayName("a refusal surfaces the sentence written for the reader, not the raw envelope")
    void refusalSurfacesTheMessage() {
        // Left to the generic handler the step error reads
        // "422 UNPROCESSABLE_ENTITY: {json blob}" and the sentence is buried in it,
        // which defeats the point of having written one.
        String body = "{\"success\":false,\"error\":\"CREDENTIAL_SELECTION_UNRESOLVED\","
                + "\"message\":\"no active credential of this integration is named 'Client Z'\"}";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), any(Class.class)))
                .thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "Unprocessable Entity", new org.springframework.http.HttpHeaders(),
                        body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.UTF_8));

        com.apimarketplace.orchestrator.services.interfaces.ExecutionResult result =
                gateway.executeTool(new ToolRef("instagram/publish", 1), Map.of(), "tenant-1",
                        markers("__credentialSource__", "user",
                                "__selectedCredentialName__", "Client Z",
                                "__credentialSelectionStrict__", true));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Client Z");
        assertThat(result.getErrorMessage()).doesNotContain("UNPROCESSABLE_ENTITY");
    }

    @Test
    @DisplayName("a refusal whose body will not parse still says something rather than nothing")
    void unparseableRefusalStillSurfaces() {
        // Tolerance on purpose: a step that failed for a reason nobody can read is
        // worse than one that failed with a raw body. The error CODE is what marks it
        // as a credential refusal, so a truncated body still gets the right label.
        String body = "{\"error\":\"CREDENTIAL_SELECTION_UNRESOLVED\", truncated";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), any(Class.class)))
                .thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "Unprocessable Entity", new org.springframework.http.HttpHeaders(),
                        body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.UTF_8));

        com.apimarketplace.orchestrator.services.interfaces.ExecutionResult result =
                gateway.executeTool(new ToolRef("instagram/publish", 1), Map.of(), "tenant-1",
                        markers("__credentialSource__", "user"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isNotBlank();
    }

    @Test
    @DisplayName("some OTHER 422 is not labelled a credential refusal")
    void unrelated422IsNotMislabelled() {
        // The catalog returns 422 from one place today. The first future one would
        // otherwise reach the step as a credential-selection error and send the reader
        // looking at an account name that is not the problem.
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), any(Class.class)))
                .thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                        org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                        "Unprocessable Entity", new org.springframework.http.HttpHeaders(),
                        "{\"error\":\"SOMETHING_ELSE\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.UTF_8));

        com.apimarketplace.orchestrator.services.interfaces.ExecutionResult result =
                gateway.executeTool(new ToolRef("instagram/publish", 1), Map.of(), "tenant-1",
                        markers("__credentialSource__", "user"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errors()).noneSatisfy(error ->
                assertThat(error).containsEntry("type", "credential_selection_error"));
    }

    @Test
    @DisplayName("a run-time account choice on a TABLE operation refuses rather than being dropped")
    void strictSelectionOnACrudToolRefuses() {
        // A crud/ tool reads this workspace's own data and authenticates against no
        // provider, and the short-circuit that routes it discards the markers
        // entirely. Dropped silently, the step ended GREEN on a choice that was never
        // applied - the shape the whole feature guards against.
        com.apimarketplace.orchestrator.services.interfaces.ExecutionResult result =
                gateway.executeTool(new ToolRef("crud/read-row", 1), Map.of(), "tenant-1",
                        markers("__credentialSource__", "user",
                                "__selectedCredentialName__", "Client B",
                                "__credentialSelectionStrict__", true));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("table operation");
        // Both overloads: the gateway calls the 4-argument one (it forwards the run markers).
        verify(crudToolExecutor, never()).execute(anyString(), anyMap(), anyString());
        verify(crudToolExecutor, never()).execute(anyString(), anyMap(), anyString(), any());
    }

    @Test
    @DisplayName("an ordinary table operation is untouched by any of this")
    void ordinaryCrudStillRuns() {
        // The no-regression half: every table step today takes this branch.
        // The gateway forwards the run markers, so the table step goes through the 4-argument overload.
        when(crudToolExecutor.execute(anyString(), anyMap(), anyString(), any()))
                .thenReturn(new com.apimarketplace.orchestrator.services.interfaces.ExecutionResult(
                        true, Map.of("rows", List.of()), List.of(), List.of()));

        com.apimarketplace.orchestrator.services.interfaces.ExecutionResult result =
                gateway.executeTool(new ToolRef("crud/read-row", 1), Map.of(), "tenant-1",
                        markers("__credentialSource__", "user"));

        assertThat(result.isSuccess()).isTrue();
    }
}
