package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.prompt.ConversationToolDefinitions;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.service.ToolResultService;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The chat surface of "which of my accounts can a workflow step run on".
 *
 * <p>A chat agent has {@code credential(action='list')} and never sees
 * {@code get_connected_services}, so whatever that action says IS the whole answer
 * for it. Both of its texts used to state that only the isDefault=true credential is
 * ever used, which stopped being true when credential_selector shipped: an agent that
 * believes the other accounts are dead will not offer to build the multi-account
 * workflow it was just asked for, and will not report a bug either, because from its
 * side the feature does not exist.
 *
 * <p>Both texts are asserted here because they are read at different moments. The tool
 * DEFINITION is in the system prompt before any call; the response HINT arrives with
 * the data and, being state, is the one that wins if they ever disagree.
 */
@DisplayName("credential(action='list') tells a chat agent the non-default accounts are selectable")
@ExtendWith(MockitoExtension.class)
class CredentialListSelectorGuidanceTest {

    @Mock
    private ConversationHistoryService conversationHistoryService;
    @Mock
    private ToolResultService toolResultService;
    @Mock
    private StreamPubSubService streamPubSubService;

    private ConversationToolExecutionService service;

    @BeforeEach
    void setUp() throws Exception {
        ConversationToolExecutionService.RECENT_FORCE_REQUESTS.clear();
        ToolServiceRouter router = new ToolServiceRouter(
                "http://localhost:8099", "http://localhost:8090",
                "http://localhost:8088", "http://localhost:8089", "http://localhost:8081");
        service = new ConversationToolExecutionService(
                conversationHistoryService, toolResultService, streamPubSubService, router);
        set("authServiceUrl", "http://localhost:8083");
        set("orchestratorUrl", "http://localhost:8099");
        set("mcpGatewayUrl", "http://localhost:8083");
    }

    @Test
    @DisplayName("the response hint names credential_selector and the active-only rule")
    void hintPointsAtTheSelector() throws Exception {
        MockRestServiceServer server = bindAuthServiceServer();
        server.expect(requestTo("http://localhost:8083/api/internal/credentials/all?userId=tenant-1"))
                .andRespond(withSuccess("""
                        [
                          {"name":"Client A","integration":"instagram","status":"ACTIVE","is_default":true},
                          {"name":"Client B","integration":"instagram","status":"ACTIVE","is_default":false}
                        ]
                        """, MediaType.APPLICATION_JSON));

        String content = hint();

        assertThat(content)
                .as("without this the agent has no name for the mechanism it would need")
                .contains("credential_selector");
        assertThat(content)
                .as("the account a step could actually run on, named for the agent to copy")
                .contains("\"Client B\" (instagram)")
                .contains("active only");
        assertThat(content)
                .as("the direct path, and the fact that it too can name an account")
                .contains("Executing a tool directly uses the isDefault=true credential unless the "
                        + "call names another with credential_name");
    }

    @Test
    @DisplayName("the response hint no longer claims the other accounts cannot run")
    void hintDoesNotDismissNonDefaults() throws Exception {
        // Asserted on the POPULATED branch, which is the one that carried the claim. An
        // earlier version of this test stubbed an empty list, which selects a different
        // string that never said it: the assertion could not fail for its stated reason.
        MockRestServiceServer server = bindAuthServiceServer();
        server.expect(requestTo("http://localhost:8083/api/internal/credentials/all?userId=tenant-1"))
                .andRespond(withSuccess("""
                        [
                          {"name":"Client A","integration":"instagram","status":"ACTIVE","is_default":true},
                          {"name":"Client B","integration":"instagram","status":"ACTIVE","is_default":false}
                        ]
                        """, MediaType.APPLICATION_JSON));

        assertThat(hint())
                .doesNotContain("Only isDefault=true credentials are used")
                .doesNotContain("Only default credentials are used");
    }

    @Test
    @DisplayName("granted scopes are listed, because they are what separates two accounts of one integration")
    void listsGrantedScopes() throws Exception {
        // Without them a chat agent sees two Gmail accounts and nothing to tell them
        // apart, then gets refused on whichever one the default resolves to.
        MockRestServiceServer server = bindAuthServiceServer();
        server.expect(requestTo("http://localhost:8083/api/internal/credentials/all?userId=tenant-1"))
                .andRespond(withSuccess("""
                        [
                          {"name":"Perso","integration":"gmail","status":"ACTIVE","is_default":true,
                           "scopes":["https://www.googleapis.com/auth/gmail.send"]},
                          {"name":"Stripe","integration":"stripe","status":"ACTIVE","is_default":true}
                        ]
                        """, MediaType.APPLICATION_JSON));

        String content = list().content();

        assertThat(content).contains("https://www.googleapis.com/auth/gmail.send");
        // An API key has no scopes; emitting [] would read as "granted nothing", which is
        // exactly what a revoked OAuth account looks like.
        assertThat(content).doesNotContain("\"scopes\":[]");
    }

    @Test
    @DisplayName("with nothing connected the hint says so, without inventing a selector to use")
    void emptyStateOffersNothingToSelect() throws Exception {
        // The no-services branch is a separate string. Naming credential_selector there
        // would point an agent at a mechanism with no account to apply it to.
        MockRestServiceServer server = bindAuthServiceServer();
        server.expect(requestTo("http://localhost:8083/api/internal/credentials/all?userId=tenant-1"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        String content = hint();

        assertThat(content).contains("No services connected");
        assertThat(content).doesNotContain("credential_selector");
    }

    @Test
    @DisplayName("a chat agent is offered only the accounts the runtime would accept")
    void offersOnlySelectableAccounts() throws Exception {
        // The whole point of routing this through the shared helper: before it, this
        // surface promised that every non-default account was selectable, so a chat agent
        // reading it would hand a revoked or ambiguous name to a fail-closed step.
        MockRestServiceServer server = bindAuthServiceServer();
        server.expect(requestTo("http://localhost:8083/api/internal/credentials/all?userId=tenant-1"))
                .andRespond(withSuccess("""
                        [
                          {"name":"Main","integration":"instagram","status":"ACTIVE","is_default":true},
                          {"name":"Usable","integration":"instagram","status":"ACTIVE","is_default":false},
                          {"name":"Revoked","integration":"instagram","status":"NEEDS_REAUTH","is_default":false},
                          {"name":"42","integration":"instagram","status":"ACTIVE","is_default":false},
                          {"name":"Twin","integration":"instagram","status":"ACTIVE","is_default":false},
                          {"name":"twin","integration":"Instagram","status":"ACTIVE","is_default":false}
                        ]
                        """, MediaType.APPLICATION_JSON));

        String content = hint();

        assertThat(content).contains("\"Usable\" (instagram)");
        // Each of these appears in the `connected` array, so assert on the OFFER form.
        assertThat(content).doesNotContain("\"Revoked\"").doesNotContain("\"42\"")
                .doesNotContain("\"Twin\"").doesNotContain("\"twin\"");
    }

    @Test
    @DisplayName("with credentials but nothing selectable, the hint does not mention the selector")
    void saysNothingAboutTheSelectorWhenNothingQualifies() throws Exception {
        MockRestServiceServer server = bindAuthServiceServer();
        server.expect(requestTo("http://localhost:8083/api/internal/credentials/all?userId=tenant-1"))
                .andRespond(withSuccess("""
                        [
                          {"name":"Main","integration":"instagram","status":"ACTIVE","is_default":true},
                          {"name":"Revoked","integration":"slack","status":"NEEDS_REAUTH","is_default":false}
                        ]
                        """, MediaType.APPLICATION_JSON));

        // The old sentence claimed "the others are selectable" unconditionally, which sends
        // the agent looking for an account it cannot use.
        assertThat(hint()).doesNotContain("credential_selector");
    }

    @Test
    @DisplayName("the tool definition read before any call says the same three things")
    void definitionAgreesWithTheHint() {
        String definitions = ConversationToolDefinitions.getConversationTools(false).stream()
                .filter(t -> "credential".equals(t.name()))
                .map(ToolDefinition::description)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the credential tool is not offered at all"));

        assertThat(definitions).contains("credential_selector");
        // Fragments here are chosen so they cannot straddle a line break: the description
        // is a text block, and rewrapping it must not fail a test about its meaning.
        assertThat(definitions).contains("than 'active'");
        assertThat(definitions)
                .as("the two refusals a name read straight out of the listing can still hit")
                .contains("selects neither")
                .contains("positive whole number");
        assertThat(definitions)
                .as("copying the name is the actionable half: the matcher forgives case and spaces, nothing else")
                .contains("capitalisation and surrounding spaces");
        assertThat(definitions).doesNotContain("Only isDefault=true credentials are used");
    }

    /** The `hint` field, decoded: the response is JSON, so quoted names arrive escaped. */
    private String hint() throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(list().content())
                .path("hint")
                .asText();
    }

    private ToolResult list() {
        ToolCall call = new ToolCall("call_1", "credential", Map.of("action", "list"), null);
        ToolDefinition def = ToolDefinition.builder().name("credential").description("test").build();
        return service.executeTool(call, def, "tenant-1", Map.of());
    }

    private MockRestServiceServer bindAuthServiceServer() throws Exception {
        Field f = ConversationToolExecutionService.class.getDeclaredField("restTemplate");
        f.setAccessible(true);
        return MockRestServiceServer.bindTo((RestTemplate) f.get(service)).build();
    }

    private void set(String field, String value) throws Exception {
        Field f = ConversationToolExecutionService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }
}
