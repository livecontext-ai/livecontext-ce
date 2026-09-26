package com.apimarketplace.orchestrator.tools.channel;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ChatChannelException;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ChatChannelSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the agent actually receives.
 *
 * <p>The help promises specific field names and then refuses without them
 * ("link_id is required. Run list to read the link_id of each connected chat"), so
 * the names in the payload and the names in the help are one contract, not two. An
 * agent that follows the documented flow and cannot find the id it was told to copy
 * is stuck on step one with nothing to read.
 */
class ChannelToolsProviderTest {

    private static final UUID LINK_ID = UUID.randomUUID();

    private ChatChannelService service;
    private ChannelToolsProvider provider;

    @BeforeEach
    void setUp() {
        service = mock(ChatChannelService.class);
        provider = new ChannelToolsProvider(service);
    }

    private static ToolExecutionContext context(String tenantId, String orgId) {
        return new ToolExecutionContext(tenantId, Map.of(), Map.of(), Set.of(), null, null, orgId, null);
    }

    private static ToolExecutionContext context(String tenantId, String orgId, String orgRole) {
        return new ToolExecutionContext(tenantId, Map.of(), Map.of(), Set.of(), null, null, orgId, orgRole);
    }

    private static ChatChannelSummary summary() {
        return new ChatChannelSummary(LINK_ID, "telegram", 9L, "ops_bot", "-100123", "Ops room",
                "group", true, true, Instant.parse("2026-09-17T10:00:00Z"),
                "https://app.example.com/approval-callback/telegram",
                Instant.parse("2026-09-17T09:00:00Z"), null,
                java.util.List.of("777"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstChannel(ToolExecutionResult result) {
        Map<String, Object> data = (Map<String, Object>) result.data();
        return ((List<Map<String, Object>>) data.get("channels")).get(0);
    }

    @Test
    @DisplayName("list answers with the field names the help tells the agent to read")
    void listUsesTheDocumentedFieldNames() {
        when(service.list("org-1")).thenReturn(List.of(summary()));

        Map<String, Object> entry = firstChannel(
                provider.execute("channel", Map.of("action", "list"), context("42", "org-1")));

        // A record left to serialise itself would emit linkId / isDefault / verifiedAt,
        // and set_default and disconnect both refuse without link_id.
        assertThat(entry).containsKeys("link_id", "channel", "chat_id", "chat_title",
                "is_default", "verified_at", "last_error");
        assertThat(entry).doesNotContainKeys("linkId", "isDefault", "verifiedAt", "lastError");
        assertThat(entry.get("link_id")).isEqualTo(LINK_ID.toString());
        assertThat(entry.get("is_default")).isEqualTo(true);
    }

    @Test
    @DisplayName("the id list returns is the id set_default accepts")
    void theIdItReturnsIsTheIdItAccepts() {
        when(service.list("org-1")).thenReturn(List.of(summary()));
        when(service.setDefault(eq("42"), eq("org-1"), any())).thenReturn(summary());

        String id = String.valueOf(firstChannel(
                provider.execute("channel", Map.of("action", "list"), context("42", "org-1")))
                .get("link_id"));
        ToolExecutionResult result = provider.execute("channel",
                Map.of("action", "set_default", "link_id", id), context("42", "org-1"));

        assertThat(result.success()).isTrue();
        verify(service).setDefault("42", "org-1", LINK_ID);
    }

    @Test
    @DisplayName("refuses an id that is not one of its own, and says where to find one")
    void refusesAnUnparseableId() {
        ToolExecutionResult result = provider.execute("channel",
                Map.of("action", "disconnect", "link_id", "the ops room"), context("42", "org-1"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("ids returned by list");
        verify(service, never()).disconnect(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("asks for the id instead of acting on none")
    void refusesAMissingId() {
        ToolExecutionResult result = provider.execute("channel",
                Map.of("action", "set_default"), context("42", "org-1"));

        assertThat(result.error()).contains("link_id is required");
    }

    @Test
    @DisplayName("says there is no workspace rather than failing inside persistence")
    void refusesWithoutAWorkspace() {
        ToolExecutionResult result = provider.execute("channel",
                Map.of("action", "connect", "chat_id", "-100123"), context("42", null));

        // The alternative is a message written for whoever maintains this code, reaching
        // the agent verbatim, after the bot was verified and its webhook possibly pointed.
        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("No workspace in this context");
        assertThat(result.error()).doesNotContain("OrgScopedEntity").doesNotContain("runWithOrgScope");
        verify(service, never()).connect(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("discover works without a workspace, because it only reads the provider")
    void discoverNeedsNoWorkspace() {
        when(service.discover(eq("42"), eq("telegram"), any()))
                .thenReturn(new ChatChannelService.DiscoveryResult("telegram", 9L, "ops_bot", List.of()));

        ToolExecutionResult result = provider.execute("channel",
                Map.of("action", "discover"), context("42", null));

        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("an empty discovery tells the agent what to ask for, not just that it is empty")
    void emptyDiscoveryCarriesTheNextStep() {
        when(service.discover(anyString(), anyString(), any()))
                .thenReturn(new ChatChannelService.DiscoveryResult("telegram", 9L, "ops_bot", List.of()));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) provider.execute("channel",
                Map.of("action", "discover"), context("42", "org-1")).data();

        assertThat(String.valueOf(data.get("hint"))).contains("@ops_bot").contains("send it any message");
    }

    @Test
    @DisplayName("a discovery that could not list says why, and does not claim nobody wrote to the bot")
    void unlistableDiscoveryCarriesTheNotice() {
        when(service.discover(anyString(), anyString(), any()))
                .thenReturn(new ChatChannelService.DiscoveryResult("telegram", 9L, "ops_bot", List.of(),
                        "the bot is already connected"));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) provider.execute("channel",
                Map.of("action", "discover"), context("42", "org-1")).data();

        assertThat(data.get("notice")).isEqualTo("the bot is already connected");
        assertThat(data).doesNotContainKey("hint");
        assertThat(data.get("bot_username")).isEqualTo("ops_bot");
    }

    @Test
    @DisplayName("relays a refusal as the sentence the service wrote, unchanged")
    void relaysTheServiceSentence() {
        when(service.connect(anyString(), anyString(), any(), any()))
                .thenThrow(new ChatChannelException("Telegram will not list recent messages while this bot "
                        + "has an active webhook."));

        ToolExecutionResult result = provider.execute("channel",
                Map.of("action", "connect", "chat_id", "-1"), context("42", "org-1"));

        assertThat(result.error()).isEqualTo("Telegram will not list recent messages while this bot "
                + "has an active webhook.");
    }

    @Test
    @DisplayName("names its actions when given none")
    void requiresAnAction() {
        ToolExecutionResult result = provider.execute("channel", Map.of(), context("42", "org-1"));

        assertThat(result.error()).contains("action is required").contains("discover").contains("connect");
    }

    @Test
    @DisplayName("a read-only member cannot change where the workspace is reached")
    void readOnlyMemberCannotWrite() {
        for (String action : List.of("discover", "connect", "set_default", "disconnect")) {
            ToolExecutionResult result = provider.execute("channel",
                    Map.of("action", action, "chat_id", "-100123", "link_id", LINK_ID.toString()),
                    context("42", "org-1", "VIEWER"));

            // The default destination decides WHO is asked to authorize this workspace's
            // agents, and a press on that message runs the action under the agent owner's
            // tenant. A read-only member pointing it at their own chat could approve on
            // the workspace's behalf.
            assertThat(result.success()).as(action).isFalse();
            assertThat(result.error()).as(action).contains("read-only");
        }
        verify(service, never()).discover(anyString(), anyString(), any());
        verify(service, never()).connect(anyString(), anyString(), any(), any());
        verify(service, never()).setDefault(anyString(), anyString(), any());
        verify(service, never()).disconnect(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("a read-only member can still read the list")
    void readOnlyMemberCanRead() {
        when(service.list("org-1")).thenReturn(List.of(summary()));

        ToolExecutionResult result = provider.execute("channel",
                Map.of("action", "list"), context("42", "org-1", "VIEWER"));

        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("a member with a write role is not blocked")
    void writeRoleIsAllowed() {
        when(service.connect(anyString(), anyString(), any(), any())).thenReturn(
                new ChatChannelService.ConnectResult(summary(), true,
                        ChatChannelService.WebhookOutcome.POINTED, null));

        ToolExecutionResult result = provider.execute("channel",
                Map.of("action", "connect", "chat_id", "-100123"), context("42", "org-1", "MEMBER"));

        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("carries the allow-list through, so a group is not open to everyone in it")
    void carriesTheAllowList() {
        when(service.connect(anyString(), anyString(), any(), any())).thenReturn(
                new ChatChannelService.ConnectResult(summary(), true,
                        ChatChannelService.WebhookOutcome.POINTED, null));

        provider.execute("channel", Map.of(
                "action", "connect", "chat_id", "-100123",
                "allowed_user_ids", List.of("777", 888)), context("42", "org-1"));

        ArgumentCaptor<ChatChannelService.ConnectRequest> request =
                ArgumentCaptor.forClass(ChatChannelService.ConnectRequest.class);
        verify(service).connect(anyString(), anyString(), request.capture(), eq(ChatChannelService.ChangeSource.ASSISTANT));
        // Numbers and strings both arrive from a model; both are ids.
        assertThat(request.getValue().allowedUserIds()).containsExactly("777", "888");
    }

    @Test
    @DisplayName("an omitted allow-list is null, so the service leaves the stored one alone")
    void omittedAllowListIsNull() {
        // This assertion used to read isEmpty(), and pinned the defect rather than the rule:
        // coercing "absent" to "empty" at the surface meant the writer could not tell them
        // apart, so it ignored empty, and a restriction could be replaced but never cleared.
        when(service.connect(anyString(), anyString(), any(), any())).thenReturn(
                new ChatChannelService.ConnectResult(summary(), true,
                        ChatChannelService.WebhookOutcome.POINTED, null));

        provider.execute("channel", Map.of("action", "connect", "chat_id", "-1"), context("42", "org-1"));

        ArgumentCaptor<ChatChannelService.ConnectRequest> request =
                ArgumentCaptor.forClass(ChatChannelService.ConnectRequest.class);
        verify(service).connect(anyString(), anyString(), request.capture(), eq(ChatChannelService.ChangeSource.ASSISTANT));
        assertThat(request.getValue().allowedUserIds())
                .as("saying nothing must stay distinguishable from asking for no restriction")
                .isNull();
    }

    @Test
    @DisplayName("an explicitly empty allow-list stays empty, which is the clear")
    void emptyAllowListIsPassedThroughEmpty() {
        // Guards the inversion above from over-correcting into "null for everything", which
        // would make the list unclearable again by the opposite route.
        when(service.connect(anyString(), anyString(), any(), any())).thenReturn(
                new ChatChannelService.ConnectResult(summary(), true,
                        ChatChannelService.WebhookOutcome.POINTED, null));

        provider.execute("channel", Map.of("action", "connect", "chat_id", "-1",
                "allowed_user_ids", java.util.List.of()), context("42", "org-1"));

        ArgumentCaptor<ChatChannelService.ConnectRequest> request =
                ArgumentCaptor.forClass(ChatChannelService.ConnectRequest.class);
        verify(service).connect(anyString(), anyString(), request.capture(), eq(ChatChannelService.ChangeSource.ASSISTANT));
        assertThat(request.getValue().allowedUserIds())
                .as("an empty array is the caller asking to open the destination to anyone")
                .isNotNull().isEmpty();
    }

    @Test
    @DisplayName("list shows who may decide, so the restriction is not writable and invisible")
    void listShowsAllowedUserIds() {
        when(service.list("org-1")).thenReturn(List.of(summary()));

        ToolExecutionResult result = provider.execute("channel",
                Map.of("action", "list"), context("42", "org-1"));

        assertThat(firstChannel(result))
                .as("nothing told the agent whether a connected group was open to all its members")
                .containsEntry("allowed_user_ids", List.of("777"));
    }

    @Test
    @DisplayName("a credential_id of an unexpected type is refused, not silently defaulted")
    void credentialIdOfUnexpectedTypeIsRefused() {
        // Mirrors the REST fix. Neither a number nor a string used to fall through to null,
        // which is the same value as absent, so the connect went ahead on whichever credential
        // the workspace defaults to and reported success. The agent named one and used another.
        ToolExecutionResult result = provider.execute("channel",
                Map.of("action", "connect", "chat_id", "-1", "credential_id", true),
                context("42", "org-1"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("credential_id must be a number");
        verify(service, never()).connect(anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("an omitted credential_id stays absent, which asks for the workspace default")
    void omittedCredentialIdStaysNull() {
        // Guards the refusal above from over-correcting: absent is a legitimate request.
        when(service.connect(anyString(), anyString(), any(), any())).thenReturn(
                new ChatChannelService.ConnectResult(summary(), true,
                        ChatChannelService.WebhookOutcome.POINTED, null));

        provider.execute("channel", Map.of("action", "connect", "chat_id", "-1"),
                context("42", "org-1"));

        ArgumentCaptor<ChatChannelService.ConnectRequest> request =
                ArgumentCaptor.forClass(ChatChannelService.ConnectRequest.class);
        verify(service).connect(anyString(), anyString(), request.capture(), eq(ChatChannelService.ChangeSource.ASSISTANT));
        assertThat(request.getValue().credentialId()).isNull();
    }

    @Test
    @DisplayName("help needs no workspace and no user")
    void helpIsAlwaysAvailable() {
        ToolExecutionResult result = provider.execute("channel",
                Map.of("action", "help"), context(null, null));

        assertThat(result.success()).isTrue();
    }

    @Test
    @DisplayName("connect passes the account setting through and hands the setup steps back to relay")
    @SuppressWarnings("unchecked")
    void connectCarriesAccountSettingAndSetupSteps() {
        org.mockito.ArgumentCaptor<ChatChannelService.ConnectRequest> request =
                org.mockito.ArgumentCaptor.forClass(ChatChannelService.ConnectRequest.class);
        when(service.connect(anyString(), anyString(), request.capture(), any())).thenReturn(new ChatChannelService.ConnectResult(
                summary(), true, ChatChannelService.WebhookOutcome.NOT_OURS_TO_SET, null,
                "Set the Interactions Endpoint URL to https://x/approval-callback/discord/b1"));

        Map<String, Object> data = (Map<String, Object>) provider.execute("channel", Map.of("action", "connect",
                "channel", "discord", "chat_id", "C1", "account_setting", "abc123"), context("42", "org-1")).data();

        assertThat(request.getValue().channel()).isEqualTo("discord");
        assertThat(request.getValue().accountSetting()).isEqualTo("abc123");
        // Without the steps the connect looks finished while presses cannot come back yet.
        assertThat(data).containsEntry("setup_instructions",
                "Set the Interactions Endpoint URL to https://x/approval-callback/discord/b1");
        assertThat(data).containsEntry("webhook", "not_ours_to_set");
    }

    @Test
    @DisplayName("an empty discovery on another service says what that service needs, not Telegram's step")
    @SuppressWarnings("unchecked")
    void emptyDiscoveryOnAnotherService() {
        when(service.discover(anyString(), eq("slack"), any()))
                .thenReturn(new ChatChannelService.DiscoveryResult("slack", 9L, "ops", List.of()));

        Map<String, Object> data = (Map<String, Object>) provider.execute("channel",
                Map.of("action", "discover", "channel", "slack"), context("42", "org-1")).data();

        assertThat(String.valueOf(data.get("hint"))).contains("/invite").doesNotContain("send it any message");
    }

    @Test
    @DisplayName("the channel parameter offers exactly the services that have a connector, and the help walks each one")
    void schemaAndHelpCoverEveryService() {
        com.apimarketplace.agent.registry.AgentToolDefinition tool = provider.getTools().get(0);

        assertThat(tool.parameters()).filteredOn(p -> "channel".equals(p.name())).singleElement()
                .satisfies(p -> assertThat(p.enumValues()).containsExactlyElementsOf(
                        com.apimarketplace.orchestrator.services.channel.ChatChannelConnectorRegistry.KNOWN_CHANNELS));
        assertThat(tool.helpText()).contains("telegram (").contains("slack (").contains("discord (")
                .contains("whatsapp (").contains("teams (").contains("account_setting")
                .doesNotContain("\u2014").doesNotContain("\u2013");
    }
}
