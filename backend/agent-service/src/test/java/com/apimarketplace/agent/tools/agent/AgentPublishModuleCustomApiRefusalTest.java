package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.publication.client.PublicationClient;
import com.apimarketplace.publication.client.PublicationValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The {@code agent(action='publish')} tool must turn the custom-API 422 refusal into a
 * message an agent can ACT on through its own surface: which APIs block the share, and
 * the two ways out (drop the tools, or publish privately). A bare relay of the HTTP
 * status leaves the agent retrying a publish that can never succeed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("agent(action='publish') - custom-API refusal is rendered as actionable guidance")
class AgentPublishModuleCustomApiRefusalTest {

    @Mock private PublicationClient publicationClient;

    private AgentPublishModule module;

    private static final String TENANT = "tenant-1";
    private static final String ORG_ID = "org-77";
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID INTERFACE_ID = UUID.randomUUID();
    private static final String MESSAGE =
            "Custom APIs cannot be shared. This publication uses My Private API, which exists "
            + "only in your own account, so anyone installing it would get nodes that cannot run.";

    @BeforeEach
    void setUp() {
        module = new AgentPublishModule(publicationClient);
    }

    private ToolExecutionContext ctx() {
        return new ToolExecutionContext(TENANT, Map.of(), Map.of(), java.util.Set.of(),
                null, null, ORG_ID, null);
    }

    private ToolExecutionResult publishRefusedWith(Map<String, Object> body) {
        when(publicationClient.publishAgent(any(), eq(TENANT), eq(ORG_ID)))
                .thenThrow(new PublicationValidationException(
                        (String) body.get("error"), (String) body.get("message"), body, null));
        return module.execute("publish",
                Map.of("agent_id", AGENT_ID.toString(), "title", "X",
                        "interface_id", INTERFACE_ID.toString()),
                TENANT, ctx()).orElseThrow();
    }

    @Test
    @DisplayName("names the offending APIs, their tools, and both fixes (drop the tools / publish private)")
    void customApiRefusalRendersActionableMessage() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "CUSTOM_API_NOT_PUBLISHABLE");
        body.put("message", MESSAGE);
        body.put("customApis", List.of(Map.of(
                "apiSlug", "my-private-api",
                "apiName", "My Private API",
                "toolIdentifiers", List.of("my-private-api/do-thing"))));

        ToolExecutionResult result = publishRefusedWith(body);

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .contains("My Private API")
                .contains("my-private-api/do-thing")
                .contains("action=update")
                .contains("visibility='PRIVATE'");
    }

    @Test
    @DisplayName("a nameless API entry falls back to its slug rather than printing null")
    void namelessApiFallsBackToSlug() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "CUSTOM_API_NOT_PUBLISHABLE");
        body.put("message", MESSAGE);
        body.put("customApis", List.of(Map.of("apiSlug", "my-private-api")));

        ToolExecutionResult result = publishRefusedWith(body);

        assertThat(result.error()).contains("my-private-api");
        assertThat(result.error()).doesNotContain("null");
    }

    @Test
    @DisplayName("a refusal with no detail list still relays the service sentence and the fix")
    void refusalWithoutDetailsStillGuides() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "CUSTOM_API_NOT_PUBLISHABLE");
        body.put("message", MESSAGE);

        ToolExecutionResult result = publishRefusedWith(body);

        assertThat(result.error())
                .contains("Custom APIs cannot be shared")
                .contains("action=update");
    }

    @Test
    @DisplayName("an unknown refusal code keeps the generic relay (no silent mis-rendering)")
    void unknownCodeKeepsGenericMessage() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "SOME_FUTURE_CODE");
        body.put("message", "Something else is wrong.");

        ToolExecutionResult result = publishRefusedWith(body);

        assertThat(result.error()).isEqualTo("Failed to publish agent: Something else is wrong.");
    }
}
