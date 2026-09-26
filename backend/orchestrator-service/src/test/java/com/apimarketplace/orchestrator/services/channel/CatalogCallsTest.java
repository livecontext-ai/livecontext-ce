package com.apimarketplace.orchestrator.services.channel;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.Outcome;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import com.apimarketplace.orchestrator.services.interfaces.ToolsGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("CatalogCalls")
class CatalogCallsTest {

    private static final ToolRef TOOL = new ToolRef("slack/slack-post-message", 1);

    private ToolsGateway gateway;
    private ObjectProvider<ToolsGateway> provider;
    private CatalogCalls calls;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        gateway = mock(ToolsGateway.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gateway);
        calls = new CatalogCalls(provider);
    }

    @Test
    @DisplayName("pins the credential strictly, so a missing credential is never swapped for the default one")
    @SuppressWarnings("unchecked")
    void pinsTheCredentialStrictly() {
        when(gateway.executeTool(any(), any(), anyString(), any()))
                .thenReturn(new ExecutionResult(true, Map.of("ok", true), List.of(), List.of()));
        ArgumentCaptor<Map<String, Object>> markers = ArgumentCaptor.forClass(Map.class);

        calls.call("slack", TOOL, Map.of("channel", "C1"), "tenant", 7L);

        // Without the strict marker the catalog softens an unresolvable id into the integration's
        // default credential, and the message goes out as another account than the row names.
        verify(gateway).executeTool(eq(TOOL), eq(Map.of("channel", "C1")), eq("tenant"), markers.capture());
        assertThat(markers.getValue())
                .containsEntry("__credentialSource__", "user")
                .containsEntry("__selectedCredentialId__", 7L)
                .containsEntry("__credentialSelectionStrict__", true);
    }

    @Test
    @DisplayName("a refused call carries the catalog's own message")
    void refusalCarriesTheCatalogMessage() {
        when(gateway.executeTool(any(), any(), anyString(), any())).thenReturn(new ExecutionResult(false, null,
                List.of(Map.of("message", "Credential 7 not found")), List.of()));

        Outcome<ExecutionResult> outcome = calls.call("slack", TOOL, Map.of(), "tenant", 7L);

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.error()).isEqualTo("Credential 7 not found");
    }

    @Test
    @DisplayName("a refusal without a message still names the provider and the tool")
    void refusalWithoutMessageNamesTheTool() {
        when(gateway.executeTool(any(), any(), anyString(), any()))
                .thenReturn(new ExecutionResult(false, null, List.of(), List.of()));

        assertThat(calls.call("slack", TOOL, Map.of(), "tenant", 7L).error())
                .isEqualTo("Slack refused the slack/slack-post-message call.");
    }

    @Test
    @DisplayName("an exception becomes a failure sentence, never a thrown error")
    void exceptionBecomesAFailure() {
        when(gateway.executeTool(any(), any(), anyString(), any())).thenThrow(new IllegalStateException("timeout"));

        Outcome<ExecutionResult> outcome = calls.call("discord", TOOL, Map.of(), "tenant", 7L);

        assertThat(outcome.error()).isEqualTo("Discord call failed: timeout");
    }

    @Test
    @DisplayName("no gateway in this deployment is a failure, not a null pointer")
    void noGatewayIsAFailure() {
        when(provider.getIfAvailable()).thenReturn(null);

        assertThat(calls.call("teams", TOOL, Map.of(), "tenant", 7L).error()).contains("unavailable");
    }

    @Test
    @DisplayName("listOf reads a list wherever the projection put it, and skips what is not an object")
    void listOfReadsEveryWrapper() {
        Map<String, Object> row = Map.of("id", "1");

        assertThat(CatalogCalls.listOf(List.of(row, "noise"))).containsExactly(row);
        assertThat(CatalogCalls.listOf(Map.of("data", List.of(row)))).containsExactly(row);
        assertThat(CatalogCalls.listOf(Map.of("items", List.of(row)))).containsExactly(row);
        assertThat(CatalogCalls.listOf(Map.of("result", List.of(row)))).containsExactly(row);
        assertThat(CatalogCalls.listOf(Map.of("value", List.of(row)))).containsExactly(row);
        assertThat(CatalogCalls.listOf(Map.of("other", List.of(row)))).isEmpty();
        assertThat(CatalogCalls.listOf(null)).isEmpty();
    }
}
