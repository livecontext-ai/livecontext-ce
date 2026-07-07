package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.ModelExecutionLinkEntity;
import com.apimarketplace.agent.domain.ModelExecutionLinkScope;
import com.apimarketplace.agent.service.ModelExecutionLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ModelExecutionLinkController")
@ExtendWith(MockitoExtension.class)
class ModelExecutionLinkControllerTest {

    @Mock private ModelExecutionLinkService service;

    private ModelExecutionLinkController controller;

    @BeforeEach
    void setUp() {
        controller = new ModelExecutionLinkController(service);
    }

    @Test
    @DisplayName("upsert is rejected with 403 for a non-admin and never touches the service")
    void upsertDeniedForNonAdmin() {
        ResponseEntity<?> response = controller.upsert("USER", Map.of(
            "billedProvider", "anthropic", "billedModel", "claude-opus-4-8", "executionProvider", "codex"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, never()).upsert(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("upsert as admin persists the link and echoes it back (enabled defaults to true, scope defaults to ALL)")
    void upsertAsAdminPersists() {
        when(service.upsert(eq("anthropic"), eq("claude-opus-4-8"), eq("codex"), eq("gpt-5.3-codex"),
                eq(ModelExecutionLinkScope.ALL), eq(true)))
            .thenReturn(link("anthropic", "claude-opus-4-8", "codex", "gpt-5.3-codex", ModelExecutionLinkScope.ALL, true));

        ResponseEntity<?> response = controller.upsert("ADMIN", Map.of(
            "billedProvider", "anthropic", "billedModel", "claude-opus-4-8",
            "executionProvider", "codex", "executionModel", "gpt-5.3-codex"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) response.getBody()).get("billedProvider")).isEqualTo("anthropic");
        assertThat(((Map<?, ?>) response.getBody()).get("scope")).isEqualTo("ALL");
        // scope absent from the body ⇒ ALL passed to the service.
        verify(service).upsert("anthropic", "claude-opus-4-8", "codex", "gpt-5.3-codex", ModelExecutionLinkScope.ALL, true);
    }

    @Test
    @DisplayName("upsert parses a surface scope (CHAT) from the body and passes it to the service; the echo carries it back")
    void upsertParsesSurfaceScope() {
        when(service.upsert(eq("anthropic"), eq("claude-opus-4-8"), eq("openrouter"), eq("anthropic/claude-3.5-sonnet"),
                eq(ModelExecutionLinkScope.CHAT), eq(true)))
            .thenReturn(link("anthropic", "claude-opus-4-8", "openrouter", "anthropic/claude-3.5-sonnet",
                ModelExecutionLinkScope.CHAT, true));

        ResponseEntity<?> response = controller.upsert("ADMIN", Map.of(
            "billedProvider", "anthropic", "billedModel", "claude-opus-4-8",
            "executionProvider", "openrouter", "executionModel", "anthropic/claude-3.5-sonnet",
            "scope", "chat")); // case-insensitive

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) response.getBody()).get("scope")).isEqualTo("CHAT");
        verify(service).upsert("anthropic", "claude-opus-4-8", "openrouter", "anthropic/claude-3.5-sonnet",
            ModelExecutionLinkScope.CHAT, true);
    }

    @Test
    @DisplayName("upsert with an unknown scope returns 400 before reaching the service")
    void upsertUnknownScopeIs400() {
        ResponseEntity<?> response = controller.upsert("ADMIN", Map.of(
            "billedProvider", "anthropic", "billedModel", "claude-opus-4-8",
            "executionProvider", "codex", "scope", "not-a-surface"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).upsert(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("upsert ACCEPTS a non-bridge execution provider (openrouter) - the target may be any provider now")
    void upsertAcceptsNonBridgeExecutionProvider() {
        when(service.upsert(eq("anthropic"), eq("claude-opus-4-8"), eq("openrouter"), eq("anthropic/claude-3.5-sonnet"),
                eq(ModelExecutionLinkScope.ALL), eq(true)))
            .thenReturn(link("anthropic", "claude-opus-4-8", "openrouter", "anthropic/claude-3.5-sonnet",
                ModelExecutionLinkScope.ALL, true));

        ResponseEntity<?> response = controller.upsert("ADMIN", Map.of(
            "billedProvider", "anthropic", "billedModel", "claude-opus-4-8",
            "executionProvider", "openrouter", "executionModel", "anthropic/claude-3.5-sonnet"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) response.getBody()).get("executionProvider")).isEqualTo("openrouter");
    }

    @Test
    @DisplayName("upsert with a blank required field returns 400 before reaching the service")
    void upsertBlankFieldIs400() {
        ResponseEntity<?> response = controller.upsert("ADMIN", Map.of(
            "billedProvider", "anthropic", "billedModel", "claude-opus-4-8", "executionProvider", " "));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).upsert(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("list and delete are also admin-gated (403 for a non-admin, service untouched)")
    void listAndDeleteDeniedForNonAdmin() {
        assertThat(controller.list("USER").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.delete("USER", "anthropic", "claude-opus-4-8", "ALL").getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, never()).list();
        verify(service, never()).delete(any(), any(), any());
    }

    @Test
    @DisplayName("delete parses the scope path segment and returns 204 when the scoped link existed, 404 when it did not")
    void deleteStatuses() {
        when(service.delete("anthropic", "claude-opus-4-8", ModelExecutionLinkScope.CHAT)).thenReturn(true);
        when(service.delete("openai", "gpt-5", ModelExecutionLinkScope.ALL)).thenReturn(false);

        assertThat(controller.delete("ADMIN", "anthropic", "claude-opus-4-8", "CHAT").getStatusCode())
            .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.delete("ADMIN", "openai", "gpt-5", "ALL").getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        verify(service).delete("anthropic", "claude-opus-4-8", ModelExecutionLinkScope.CHAT);
    }

    @Test
    @DisplayName("delete with an unknown scope returns 400 before reaching the service")
    void deleteUnknownScopeIs400() {
        assertThat(controller.delete("ADMIN", "anthropic", "claude-opus-4-8", "bogus").getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).delete(any(), any(), any());
    }

    @Test
    @DisplayName("query-param delete addresses a billed model containing '/' (undeletable via the path form) and returns 204")
    void deleteByParamsHandlesSlashModel() {
        when(service.delete("openrouter", "meta-llama/llama-3.3-70b", ModelExecutionLinkScope.ALL)).thenReturn(true);

        ResponseEntity<?> response = controller.deleteByParams(
            "ADMIN", "openrouter", "meta-llama/llama-3.3-70b", "ALL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(service).delete("openrouter", "meta-llama/llama-3.3-70b", ModelExecutionLinkScope.ALL);
    }

    @Test
    @DisplayName("query-param delete defaults an absent scope to ALL and shares the path form's semantics (403 non-admin, 400 unknown scope, 404 missing)")
    void deleteByParamsSharedSemantics() {
        when(service.delete("openai", "gpt-5", ModelExecutionLinkScope.ALL)).thenReturn(false);

        assertThat(controller.deleteByParams("USER", "openai", "gpt-5", null).getStatusCode())
            .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.deleteByParams("ADMIN", "openai", "gpt-5", "bogus").getStatusCode())
            .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.deleteByParams("ADMIN", "openai", "gpt-5", null).getStatusCode())
            .isEqualTo(HttpStatus.NOT_FOUND);
        // Absent scope reached the service as the ALL wildcard, not null.
        verify(service).delete("openai", "gpt-5", ModelExecutionLinkScope.ALL);
    }

    @Test
    @DisplayName("upsert with a non-boolean 'enabled' (string \"true\") returns 400 instead of silently persisting a DISABLED link")
    void upsertStringEnabledIs400() {
        ResponseEntity<?> response = controller.upsert("ADMIN", Map.of(
            "billedProvider", "anthropic", "billedModel", "claude-opus-4-8",
            "executionProvider", "codex", "enabled", "true"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).upsert(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("upsert with a numeric 'enabled' returns 400 (strict JSON boolean)")
    void upsertNumericEnabledIs400() {
        ResponseEntity<?> response = controller.upsert("ADMIN", Map.of(
            "billedProvider", "anthropic", "billedModel", "claude-opus-4-8",
            "executionProvider", "codex", "enabled", 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).upsert(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("upsert with 'enabled': false passes false through (an explicit boolean is honored)")
    void upsertExplicitFalseEnabled() {
        when(service.upsert(eq("anthropic"), eq("claude-opus-4-8"), eq("codex"), any(),
                eq(ModelExecutionLinkScope.ALL), eq(false)))
            .thenReturn(link("anthropic", "claude-opus-4-8", "codex", null, ModelExecutionLinkScope.ALL, false));

        ResponseEntity<?> response = controller.upsert("ADMIN", Map.of(
            "billedProvider", "anthropic", "billedModel", "claude-opus-4-8",
            "executionProvider", "codex", "enabled", false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) response.getBody()).get("enabled")).isEqualTo(false);
    }

    @Test
    @DisplayName("upsert with a non-string optional field (numeric scope) returns 400 via the optionalString type check")
    void upsertNumericScopeIs400() {
        ResponseEntity<?> response = controller.upsert("ADMIN", Map.of(
            "billedProvider", "anthropic", "billedModel", "claude-opus-4-8",
            "executionProvider", "codex", "scope", 3));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).upsert(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("list as admin returns the links mapped to a stable JSON shape including the scope")
    void listAsAdmin() {
        when(service.list()).thenReturn(List.of(
            link("anthropic", "claude-opus-4-8", "codex", "gpt-5.3-codex", ModelExecutionLinkScope.WORKFLOW, true)));

        ResponseEntity<?> response = controller.list("ADMIN");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(List.class);
        Object first = ((List<?>) response.getBody()).get(0);
        assertThat(((Map<?, ?>) first).get("executionProvider")).isEqualTo("codex");
        assertThat(((Map<?, ?>) first).get("scope")).isEqualTo("WORKFLOW");
    }

    private static ModelExecutionLinkEntity link(String bp, String bm, String ep, String em,
                                                 ModelExecutionLinkScope scope, boolean enabled) {
        ModelExecutionLinkEntity e = new ModelExecutionLinkEntity();
        e.setBilledProvider(bp);
        e.setBilledModel(bm);
        e.setExecutionProvider(ep);
        e.setExecutionModel(em);
        e.setScope(scope);
        e.setEnabled(enabled);
        return e;
    }
}
