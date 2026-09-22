package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.prompt.ConversationToolDefinitions;
import com.apimarketplace.conversation.service.ConversationHistoryService;
import com.apimarketplace.conversation.service.ToolResultService;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Asking the user to reconnect an account that IS connected but was not granted enough.
 *
 * <p>Before this, {@code require} had exactly two answers for a connected service: a hard
 * "Credentials already exist" error with no card, or the same thing with {@code force=true},
 * which the guidance then talked the caller out of using. Its words: "A 401/403 is often a
 * scope, account, quota, or endpoint mismatch - in that case try a different tool or narrow
 * the scope instead." For a genuine missing scope that advice is false in both halves, since
 * no other tool and no narrower scope can read a mailbox the account was never allowed to
 * read, and an agent that followed it filed the whole thing as a standing blocker. One did,
 * for six days, every thirty minutes.
 *
 * <p>What changed is not the amount of trust placed in the caller: it is that the caller can
 * now hand over something CHECKABLE. {@code force} asserts, {@code scopes} is verified. So the
 * card is raised on the ordinary path, with no force, no error and no anti-loop cooldown, and
 * only when the server has itself confirmed the account falls short.
 */
@ExtendWith(MockitoExtension.class)
class CredentialRequireScopeGapTest {

    private static final String GMAIL_DEFAULT_URL =
            "http://localhost:8083/api/internal/credentials/default?userId=tenant-1&integration=gmail";
    private static final String READONLY = "https://www.googleapis.com/auth/gmail.readonly";
    private static final String SEND = "https://www.googleapis.com/auth/gmail.send";
    private static final String LABELS = "https://www.googleapis.com/auth/gmail.labels";

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

    /** The account production actually held: it can label and send, and cannot read. */
    private void stubGmailGranted(String... scopes) throws Exception {
        String list = scopes.length == 0 ? "" :
                "\"" + String.join("\",\"", scopes) + "\"";
        bindAuthServiceServer()
                .expect(requestTo(GMAIL_DEFAULT_URL))
                .andRespond(withSuccess("""
                        {"id":41,"name":"Jaden","integration":"gmail","type":"OAuth2",
                         "status":"active","is_default":true,"scopes":[%s]}
                        """.formatted(list), MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("a verified scope gap raises the card with no force and no error")
    void raisesTheCardOnTheOrdinaryPath() throws Exception {
        stubGmailGranted(LABELS, SEND);

        ToolResult result = require(Map.of(
                "action", "require",
                "services", List.of("gmail"),
                "scopes", List.of(READONLY),
                "reason", "read the inbox"));

        assertThat(result.success())
                .as("the old path answered success=false here, which is what stopped the card")
                .isTrue();
        assertThat(result.error()).isNull();

        JsonNode content = new ObjectMapper().readTree(result.content());
        assertThat(content.path("status").asText()).isEqualTo("credentials_required");
        assertThat(content.path("services")).hasSize(1);
    }

    @Test
    @DisplayName("the card carries what the account holds, what it lacks and the full ask")
    void carriesTheScopeDetail() throws Exception {
        stubGmailGranted(LABELS, SEND);

        JsonNode service0 = requireServices(List.of(READONLY)).get(0);

        assertThat(service0.path("missingScopes")).hasSize(1);
        assertThat(service0.path("missingScopes").get(0).asText()).isEqualTo(READONLY);
        assertThat(toList(service0.path("grantedScopes"))).containsExactlyInAnyOrder(LABELS, SEND);
        assertThat(toList(service0.path("requiredScopes"))).containsExactly(READONLY);
        assertThat(service0.path("credentialType").asText())
                .as("only an OAuth2 account has scopes; the card renders nothing without this")
                .isEqualTo("OAuth2");
        // No per-service needsAttention: nothing reads one, and the flag that keeps the card
        // on screen lives on the result metadata (see flagsNeedsAttention). A second copy
        // would be a field a future reader believes in and nobody sets correctly.
        assertThat(service0.has("needsAttention")).isFalse();
    }

    /**
     * The half that keeps the mechanism honest. If holding every scope still raised a card,
     * {@code scopes} would be a way of forcing one without saying {@code force}, and the
     * anti-loop guard would be worth nothing.
     */
    @Test
    @DisplayName("an account that holds every scope asked for still gets the already-exists refusal")
    void noGapMeansNoCard() throws Exception {
        stubGmailGranted(LABELS, SEND, READONLY);

        ToolResult result = require(Map.of(
                "action", "require",
                "services", List.of("gmail"),
                "scopes", List.of(READONLY),
                "reason", "read the inbox"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Credentials already exist for: gmail");
    }

    /**
     * An API key has no scope concept, so its empty scope list means "not applicable", never
     * "granted nothing". Read the other way, naming any scope would raise a reconnect card on
     * every key-based integration on the platform.
     */
    @Test
    @DisplayName("a credential that declares no scopes at all is not treated as granting none")
    void emptyScopesIsNotAGap() throws Exception {
        stubGmailGranted();

        ToolResult result = require(Map.of(
                "action", "require",
                "services", List.of("gmail"),
                "scopes", List.of(READONLY),
                "reason", "read the inbox"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Credentials already exist");
    }

    @Test
    @DisplayName("naming no scopes leaves the previous behaviour exactly as it was")
    void withoutScopesNothingChanges() throws Exception {
        stubGmailGranted(LABELS, SEND);

        ToolResult result = require(Map.of(
                "action", "require",
                "services", List.of("gmail"),
                "reason", "read the inbox"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Credentials already exist for: gmail");
    }

    /**
     * Models serialise a list of scopes more than one way. A shape that parsed to nothing would
     * silently fall back to the old refusal, which is the failure being fixed arriving by a
     * different door.
     */
    @Test
    @DisplayName("scopes are read from a list or from a JSON array string")
    void acceptsEveryShapeAModelSends() throws Exception {
        for (Object shape : List.of(List.of(READONLY), "[\"" + READONLY + "\"]", READONLY)) {
            setUp();
            stubGmailGranted(LABELS, SEND);
            ToolResult result = require(Map.of(
                    "action", "require", "services", List.of("gmail"),
                    "scopes", shape, "reason", "read the inbox"));
            assertThat(result.success())
                    .as("scopes sent as %s must be understood", shape.getClass().getSimpleName())
                    .isTrue();
        }
    }

    /**
     * A bare string is ONE scope, never several. The granted side is re-split because providers
     * return a delimited blob; the required side never is, and the reason is concrete: a scope
     * may contain a space (GrantedScopes' own javadoc names "Tenant Non-Configurable"), so
     * splitting would invent two requirements out of one and report both as missing.
     */
    @Test
    @DisplayName("a scope containing a space stays one scope and is not split into two")
    void doesNotSplitAScopeOnWhitespace() throws Exception {
        stubGmailGranted(LABELS, SEND);

        JsonNode service0 = requireServicesRaw("Tenant Non-Configurable").get(0);

        assertThat(toList(service0.path("requiredScopes")))
                .containsExactly("Tenant Non-Configurable");
        assertThat(toList(service0.path("missingScopes")))
                .containsExactly("Tenant Non-Configurable");
    }

    /**
     * Without this flag the card auto-approves and vanishes: it dismisses itself whenever every
     * service it names already has a credential, which is exactly the shape of a scope gap. The
     * user would have seen a green "approved" box and no mention of the scope that just failed.
     */
    @Test
    @DisplayName("the result flags needsAttention, which is what keeps the card on screen")
    void flagsNeedsAttention() throws Exception {
        stubGmailGranted(LABELS, SEND);

        ToolResult result = require(Map.of(
                "action", "require", "services", List.of("gmail"),
                "scopes", List.of(READONLY), "reason", "read the inbox"));

        assertThat(result.metadata()).containsEntry("needsAttention", true);
        assertThat(result.metadata().get("needsAttentionServices").toString()).contains("gmail");
    }

    /**
     * The agent reads this sentence and reports it to the user. "Nothing is connected yet" is
     * false here and sends it to describe a connect flow for an account that exists.
     */
    @Test
    @DisplayName("the message says the account is connected and short of scopes, not missing")
    void messageDescribesTheRightSituation() throws Exception {
        stubGmailGranted(LABELS, SEND);

        ToolResult result = require(Map.of(
                "action", "require", "services", List.of("gmail"),
                "scopes", List.of(READONLY), "reason", "read the inbox"));

        String message = new ObjectMapper().readTree(result.content()).path("message").asText();
        assertThat(message).contains("The account IS connected");
        assertThat(message).doesNotContain("Nothing is connected yet");
    }

    /**
     * The cooldown exists to stop an agent nagging on a hunch. A checked scope gap is not a
     * hunch, and counting it would burn the one forced reconnect the conversation gets on the
     * case that needed no force at all.
     */
    @Test
    @DisplayName("a scope-gap card does not spend the conversation's forced-reconnect budget")
    void doesNotConsumeTheAntiLoopBudget() throws Exception {
        stubGmailGranted(LABELS, SEND);
        require(Map.of("action", "require", "services", List.of("gmail"),
                "scopes", List.of(READONLY), "reason", "read the inbox"));

        assertThat(ConversationToolExecutionService.RECENT_FORCE_REQUESTS)
                .as("nothing was forced, so nothing should be on the cooldown")
                .isEmpty();
    }

    /**
     * The combination the guidance steers away from, sent anyway.
     *
     * <p>An agent that ignores instructions is the reason this change exists, so the two
     * arguments have to compose rather than cancel. The forced path builds its card from the
     * request's own service maps, which carry no scope detail, so before this the card rendered
     * as a plain amber reconnect with no mention of the scope that had just failed, on a call
     * that had named that scope explicitly.
     */
    @Test
    @DisplayName("force and scopes together still produce a card that names the missing scope")
    void forceDoesNotDiscardTheScopeDetail() throws Exception {
        stubGmailGranted(LABELS, SEND);

        ToolResult result = require(Map.of(
                "action", "require", "services", List.of("gmail"),
                "scopes", List.of(READONLY), "force", true, "reason", "token expired"));

        assertThat(result.success()).isTrue();
        JsonNode service0 = toListNodes(
                new ObjectMapper().readTree(result.content()).path("services")).get(0);
        assertThat(toList(service0.path("missingScopes"))).containsExactly(READONLY);
        assertThat(service0.path("credentialType").asText()).isEqualTo("OAuth2");
        assertThat(result.metadata()).containsEntry("needsAttention", true);
    }

    /**
     * The same combination, asked of the anti-loop guard.
     *
     * <p>A connected service short of a scope is still CONNECTED, but it no longer lands in
     * the list the force guard walked, so {@code force} + {@code scopes} recorded no cooldown
     * and was never checked against one: an agent could raise a forced card on every call, for
     * ever. That matters beyond the loop, because the tool definition promises the agent in so
     * many words that "the server blocks the second attempt", and an instruction the server
     * does not keep is worse than no instruction.
     */
    @Test
    @DisplayName("a forced scope-gap call records a cooldown, and the second one is blocked")
    void forceWithScopesIsStillRateLimited() throws Exception {
        // ONE binding that answers twice: each bindAuthServiceServer call replaces the
        // interceptor, so two stub calls would leave only the second expectation.
        bindAuthServiceServer()
                .expect(ExpectedCount.twice(), requestTo(GMAIL_DEFAULT_URL))
                .andRespond(withSuccess("""
                        {"id":41,"name":"Jaden","integration":"gmail","type":"OAuth2",
                         "status":"active","is_default":true,"scopes":["%s","%s"]}
                        """.formatted(LABELS, SEND), MediaType.APPLICATION_JSON));
        Map<String, Object> args = Map.of("action", "require", "services", List.of("gmail"),
                "scopes", List.of(READONLY), "force", true, "reason", "token expired");

        ToolResult first = require(args);
        assertThat(first.success()).as("the first forced call still raises its card").isTrue();
        assertThat(ConversationToolExecutionService.RECENT_FORCE_REQUESTS)
                .as("a force that records nothing can never be blocked")
                .isNotEmpty();

        ToolResult second = require(args);
        assertThat(second.success()).isFalse();
        assertThat(second.error())
                .as("and the refusal has to tell the agent to stop asking, not just fail")
                .contains("already requested a forced reconnect");
        assertThat(second.metadata()).containsEntry("forceLoopBlocked", List.of("gmail"));
    }

    /**
     * A value that announces itself as a JSON array and is not one says nothing about which
     * scopes were meant. Keeping the whole broken text as ONE scope invents a requirement no
     * account can hold, so the gap is always non-empty: the user is sent to reconnect for a
     * scope that does not exist, on every call, and reconnecting cannot help.
     */
    @Test
    @DisplayName("a malformed JSON-array scopes argument asks for nothing rather than for nonsense")
    void malformedScopesArrayIsNotOneGiantScope() throws Exception {
        stubGmailGranted(LABELS, SEND);

        ToolResult result = require(Map.of(
                "action", "require", "services", List.of("gmail"),
                "scopes", "[\"" + READONLY, "reason", "read the inbox"));

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .as("said out loud: dropped in silence, the caller believes its scopes were "
                        + "checked and found present, which is a wrong conclusion about the "
                        + "user's account rather than a missing one")
                .contains("`scopes` could not be read")
                .contains("no card was shown");
        assertThat(result.error())
                .as("and the way out is named, not left to be guessed")
                .contains("JSON array of strings");
        assertThat(result.error())
                .as("no card, and the refusal must not read like the already-exists one, which "
                        + "tells the caller to stop asking")
                .doesNotContain("Credentials already exist");
    }

    /**
     * The other half of the same rule: what IS readable must not be refused. A bare string is
     * one scope (never split, since a scope can contain spaces), an empty list is no ask, and
     * an absent argument is the ordinary path.
     */
    @Test
    @DisplayName("a readable scopes argument is never mistaken for an unreadable one")
    void readableScopesAreNotRefused() throws Exception {
        stubGmailGranted(LABELS, SEND);

        ToolResult result = require(Map.of(
                "action", "require", "services", List.of("gmail"),
                "scopes", READONLY, "reason", "read the inbox"));

        assertThat(result.success())
                .as("a bare string is a perfectly readable one-scope ask")
                .isTrue();
        assertThat(result.error()).isNull();
    }

    /**
     * A flat list cannot describe two integrations. Applied to both, gmail's missing scope
     * would be reported as slack's as well, raising a reconnect card for an account that is
     * perfectly fine. The refusal names the way out rather than guessing which service the
     * list belonged to.
     */
    @Test
    @DisplayName("scopes with several services is refused, and the refusal says what to do instead")
    void refusesScopesAcrossSeveralServices() {
        ToolResult result = require(Map.of(
                "action", "require",
                "services", List.of("gmail", "slack"),
                "scopes", List.of(READONLY),
                "reason", "read the inbox"));

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .contains("ONE integration")
                .contains("once per service");
    }

    @Test
    @DisplayName("several services WITHOUT scopes still works exactly as it did")
    void severalServicesWithoutScopesIsUnaffected() throws Exception {
        MockRestServiceServer server = bindAuthServiceServer();
        server.expect(requestTo(GMAIL_DEFAULT_URL))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));
        server.expect(requestTo("http://localhost:8083/api/internal/credentials/default"
                        + "?userId=tenant-1&integration=slack"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        ToolResult result = require(Map.of(
                "action", "require",
                "services", List.of("gmail", "slack"),
                "reason", "connect both"));

        assertThat(result.success())
                .as("the guard must bite on `scopes`, not on multi-service requests")
                .isTrue();
    }

    @Test
    @DisplayName("the tool definition tells the agent to send scopes and not to force")
    void definitionTeachesTheNewPath() {
        String description = ConversationToolDefinitions.getConversationTools(false).stream()
                .filter(t -> "credential".equals(t.name()))
                .map(ToolDefinition::description)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the credential tool is not offered at all"));

        assertThat(description).contains("scopes");
        assertThat(description)
                .as("the decision table needs the row that was missing, or nothing reaches this code")
                .contains("CREDENTIALS_INSUFFICIENT");
        assertThat(description)
                .as("the old row sent a scope mismatch away from require; it must now be conditional")
                .contains("NOTHING names a missing scope");
    }

    // ---- helpers ----

    private List<JsonNode> toListNodes(JsonNode array) {
        return java.util.stream.StreamSupport
                .stream(array.spliterator(), false).toList();
    }

    /** Same as {@link #requireServices}, with the scopes arg passed through verbatim. */
    private List<JsonNode> requireServicesRaw(Object scopes) throws Exception {
        ToolResult result = require(Map.of(
                "action", "require", "services", List.of("gmail"),
                "scopes", scopes, "reason", "read the inbox"));
        return toListNodes(new ObjectMapper().readTree(result.content()).path("services"));
    }

    private List<JsonNode> requireServices(List<String> scopes) throws Exception {
        ToolResult result = require(Map.of(
                "action", "require", "services", List.of("gmail"),
                "scopes", scopes, "reason", "read the inbox"));
        return toListNodes(new ObjectMapper().readTree(result.content()).path("services"));
    }

    private List<String> toList(JsonNode array) {
        return toListNodes(array).stream().map(JsonNode::asText).toList();
    }

    private ToolResult require(Map<String, Object> args) {
        ToolCall call = new ToolCall("call_1", "credential", args, null);
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
