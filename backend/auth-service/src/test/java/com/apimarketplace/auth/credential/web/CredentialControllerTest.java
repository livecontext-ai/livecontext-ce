package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.CredentialModels.Credential;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialEnvironment;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialStatus;
import com.apimarketplace.auth.credential.domain.CredentialModels.CredentialType;
import com.apimarketplace.auth.credential.domain.CredentialRenameRefusedException;
import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.util.RequestParameterExtractor;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CredentialController")
class CredentialControllerTest {

    @Mock
    private CredentialService credentialService;

    @Mock
    private TenantResolver tenantResolver;

    @Mock
    private RequestParameterExtractor extractor;

    @Mock
    private HttpServletRequest request;

    @InjectMocks
    private CredentialController controller;

    @Test
    @DisplayName("clear-default maps only-credential default guard to 409 instead of leaking a 500")
    void clearDefaultOnlyCredentialReturnsConflict() {
        when(tenantResolver.resolveOrNull(request)).thenReturn("tenant-1");
        when(tenantResolver.resolveOrgId(request)).thenReturn(null);
        doThrow(new IllegalStateException("Cannot remove default status: this is the only credential for github"))
                .when(credentialService).clearDefault("tenant-1", null, 42L);

        ResponseEntity<Void> response = controller.clearDefault(42L, request, null);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        verify(tenantResolver).validate("tenant-1");
        verify(credentialService).clearDefault("tenant-1", null, 42L);
    }

    @Test
    @DisplayName("llm-mode: switches the route and returns the credential with its mode visible and its secrets stripped")
    void llmModeSwitchesTheRoute() {
        Map<String, Object> body = Map.of("mode", "proxy");
        when(tenantResolver.resolveOrNull(request)).thenReturn("tenant-1");
        when(tenantResolver.resolveOrgId(request)).thenReturn("org-1");
        Credential switched = new Credential(
                42L, "tenant-1", "org-1", "Anthropic", "llm_anthropic", CredentialType.API_Key,
                CredentialEnvironment.Production, CredentialStatus.active, null,
                Map.of("api_key", "sk-ant-secret", "mode", "proxy"),
                List.of(), List.of(), "tenant-1", null, true, null,
                Instant.parse("2026-09-17T10:00:00Z"), Instant.parse("2026-09-17T10:00:00Z"));
        when(credentialService.setLlmKeyModeForScope(42L, "tenant-1", "org-1", "proxy"))
                .thenReturn(Optional.of(switched));

        ResponseEntity<?> response = controller.setLlmKeyMode(42L, request, body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Credential returned = (Credential) response.getBody();
        // The switch is what the settings page reads back; the key never travels.
        assertThat(returned.credentialData()).containsEntry("mode", "proxy");
        assertThat(returned.credentialData()).doesNotContainKey("api_key");
        verify(tenantResolver).validate("tenant-1");
    }

    @Test
    @DisplayName("llm-mode: a mode outside no_proxy/proxy is 400, never written")
    void llmModeRejectsUnknownMode() {
        when(tenantResolver.resolveOrNull(request)).thenReturn("tenant-1");
        when(tenantResolver.resolveOrgId(request)).thenReturn("org-1");

        ResponseEntity<?> response = controller.setLlmKeyMode(42L, request, Map.of("mode", "sometimes"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(credentialService, never()).setLlmKeyModeForScope(any(), any(), any(), any());
    }

    @Test
    @DisplayName("llm-mode: no active workspace is 400, and a non-string mode is 400 - nothing reaches the service")
    void llmModeWorkspaceAndShapeGuards() {
        when(tenantResolver.resolveOrNull(request)).thenReturn("tenant-1");
        when(tenantResolver.resolveOrgId(request)).thenReturn(null);
        assertThat(controller.setLlmKeyMode(42L, request, Map.of("mode", "proxy")).getStatusCode().value()).isEqualTo(400);

        when(tenantResolver.resolveOrgId(request)).thenReturn("org-1");
        Map<String, Object> shaped = new HashMap<>();
        shaped.put("mode", Map.of("value", "proxy"));
        assertThat(controller.setLlmKeyMode(42L, request, shaped).getStatusCode().value()).isEqualTo(400);
        Map<String, Object> missing = new HashMap<>();
        assertThat(controller.setLlmKeyMode(42L, request, missing).getStatusCode().value()).isEqualTo(400);

        verify(credentialService, never()).setLlmKeyModeForScope(any(), any(), any(), any());
    }

    @Test
    @DisplayName("llm-mode: out of scope is 404, and a non-LLM credential is 400 (the service refuses it)")
    void llmModeScopeAndKindGuards() {
        when(tenantResolver.resolveOrNull(request)).thenReturn("tenant-1");
        when(tenantResolver.resolveOrgId(request)).thenReturn("org-1");
        when(credentialService.setLlmKeyModeForScope(42L, "tenant-1", "org-1", "no_proxy"))
                .thenReturn(Optional.empty());
        when(credentialService.setLlmKeyModeForScope(43L, "tenant-1", "org-1", "no_proxy"))
                .thenThrow(new IllegalArgumentException("Only an LLM provider key has a route to switch"));

        assertThat(controller.setLlmKeyMode(42L, request, Map.of("mode", "no_proxy")).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.setLlmKeyMode(43L, request, Map.of("mode", "no_proxy")).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("rename returns the renamed credential with its secrets stripped")
    void renameReturnsRenamedCredentialWithoutSecrets() {
        Map<String, Object> body = Map.of("name", "Gmail (work)");
        when(tenantResolver.resolveOrNull(request)).thenReturn("tenant-1");
        when(tenantResolver.resolveOrgId(request)).thenReturn("org-1");
        when(credentialService.renameCredentialForScope(42L, "tenant-1", "org-1", "Gmail (work)"))
                .thenReturn(Optional.of(credential("Gmail (work)")));

        ResponseEntity<?> response = controller.renameCredential(42L, request, body);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isInstanceOf(Credential.class);
        Credential body2 = (Credential) response.getBody();
        assertThat(body2.name()).isEqualTo("Gmail (work)");
        // withoutSecrets: the access_token must never reach the public response.
        assertThat(body2.credentialData()).doesNotContainKey("access_token");
        verify(tenantResolver).validate("tenant-1");
    }

    @Test
    @DisplayName("rename maps an out-of-scope credential to 404 rather than leaking its existence")
    void renameOutOfScopeReturnsNotFound() {
        Map<String, Object> body = Map.of("name", "Hijacked");
        when(tenantResolver.resolveOrNull(request)).thenReturn("tenant-1");
        when(tenantResolver.resolveOrgId(request)).thenReturn("org-1");
        when(credentialService.renameCredentialForScope(42L, "tenant-1", "org-1", "Hijacked"))
                .thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.renameCredential(42L, request, body);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("rename maps an invalid name to 400 instead of leaking a 500")
    void renameInvalidNameReturnsBadRequest() {
        Map<String, Object> body = Map.of("name", " ");
        when(tenantResolver.resolveOrNull(request)).thenReturn("tenant-1");
        when(tenantResolver.resolveOrgId(request)).thenReturn("org-1");

        ResponseEntity<?> response = controller.renameCredential(42L, request, body);

        // Rejected before the service is reached, so an invalid NAME can never be
        // confused with the other IllegalArgumentException the service can raise.
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(credentialService, never())
                .renameCredentialForScope(anyLong(), anyString(), anyString(), anyString());
        // The reason travels in the body, so the caller learns WHICH rule it hit,
        // and the code is what it branches on.
        assertThat(String.valueOf(response.getBody())).contains("cannot be empty");
        assertThat(String.valueOf(response.getBody())).contains("invalid_name");
    }

    @Test
    @DisplayName("rename maps a duplicate name to 409, never a silent overwrite")
    void renameDuplicateNameReturnsConflict() {
        Map<String, Object> body = Map.of("name", "Gmail");
        when(tenantResolver.resolveOrNull(request)).thenReturn("tenant-1");
        when(tenantResolver.resolveOrgId(request)).thenReturn("org-1");
        when(credentialService.renameCredentialForScope(42L, "tenant-1", "org-1", "Gmail"))
                .thenThrow(new CredentialRenameRefusedException(
                        CredentialRenameRefusedException.Reason.DUPLICATE_NAME,
                        "'Gmail' already identifies another credential of this credential's owner"));

        ResponseEntity<?> response = controller.renameCredential(42L, request, body);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        // The service's message reaches the caller, and it echoes the name they typed rather
        // than anything about the contending row (which can live in a workspace they cannot
        // open). Asserting the exact prose would only re-read this test's own stub.
        assertThat(String.valueOf(response.getBody())).contains("'Gmail'");
        // Callers branch on the code, never on the prose.
        assertThat(String.valueOf(response.getBody())).contains("duplicate_name");
    }

    @Test
    @DisplayName("rename rejects a non-string name instead of coercing it into the row")
    void renameNonStringNameReturnsBadRequest() {
        Map<String, Object> body = Map.of("name", Map.of("a", 1));
        when(tenantResolver.resolveOrNull(request)).thenReturn("tenant-1");
        when(tenantResolver.resolveOrgId(request)).thenReturn("org-1");

        ResponseEntity<?> response = controller.renameCredential(42L, request, body);

        // toString() coercion would have persisted the literal "{a=1}" as the name.
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(credentialService, never())
                .renameCredentialForScope(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("rename rejects a body with no name at all")
    void renameMissingNameReturnsBadRequest() {
        Map<String, Object> body = Map.of();
        when(tenantResolver.resolveOrNull(request)).thenReturn("tenant-1");
        when(tenantResolver.resolveOrgId(request)).thenReturn("org-1");

        ResponseEntity<?> response = controller.renameCredential(42L, request, body);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(credentialService, never())
                .renameCredentialForScope(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("rename maps a name-is-identity refusal to 422 with its own code")
    void renameNameIsIdentityReturnsUnprocessable() {
        Map<String, Object> body = Map.of("name", "Company SMTP");
        when(tenantResolver.resolveOrNull(request)).thenReturn("tenant-1");
        when(tenantResolver.resolveOrgId(request)).thenReturn("org-1");
        when(credentialService.renameCredentialForScope(42L, "tenant-1", "org-1", "Company SMTP"))
                .thenThrow(new CredentialRenameRefusedException(
                        CredentialRenameRefusedException.Reason.NAME_IS_IDENTITY,
                        "This credential carries no integration"));

        ResponseEntity<?> response = controller.renameCredential(42L, request, body);

        // Distinct from the duplicate-name 409: the UI has to explain a different thing.
        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(String.valueOf(response.getBody())).contains("name_is_identity");
    }

    @Test
    @DisplayName("rename answers 400 in its own words when there is no active workspace")
    void renameWithoutWorkspaceReturnsBadRequest() {
        Map<String, Object> body = Map.of("name", "Gmail");
        when(tenantResolver.resolveOrNull(request)).thenReturn("tenant-1");
        when(tenantResolver.resolveOrgId(request)).thenReturn(null);

        ResponseEntity<?> response = controller.renameCredential(42L, request, body);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(String.valueOf(response.getBody())).contains("workspace_required");
        // TenantResolver's internal "required after V261" wording must not be relayed.
        assertThat(String.valueOf(response.getBody())).doesNotContain("V261");
        verify(credentialService, never())
                .renameCredentialForScope(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("rejects a name longer than the column with invalid_name, without calling the service")
    void renameOverlongNameReturnsBadRequest() {
        when(tenantResolver.resolveOrNull(request)).thenReturn("tenant-1");
        when(tenantResolver.resolveOrgId(request)).thenReturn("org-1");

        // 256 characters: auth.credentials.name is VARCHAR(255), so letting this through
        // turns a typo into a database error the user reads as "something went wrong".
        Map<String, Object> body = Map.of("name", "x".repeat(256));
        ResponseEntity<?> response = controller.renameCredential(42L, request, body);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) response.getBody()).get("code")).isEqualTo("invalid_name");
        verify(credentialService, never()).renameCredentialForScope(anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("create rejects an over-long name with the same 400 as rename, not a 500")
    void createOverlongNameReturnsBadRequest() {
        when(tenantResolver.resolveOrNull(request)).thenReturn("tenant-1");
        when(tenantResolver.resolveOrgId(request)).thenReturn("org-1");
        when(extractor.getString(anyMap(), eq("name"))).thenReturn("x".repeat(256));

        ResponseEntity<?> response = controller.createCredential(request, Map.of("name", "x".repeat(256)));

        // auth-service registers no @ControllerAdvice, so an IllegalArgumentException escaping
        // here would surface as a 500: the user would read "something went wrong" on create and
        // a precise message on rename, for one identical rule.
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(((Map<?, ?>) response.getBody()).get("code")).isEqualTo("invalid_name");
        verify(credentialService, never()).createCredential(
                anyString(), anyString(), anyString(), anyString(), any(), any(),
                anyString(), anyMap(), anyList(), anyList(), anyString(), anyString());
    }

    private Credential credential(String name) {
        return new Credential(
                42L,
                "tenant-1",
                "org-1",
                name,
                "gmail",
                CredentialType.OAuth2,
                CredentialEnvironment.Production,
                CredentialStatus.active,
                "Test credential",
                Map.of("access_token", "super-secret"),
                List.of("email"),
                List.of(),
                "tenant-1",
                "icon",
                true,
                null,
                Instant.parse("2026-05-04T10:00:00Z"),
                Instant.parse("2026-05-05T10:00:00Z"));
    }
}
