package com.apimarketplace.orchestrator.services.channel;

import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import com.apimarketplace.orchestrator.domain.channel.ChatChannelBotEntity;
import com.apimarketplace.orchestrator.domain.channel.ChatChannelLinkEntity;
import com.apimarketplace.orchestrator.repository.ChatChannelBotRepository;
import com.apimarketplace.orchestrator.repository.ChatChannelLinkRepository;
import org.springframework.dao.DataIntegrityViolationException;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.BotIdentity;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.ChatCandidate;
import com.apimarketplace.orchestrator.services.channel.ChatChannelConnector.Outcome;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ChatChannelException;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ConnectRequest;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.ConnectResult;
import com.apimarketplace.orchestrator.services.channel.ChatChannelService.WebhookOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChatChannelService}.
 *
 * <p>The cases that matter here are the ones where a wrong decision produces a
 * connection that reports success and delivers nothing: an existing webhook that
 * must not be overwritten, a destination that never received the test message,
 * and the default slot that must never point at either.
 */
class ChatChannelServiceTest {

    private static final String TENANT = "42";
    private static final String ORG = "org-1";
    private static final String OUR_BASE = "https://app.example.com";

    private ChatChannelBotRepository botRepository;
    private ChatChannelLinkRepository linkRepository;
    private ChatChannelConnector connector;
    private CredentialClient credentialClient;
    private ChatChannelService service;

    @BeforeEach
    void setUp() {
        botRepository = mock(ChatChannelBotRepository.class);
        linkRepository = mock(ChatChannelLinkRepository.class);
        connector = mock(ChatChannelConnector.class);
        credentialClient = mock(CredentialClient.class);

        when(connector.channelId()).thenReturn("telegram");

        // Saves echo the entity back with an id, as JPA would.
        when(botRepository.save(any())).thenAnswer(invocation -> {
            ChatChannelBotEntity bot = invocation.getArgument(0);
            if (bot.getId() == null) {
                bot.setId(UUID.randomUUID());
            }
            return bot;
        });
        when(linkRepository.save(any())).thenAnswer(invocation -> {
            ChatChannelLinkEntity link = invocation.getArgument(0);
            if (link.getId() == null) {
                link.setId(UUID.randomUUID());
            }
            return link;
        });
        when(botRepository.findByOrganizationIdAndChannelAndCredentialId(anyString(), anyString(), anyLong()))
                .thenReturn(Optional.empty());
        when(linkRepository.findByBotIdAndChatId(any(), anyString())).thenReturn(Optional.empty());
        when(linkRepository.findByOrganizationIdAndIsDefaultTrueAndActiveTrue(anyString()))
                .thenReturn(Optional.empty());

        // Telegram's own answer, not a mock's null: the connect flow asks a connector what it
        // can do before it calls a method whose only honest answer would be "I cannot".
        when(connector.capabilities()).thenCallRealMethod();
        // The interface's own defaults for everything a provider MAY override, for the same
        // reason: a bare mock answers null, and the connect path reads each of these. Telegram
        // overrides none of them, which is exactly what these defaults describe.
        when(connector.verifyBot(anyString(), anyLong(), any())).thenCallRealMethod();
        when(connector.credentialIntegration()).thenCallRealMethod();
        when(connector.accountSettingLabel()).thenCallRealMethod();
        when(connector.newInboundKey(any(), any())).thenCallRealMethod();
        when(connector.storedAccountSetting(any(), any())).thenCallRealMethod();
        when(connector.setupInstructions(any(), any())).thenCallRealMethod();
        when(connector.normalizeChatId(any())).thenCallRealMethod();
        when(connector.identifiesPresser()).thenCallRealMethod();
        when(connector.undeliveredHint()).thenCallRealMethod();

        service = new ChatChannelService(botRepository, linkRepository,
                new ChatChannelConnectorRegistry(List.of(connector)), credentialClient,
                immediateTransactionManager(), OUR_BASE);
    }

    /** Runs the callback inline: these tests are about decisions, not transactions. */
    private static PlatformTransactionManager immediateTransactionManager() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        return manager;
    }

    /**
     * Same, except the {@code failing}-th commit raises the partial unique index.
     *
     * <p>The violation is raised at COMMIT, not at the save, because that is where Postgres
     * raises it: the losing transaction runs to completion on its own snapshot and only
     * discovers the other default when it tries to make its work permanent. A test that
     * threw from {@code save} would pass against a catch placed too narrowly to help.
     */
    private static PlatformTransactionManager transactionManagerFailingCommit(int failing) {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        TransactionStatus status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        int[] commits = {0};
        doAnswer(invocation -> {
            if (++commits[0] == failing) {
                throw new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"uq_chat_channel_links_default\"");
            }
            return null;
        }).when(manager).commit(any());
        return manager;
    }

    /** Rebuilds the service on a transaction manager of this test's choosing. */
    private void serviceOn(PlatformTransactionManager manager) {
        service = new ChatChannelService(botRepository, linkRepository,
                new ChatChannelConnectorRegistry(List.of(connector)), credentialClient,
                manager, OUR_BASE);
    }

    private void botAnswers() {
        when(connector.verifyBot(anyString(), anyLong()))
                .thenReturn(Outcome.of(new BotIdentity("77", "ops_bot", "Ops")));
    }

    private void webhookSlotIsEmpty() {
        when(connector.currentWebhookUrl(anyString(), anyLong())).thenReturn(Outcome.of(Optional.empty()));
        when(connector.applyWebhook(anyString(), anyLong(), anyString())).thenReturn(Outcome.of(null));
    }

    private void deliveryWorks() {
        when(connector.sendTest(anyString(), anyLong(), anyString(), anyString())).thenReturn(Outcome.of(null));
    }

    private ConnectResult connect() {
        return service.connect(TENANT, ORG,
                new ConnectRequest("telegram", 9L, "-100123", "Ops room", "group", false));
    }

    @Nested
    @DisplayName("connect()")
    class Connect {

        /**
         * Prod 2026-09-23: a person typed their bot's own @username as the destination. Telegram
         * refused the test message ("the bot can't send messages to the bot"), but only after the
         * webhook slot was taken and a dead destination saved.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"@ops_bot", "ops_bot", "@OPS_BOT", " @ops_bot ", "77"})
        @DisplayName("refuses the bot itself as the destination, before storing or pointing anything")
        void refusesTheBotItself(String destination) {
            botAnswers();

            assertThatThrownBy(() -> service.connect(TENANT, ORG,
                    new ConnectRequest("telegram", 9L, destination, null, null, false)))
                    .isInstanceOf(ChatChannelException.class)
                    .hasMessageContaining("the bot itself (@ops_bot)")
                    .hasMessageContaining("@userinfobot");
            verify(connector, never()).currentWebhookUrl(anyString(), anyLong());
            verify(connector, never()).applyWebhook(anyString(), anyLong(), anyString());
            verify(botRepository, never()).save(any());
            verify(linkRepository, never()).save(any());
            verify(connector, never()).sendTest(anyString(), anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("a chat whose id only resembles the bot's is still accepted")
        void similarButDifferentDestinationIsAccepted() {
            assertThat(ChatChannelService.isTheBotItself("@ops_bot_group", new BotIdentity("77", "ops_bot", "Ops")))
                    .isFalse();
            assertThat(ChatChannelService.isTheBotItself("770", new BotIdentity("77", "ops_bot", "Ops"))).isFalse();
            assertThat(ChatChannelService.isTheBotItself("-100123", new BotIdentity("77", null, null))).isFalse();
            assertThat(ChatChannelService.isTheBotItself(null, new BotIdentity("77", "ops_bot", "Ops"))).isFalse();
        }

        @Test
        @DisplayName("points the webhook at this installation when the bot's slot is free")
        void pointsWebhookWhenSlotFree() {
            botAnswers();
            webhookSlotIsEmpty();
            deliveryWorks();

            ConnectResult result = connect();

            assertThat(result.webhook()).isEqualTo(WebhookOutcome.POINTED);
            // The secret is the connector's own now, so the shared flow does not carry one.
            verify(connector).applyWebhook(TENANT, 9L, OUR_BASE + "/approval-callback/telegram");
        }

        @Test
        @DisplayName("never overwrites a webhook pointing at a server we do not control")
        void leavesForeignWebhookAlone() {
            botAnswers();
            when(connector.currentWebhookUrl(anyString(), anyLong()))
                    .thenReturn(Outcome.of(Optional.of("https://someone-else.example.org/hook")));
            deliveryWorks();

            ConnectResult result = connect();

            // The whole point: a bot wired to another system keeps working, and the
            // caller is told buttons will not come back rather than discovering it later.
            assertThat(result.webhook()).isEqualTo(WebhookOutcome.FOREIGN);
            verify(connector, never()).applyWebhook(anyString(), anyLong(), anyString());
            assertThat(result.warning()).contains("buttons pressed here will not come back");
        }

        @Test
        @DisplayName("keeps a webhook that is another URL of this same installation")
        void keepsInstallationWebhook() {
            botAnswers();
            // What a bot already webhooked to a workflow trigger looks like.
            when(connector.currentWebhookUrl(anyString(), anyLong()))
                    .thenReturn(Outcome.of(Optional.of(OUR_BASE + "/api/webhooks/abc123")));
            deliveryWorks();

            ConnectResult result = connect();

            assertThat(result.webhook()).isEqualTo(WebhookOutcome.KEPT_INSTALLATION_URL);
            verify(connector, never()).applyWebhook(anyString(), anyLong(), anyString());
            // Inbound still reaches us on that path, so there is nothing to warn about.
            assertThat(result.warning()).isNull();
        }

        @Test
        @DisplayName("reports the exact URL match as already ours, without re-pointing it")
        void recognisesOwnWebhook() {
            botAnswers();
            when(connector.currentWebhookUrl(anyString(), anyLong()))
                    .thenReturn(Outcome.of(Optional.of(OUR_BASE + "/approval-callback/telegram")));
            deliveryWorks();

            assertThat(connect().webhook()).isEqualTo(WebhookOutcome.ALREADY_OURS);
            verify(connector, never()).applyWebhook(anyString(), anyLong(), anyString());
        }

        @Test
        @DisplayName("a destination that received nothing is saved, not activated, and never the default")
        void undeliveredDestinationIsNotUsable() {
            botAnswers();
            webhookSlotIsEmpty();
            when(connector.sendTest(anyString(), anyLong(), anyString(), anyString()))
                    .thenReturn(Outcome.failed("Forbidden: bot was blocked by the user"));

            ConnectResult result = connect();

            assertThat(result.delivered()).isFalse();
            assertThat(result.channel().isDefault()).isFalse();
            assertThat(result.channel().active()).isFalse();
            assertThat(result.channel().verifiedAt()).isNull();
            assertThat(result.warning()).contains("nothing was delivered")
                    .contains("bot was blocked by the user");
            verify(linkRepository, never()).clearDefault(anyString());
        }

        @Test
        @DisplayName("the first destination that delivers becomes the default on its own")
        void firstDeliveredDestinationBecomesDefault() {
            botAnswers();
            webhookSlotIsEmpty();
            deliveryWorks();

            ConnectResult result = connect();

            // Without this, a workspace with one connected chat has no default and
            // every delivery surface silently resolves nothing.
            assertThat(result.delivered()).isTrue();
            assertThat(result.channel().isDefault()).isTrue();
        }

        @Test
        @DisplayName("does not steal the default from an existing destination unless asked")
        void keepsExistingDefault() {
            botAnswers();
            webhookSlotIsEmpty();
            deliveryWorks();
            ChatChannelLinkEntity existing = new ChatChannelLinkEntity();
            existing.setId(UUID.randomUUID());
            when(linkRepository.findByOrganizationIdAndIsDefaultTrueAndActiveTrue(ORG))
                    .thenReturn(Optional.of(existing));

            assertThat(connect().channel().isDefault()).isFalse();
            verify(linkRepository, never()).clearDefault(anyString());
        }

        @Test
        @DisplayName("moves the default when the caller asks for it")
        void makeDefaultMovesTheSlot() {
            botAnswers();
            webhookSlotIsEmpty();
            deliveryWorks();
            ChatChannelLinkEntity existing = new ChatChannelLinkEntity();
            existing.setId(UUID.randomUUID());
            when(linkRepository.findByOrganizationIdAndIsDefaultTrueAndActiveTrue(ORG))
                    .thenReturn(Optional.of(existing));

            ConnectResult result = service.connect(TENANT, ORG,
                    new ConnectRequest("telegram", 9L, "-100123", "Ops room", "group", true));

            assertThat(result.channel().isDefault()).isTrue();
            verify(linkRepository).clearDefault(ORG);
        }

        @Test
        @DisplayName("stores the allow-list, which nothing could write before")
        void storesTheAllowList() {
            botAnswers();
            webhookSlotIsEmpty();
            deliveryWorks();

            service.connect(TENANT, ORG, new ConnectRequest("telegram", 9L, "-100123", "Ops room",
                    "group", false, List.of("777")));

            ArgumentCaptor<ChatChannelLinkEntity> saved = ArgumentCaptor.forClass(ChatChannelLinkEntity.class);
            verify(linkRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
            // The column, the read and the enforcement all existed; no caller could set it,
            // so every connected group was open to any of its members.
            assertThat(saved.getValue().getAllowedUserIds()).containsExactly("777");
        }

        @Test
        @DisplayName("a reconnect that says nothing about the allow-list does not widen it")
        void reconnectDoesNotWidenTheAllowList() {
            botAnswers();
            webhookSlotIsEmpty();
            deliveryWorks();
            ChatChannelLinkEntity existing = new ChatChannelLinkEntity();
            existing.setId(UUID.randomUUID());
            existing.setChatId("-100123");
            existing.setAllowedUserIds(List.of("777"));
            when(linkRepository.findByBotIdAndChatId(any(), anyString())).thenReturn(Optional.of(existing));

            // Both shapes of "said nothing": the back-compat constructor and an explicit null.
            // The 6-argument one used to pass List.of(), which made this test pass for the wrong
            // reason - the writer ignored empty lists, so it could not have widened anything.
            service.connect(TENANT, ORG,
                    new ConnectRequest("telegram", 9L, "-100123", "Ops room", "group", false));
            service.connect(TENANT, ORG,
                    new ConnectRequest("telegram", 9L, "-100123", "Ops room", "group", false, null));

            // Silently going back to "anyone in the chat decides" is exactly the widening
            // this restriction exists to prevent.
            assertThat(existing.getAllowedUserIds()).containsExactly("777");
        }

        @Test
        @DisplayName("a reconnect with an empty allow-list clears it, which is the only way to")
        void reconnectWithAnEmptyListClearsTheAllowList() {
            // Pre-fix the writer ignored an empty list, so a restriction could be replaced but
            // never removed: the only route back to "anyone in the chat decides" was to
            // disconnect the destination and connect it again.
            botAnswers();
            webhookSlotIsEmpty();
            deliveryWorks();
            ChatChannelLinkEntity existing = new ChatChannelLinkEntity();
            existing.setId(UUID.randomUUID());
            existing.setChatId("-100123");
            existing.setAllowedUserIds(List.of("777"));
            when(linkRepository.findByBotIdAndChatId(any(), anyString())).thenReturn(Optional.of(existing));

            service.connect(TENANT, ORG,
                    new ConnectRequest("telegram", 9L, "-100123", "Ops room", "group", false, List.of()));

            assertThat(existing.getAllowedUserIds())
                    .as("an empty list is the caller asking to open it to anyone in the chat")
                    .isEmpty();
        }

        @Test
        @DisplayName("the summary carries the allow-list, so it is readable at all")
        void summaryCarriesTheAllowList() {
            ChatChannelLinkEntity existing = new ChatChannelLinkEntity();
            existing.setId(UUID.randomUUID());
            existing.setBotId(UUID.randomUUID());
            existing.setChatId("-100123");
            existing.setAllowedUserIds(List.of("777", "888"));
            existing.setActive(true);
            ChatChannelBotEntity bot = new ChatChannelBotEntity();
            bot.setId(existing.getBotId());
            bot.setChannel("telegram");
            bot.setCredentialId(9L);
            when(linkRepository.findByOrganizationIdOrderByIsDefaultDescCreatedAtAsc(ORG))
                    .thenReturn(List.of(existing));
            when(botRepository.findByIdAndOrganizationId(existing.getBotId(), ORG))
                    .thenReturn(Optional.of(bot));

            var summaries = service.list(ORG);

            assertThat(summaries).hasSize(1);
            assertThat(summaries.get(0).allowedUserIds())
                    .as("the restriction was writable and invisible before this")
                    .containsExactly("777", "888");
        }

        @Test
        @DisplayName("refuses a channel this deployment cannot deliver to")
        void refusesUnknownChannel() {
            assertThatThrownBy(() -> service.connect(TENANT, ORG,
                    new ConnectRequest("whatsapp", 9L, "1", null, null, false)))
                    .isInstanceOf(ChatChannelException.class)
                    .hasMessageContaining("cannot be connected here")
                    .hasMessageContaining("telegram");
        }

        @Test
        @DisplayName("says what to do when the workspace has no connection for the channel")
        void refusesWithoutCredential() {
            when(credentialClient.getDefaultCredential(TENANT, "telegram")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.connect(TENANT, ORG,
                    new ConnectRequest("telegram", null, "1", null, null, false)))
                    .isInstanceOf(ChatChannelException.class)
                    .hasMessageContaining("Connect the bot's token first");
        }

        @Test
        @DisplayName("falls back to the workspace's default credential when none is named")
        void resolvesDefaultCredential() {
            CredentialSummaryDto credential = new CredentialSummaryDto();
            credential.setId(31L);
            when(credentialClient.getDefaultCredential(TENANT, "telegram")).thenReturn(Optional.of(credential));
            botAnswers();
            webhookSlotIsEmpty();
            deliveryWorks();

            service.connect(TENANT, ORG, new ConnectRequest("telegram", null, "55", null, null, false));

            verify(connector).verifyBot(TENANT, 31L);
        }

        @Test
        @DisplayName("stops at the bot check rather than storing a destination for a bot that does not answer")
        void refusesWhenBotDoesNotAnswer() {
            when(connector.verifyBot(anyString(), anyLong()))
                    .thenReturn(Outcome.failed("Unauthorized: the bot token was revoked"));

            assertThatThrownBy(ChatChannelServiceTest.this::connectDirect)
                    .isInstanceOf(ChatChannelException.class)
                    .hasMessageContaining("token was revoked");
            verify(linkRepository, never()).save(any());
        }

        @Test
        @DisplayName("requires a destination")
        void refusesBlankChatId() {
            assertThatThrownBy(() -> service.connect(TENANT, ORG,
                    new ConnectRequest("telegram", 9L, "  ", null, null, false)))
                    .isInstanceOf(ChatChannelException.class)
                    .hasMessageContaining("chat id is required");
        }
    }

    private ConnectResult connectDirect() {
        return connect();
    }

    @Nested
    @DisplayName("connect() on a provider whose callback URL is not ours to set")
    class ConnectWithoutWebhookControl {

        @BeforeEach
        void providerOwnsItsOwnCallbackUrl() {
            botAnswers();
            deliveryWorks();
            // Set in the Meta dashboard (WhatsApp) or the app manifest (Slack), never by us.
            when(connector.capabilities()).thenReturn(new ChatChannelConnector.Capabilities(true, false));
        }

        @Test
        @DisplayName("does not read or write the provider's callback URL")
        void leavesTheCallbackUrlAlone() {
            connect();

            // Calling either would be asking a provider a question it has no answer to, and
            // the failure would be reported to the person as a problem with their connect.
            verify(connector, never()).currentWebhookUrl(anyString(), anyLong());
            verify(connector, never()).applyWebhook(anyString(), anyLong(), anyString());
        }

        @Test
        @DisplayName("reports that the step did not apply, not that it failed")
        void doesNotReportAProblemItDidNotHave() {
            ConnectResult result = connect();

            assertThat(result.webhook()).isEqualTo(ChatChannelService.WebhookOutcome.NOT_OURS_TO_SET);
            // FAILED would tell the person their buttons may not come back, about a step that
            // was never theirs to take.
            assertThat(result.warning()).isNull();
            assertThat(result.delivered()).isTrue();
        }
    }

    @Nested
    @DisplayName("setDefault()")
    class SetDefault {

        @Test
        @DisplayName("refuses a destination that has never received anything")
        void refusesUnverified() {
            ChatChannelLinkEntity link = new ChatChannelLinkEntity();
            UUID id = UUID.randomUUID();
            link.setId(id);
            link.setVerifiedAt(null);
            when(linkRepository.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.of(link));

            assertThatThrownBy(() -> service.setDefault(ORG, id))
                    .isInstanceOf(ChatChannelException.class)
                    .hasMessageContaining("never received a message");
            verify(linkRepository, never()).clearDefault(anyString());
        }

        @Test
        @DisplayName("refuses a deactivated destination")
        void refusesInactive() {
            ChatChannelLinkEntity link = new ChatChannelLinkEntity();
            UUID id = UUID.randomUUID();
            link.setId(id);
            link.setVerifiedAt(Instant.now());
            link.setActive(false);
            when(linkRepository.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.of(link));

            assertThatThrownBy(() -> service.setDefault(ORG, id))
                    .isInstanceOf(ChatChannelException.class)
                    .hasMessageContaining("deactivated");
        }

        @Test
        @DisplayName("clears the previous default before setting the new one")
        void clearsBeforeSetting() {
            ChatChannelLinkEntity link = new ChatChannelLinkEntity();
            UUID id = UUID.randomUUID();
            UUID botId = UUID.randomUUID();
            link.setId(id);
            link.setBotId(botId);
            link.setVerifiedAt(Instant.now());
            link.setActive(true);
            ChatChannelBotEntity bot = new ChatChannelBotEntity();
            bot.setId(botId);
            bot.setChannel("telegram");
            bot.setCredentialId(9L);
            when(linkRepository.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.of(link));
            when(botRepository.findByIdAndOrganizationId(botId, ORG)).thenReturn(Optional.of(bot));

            assertThat(service.setDefault(ORG, id).isDefault()).isTrue();
            verify(linkRepository).clearDefault(ORG);
        }
    }

    @Nested
    @DisplayName("resolveDefault()")
    class ResolveDefault {

        @Test
        @DisplayName("is empty for a workspace with nothing connected")
        void emptyWithoutLink() {
            assertThat(service.resolveDefault(ORG)).isEmpty();
        }

        @Test
        @DisplayName("is empty rather than throwing when there is no workspace at all")
        void emptyWithoutOrg() {
            // Reached from unattended execution paths, where a blank scope is normal.
            assertThat(service.resolveDefault(null)).isEmpty();
            assertThat(service.resolveDefault("  ")).isEmpty();
        }

        @Test
        @DisplayName("carries the bot's credential so the caller sends with the right bot")
        void carriesCredential() {
            UUID botId = UUID.randomUUID();
            ChatChannelLinkEntity link = new ChatChannelLinkEntity();
            link.setId(UUID.randomUUID());
            link.setBotId(botId);
            link.setChatId("-100999");
            link.setAllowedUserIds(List.of("5"));
            ChatChannelBotEntity bot = new ChatChannelBotEntity();
            bot.setId(botId);
            bot.setChannel("telegram");
            bot.setCredentialId(12L);
            when(linkRepository.findByOrganizationIdAndIsDefaultTrueAndActiveTrue(ORG))
                    .thenReturn(Optional.of(link));
            when(botRepository.findByIdAndOrganizationId(botId, ORG)).thenReturn(Optional.of(bot));

            var target = service.resolveDefault(ORG).orElseThrow();

            assertThat(target.channel()).isEqualTo("telegram");
            assertThat(target.credentialId()).isEqualTo(12L);
            assertThat(target.chatId()).isEqualTo("-100999");
            assertThat(target.allowedUserIds()).containsExactly("5");
        }
    }

    @Nested
    @DisplayName("resolveFor() - a chosen destination, or the default")
    class ResolveFor {

        private final UUID botId = UUID.randomUUID();
        private final UUID chosenId = UUID.randomUUID();

        private ChatChannelLinkEntity chosen(boolean active) {
            ChatChannelLinkEntity link = new ChatChannelLinkEntity();
            link.setId(chosenId);
            link.setBotId(botId);
            link.setChatId("C-finance");
            link.setActive(active);
            ChatChannelBotEntity bot = new ChatChannelBotEntity();
            bot.setId(botId);
            bot.setChannel("slack");
            bot.setCredentialId(21L);
            when(botRepository.findByIdAndOrganizationId(botId, ORG)).thenReturn(Optional.of(bot));
            return link;
        }

        @Test
        @DisplayName("no choice is the workspace default")
        void noChoiceIsDefault() {
            UUID defaultBot = UUID.randomUUID();
            ChatChannelLinkEntity link = new ChatChannelLinkEntity();
            link.setId(UUID.randomUUID());
            link.setBotId(defaultBot);
            link.setChatId("-100999");
            ChatChannelBotEntity bot = new ChatChannelBotEntity();
            bot.setId(defaultBot);
            bot.setChannel("telegram");
            bot.setCredentialId(12L);
            when(linkRepository.findByOrganizationIdAndIsDefaultTrueAndActiveTrue(ORG)).thenReturn(Optional.of(link));
            when(botRepository.findByIdAndOrganizationId(defaultBot, ORG)).thenReturn(Optional.of(bot));

            assertThat(service.resolveFor(ORG, null).orElseThrow().chatId()).isEqualTo("-100999");
        }

        @Test
        @DisplayName("a chosen active destination of this workspace is used, with its own bot")
        void chosenIsUsed() {
            ChatChannelLinkEntity link = chosen(true);
            when(linkRepository.findByIdAndOrganizationId(chosenId, ORG)).thenReturn(Optional.of(link));

            var target = service.resolveFor(ORG, chosenId).orElseThrow();

            assertThat(target.linkId()).isEqualTo(chosenId);
            assertThat(target.channel()).isEqualTo("slack");
            assertThat(target.credentialId()).isEqualTo(21L);
            assertThat(target.chatId()).isEqualTo("C-finance");
            // Looked up in THIS workspace only: an id from another workspace can never resolve.
            verify(linkRepository).findByIdAndOrganizationId(chosenId, ORG);
        }

        @Test
        @DisplayName("a switched-off destination is not used, and the default does NOT stand in for it")
        void inactiveIsEmpty() {
            ChatChannelLinkEntity link = chosen(false);
            when(linkRepository.findByIdAndOrganizationId(chosenId, ORG)).thenReturn(Optional.of(link));

            assertThat(service.resolveFor(ORG, chosenId)).isEmpty();
            verify(linkRepository, never()).findByOrganizationIdAndIsDefaultTrueAndActiveTrue(any());
        }

        @Test
        @DisplayName("a disconnected (or foreign) destination is empty, never the default")
        void goneIsEmpty() {
            when(linkRepository.findByIdAndOrganizationId(chosenId, ORG)).thenReturn(Optional.empty());

            assertThat(service.resolveFor(ORG, chosenId)).isEmpty();
            verify(linkRepository, never()).findByOrganizationIdAndIsDefaultTrueAndActiveTrue(any());
        }

        @Test
        @DisplayName("no workspace resolves nothing")
        void noWorkspace() {
            assertThat(service.resolveLink(null, chosenId)).isEmpty();
            assertThat(service.resolveLink(" ", chosenId)).isEmpty();
        }
    }

    @Nested
    @DisplayName("discover()")
    class Discover {

        @Test
        @DisplayName("relays the provider's refusal as a notice, with the bot still named, instead of an empty list")
        void relaysRefusal() {
            botAnswers();
            when(connector.discoverChats(anyString(), anyLong()))
                    .thenReturn(Outcome.unavailable("Telegram cannot list this bot's chats: the bot is already connected."));

            ChatChannelService.DiscoveryResult result = service.discover(TENANT, "telegram", 9L);

            // "No chats" and "I was not allowed to look" send the user to completely
            // different places, so they must never collapse into the same answer: the notice
            // is what tells them apart. The bot is still named, since opening it is the next step.
            assertThat(result.notice()).contains("already connected");
            assertThat(result.chats()).isEmpty();
            assertThat(result.botUsername()).isEqualTo("ops_bot");
        }

        @Test
        @DisplayName("a failed call is an error, never a notice that the bot is already connected")
        void failedListIsAnError() {
            botAnswers();
            when(connector.discoverChats(anyString(), anyLong()))
                    .thenReturn(Outcome.failed("Telegram call failed: Read timed out"));

            assertThatThrownBy(() -> service.discover(TENANT, "telegram", 9L))
                    .isInstanceOf(ChatChannelException.class)
                    .hasMessageContaining("Read timed out");
        }

        @Test
        @DisplayName("a list that was read carries no notice")
        void listedChatsHaveNoNotice() {
            botAnswers();
            when(connector.discoverChats(anyString(), anyLong())).thenReturn(Outcome.of(List.of()));

            assertThat(service.discover(TENANT, "telegram", 9L).notice()).isNull();
        }

        @Test
        @DisplayName("says a provider has no chat list, instead of answering nobody wrote to you")
        void refusesToDiscoverWhereThereIsNoList() {
            botAnswers();
            // WhatsApp Business, Slack DMs: the destination is one the person already has, and
            // there is no "chats my bot has heard from" to read.
            when(connector.capabilities()).thenReturn(new ChatChannelConnector.Capabilities(false, true));

            assertThatThrownBy(() -> service.discover(TENANT, "telegram", 9L))
                    .isInstanceOf(ChatChannelException.class)
                    .hasMessageContaining("no list of chats")
                    .hasMessageContaining("giving its id");

            // An empty list would read as "nobody has written to your bot" and send them
            // looking for a message they never had to send.
            verify(connector, never()).discoverChats(anyString(), anyLong());
        }

        @Test
        @DisplayName("returns the candidates with the bot that saw them")
        void returnsCandidates() {
            botAnswers();
            when(connector.discoverChats(anyString(), anyLong())).thenReturn(Outcome.of(
                    List.of(new ChatCandidate("-100123", "Ops room", "group", "lea"))));

            var result = service.discover(TENANT, "telegram", 9L);

            assertThat(result.botUsername()).isEqualTo("ops_bot");
            assertThat(result.credentialId()).isEqualTo(9L);
            assertThat(result.chats()).singleElement()
                    .extracting(ChatCandidate::chatId, ChatCandidate::title)
                    .containsExactly("-100123", "Ops room");
        }
    }

    @Nested
    @DisplayName("disconnect()")
    class Disconnect {

        @Test
        @DisplayName("removes the bot once its last destination is gone")
        void removesOrphanBot() {
            UUID botId = UUID.randomUUID();
            UUID linkId = UUID.randomUUID();
            ChatChannelLinkEntity link = new ChatChannelLinkEntity();
            link.setId(linkId);
            link.setBotId(botId);
            ChatChannelBotEntity bot = new ChatChannelBotEntity();
            bot.setId(botId);
            when(linkRepository.findByIdAndOrganizationId(linkId, ORG)).thenReturn(Optional.of(link));
            when(linkRepository.findByBotId(botId)).thenReturn(List.of());
            when(botRepository.findByIdAndOrganizationId(botId, ORG)).thenReturn(Optional.of(bot));

            service.disconnect(ORG, linkId);

            verify(linkRepository).delete(link);
            verify(botRepository).delete(bot);
        }

        @Test
        @DisplayName("keeps the bot while another destination still uses it")
        void keepsSharedBot() {
            UUID botId = UUID.randomUUID();
            UUID linkId = UUID.randomUUID();
            ChatChannelLinkEntity link = new ChatChannelLinkEntity();
            link.setId(linkId);
            link.setBotId(botId);
            ChatChannelLinkEntity sibling = new ChatChannelLinkEntity();
            sibling.setId(UUID.randomUUID());
            when(linkRepository.findByIdAndOrganizationId(linkId, ORG)).thenReturn(Optional.of(link));
            when(linkRepository.findByBotId(botId)).thenReturn(List.of(sibling));

            service.disconnect(ORG, linkId);

            verify(linkRepository).delete(link);
            verify(botRepository, never()).delete(any());
        }

        @Test
        @DisplayName("refuses an id from another workspace")
        void refusesForeignId() {
            UUID linkId = UUID.randomUUID();
            when(linkRepository.findByIdAndOrganizationId(linkId, ORG)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.disconnect(ORG, linkId))
                    .isInstanceOf(ChatChannelException.class)
                    .hasMessageContaining("No connected chat with that id");
        }

        @Test
        @DisplayName("hands the default to the oldest destination still able to receive")
        void promotesOldestSuccessor() {
            ChatChannelLinkEntity removed = usable(Instant.parse("2026-01-01T00:00:00Z"));
            removed.setDefault(true);
            ChatChannelLinkEntity older = usable(Instant.parse("2026-02-01T00:00:00Z"));
            ChatChannelLinkEntity newer = usable(Instant.parse("2026-03-01T00:00:00Z"));
            disconnecting(removed, List.of(removed, newer, older));

            service.disconnect(ORG, removed.getId());

            // Without this the workspace keeps a chat and loses its default, and every
            // unattended run then resolves NO_CHANNEL with nobody told why. Oldest wins
            // because it is the one this workspace has been using longest.
            assertThat(older.isDefault()).isTrue();
            assertThat(newer.isDefault()).isFalse();
            verify(linkRepository).save(older);
        }

        @Test
        @DisplayName("leaves the row it just deleted out of the succession")
        void neverPromotesTheRemovedRow() {
            ChatChannelLinkEntity removed = usable(Instant.parse("2026-01-01T00:00:00Z"));
            removed.setDefault(true);
            ChatChannelLinkEntity successor = usable(Instant.parse("2026-02-01T00:00:00Z"));
            // The delete is queued in the persistence context, so a read in the same
            // transaction still returns the row. It is the oldest, so an id filter is the
            // only thing keeping the default on a destination that no longer exists.
            disconnecting(removed, List.of(removed, successor));

            service.disconnect(ORG, removed.getId());

            assertThat(successor.isDefault()).isTrue();
            verify(linkRepository, never()).save(removed);
        }

        @Test
        @DisplayName("skips a chat that never received a message and one that is deactivated")
        void skipsUnusableSuccessors() {
            ChatChannelLinkEntity removed = usable(Instant.parse("2026-01-01T00:00:00Z"));
            removed.setDefault(true);
            ChatChannelLinkEntity unverified = usable(Instant.parse("2026-02-01T00:00:00Z"));
            unverified.setVerifiedAt(null);
            ChatChannelLinkEntity deactivated = usable(Instant.parse("2026-03-01T00:00:00Z"));
            deactivated.setActive(false);
            ChatChannelLinkEntity usable = usable(Instant.parse("2026-04-01T00:00:00Z"));
            disconnecting(removed, List.of(removed, unverified, deactivated, usable));

            service.disconnect(ORG, removed.getId());

            // Both are refused by setDefault for the same reasons, and the default lookup
            // filters on active anyway: promoting either would look like a default and
            // behave like none, which is worse than having none.
            assertThat(unverified.isDefault()).isFalse();
            assertThat(deactivated.isDefault()).isFalse();
            assertThat(usable.isDefault()).isTrue();
        }

        @Test
        @DisplayName("leaves no default when nothing left can take it")
        void leavesNoDefaultWhenNothingQualifies() {
            ChatChannelLinkEntity removed = usable(Instant.parse("2026-01-01T00:00:00Z"));
            removed.setDefault(true);
            ChatChannelLinkEntity deactivated = usable(Instant.parse("2026-02-01T00:00:00Z"));
            deactivated.setActive(false);
            disconnecting(removed, List.of(removed, deactivated));

            service.disconnect(ORG, removed.getId());

            // No default is then the truth about this workspace rather than a silence, and
            // reconnecting is what fixes it.
            assertThat(deactivated.isDefault()).isFalse();
            verify(linkRepository, never()).save(any());
        }

        @Test
        @DisplayName("touches nobody's default when the destination was not the default")
        void doesNotReshuffleOnANonDefault() {
            ChatChannelLinkEntity removed = usable(Instant.parse("2026-02-01T00:00:00Z"));
            ChatChannelLinkEntity current = usable(Instant.parse("2026-01-01T00:00:00Z"));
            current.setDefault(true);
            disconnecting(removed, List.of(removed, current));

            service.disconnect(ORG, removed.getId());

            // The workspace already has a default and it is not the one going away, so the
            // succession must not run: it would hand the slot to the oldest, which is a
            // silent change of destination nobody asked for.
            assertThat(current.isDefault()).isTrue();
            verify(linkRepository, never()).findByOrganizationIdOrderByIsDefaultDescCreatedAtAsc(anyString());
        }

        /** A destination that could legitimately be promoted: connected, active, delivered. */
        private ChatChannelLinkEntity usable(Instant createdAt) {
            ChatChannelLinkEntity link = new ChatChannelLinkEntity();
            link.setId(UUID.randomUUID());
            link.setBotId(UUID.randomUUID());
            link.setOrganizationId(ORG);
            link.setActive(true);
            link.setVerifiedAt(Instant.parse("2026-01-01T00:00:00Z"));
            link.setCreatedAt(createdAt);
            return link;
        }

        /** Wires the repository so disconnecting {@code target} sees {@code workspace}. */
        private void disconnecting(ChatChannelLinkEntity target, List<ChatChannelLinkEntity> workspace) {
            when(linkRepository.findByIdAndOrganizationId(target.getId(), ORG))
                    .thenReturn(Optional.of(target));
            when(linkRepository.findByOrganizationIdOrderByIsDefaultDescCreatedAtAsc(ORG))
                    .thenReturn(workspace);
            when(linkRepository.findByBotId(target.getBotId()))
                    .thenReturn(List.of(new ChatChannelLinkEntity()));
        }

        @Test
        @DisplayName("still forgets the destination when a connect wins the vacant slot first")
        void survivesLosingTheSuccession() {
            ChatChannelLinkEntity removed = usable(Instant.parse("2026-01-01T00:00:00Z"));
            removed.setDefault(true);
            ChatChannelLinkEntity successor = usable(Instant.parse("2026-02-01T00:00:00Z"));
            disconnecting(removed, List.of(removed, successor));
            // Commit 1 is the delete, commit 2 is the succession: a connect committed its
            // own default in between and the index refused this one.
            serviceOn(transactionManagerFailingCommit(2));

            assertThatCode(() -> service.disconnect(ORG, removed.getId())).doesNotThrowAnyException();

            // The disconnect is what was asked for and it is done. Failing it because the
            // consolation prize was taken would leave a destination the user asked to forget.
            verify(linkRepository).delete(removed);
        }
    }

    @Nested
    @DisplayName("the single default slot under a race")
    class DefaultSlotRace {

        @Test
        @DisplayName("connect keeps the destination when another connect takes the slot first")
        void connectSurvivesLosingTheSlot() {
            botAnswers();
            webhookSlotIsEmpty();
            deliveryWorks();
            // Commit 1 saves the link, commit 2 claims the default. Two connects into an
            // empty workspace both read no default, both write one, and the partial unique
            // index lets exactly one through.
            serviceOn(transactionManagerFailingCommit(2));

            ConnectResult result = connect();

            // The test message reached a real chat, so the destination has to be recorded
            // whatever happened to the slot. Raising the constraint here would strand a row
            // a real person has already been notified about, and the next attempt would
            // send them a second "connected" message.
            assertThat(result.delivered()).isTrue();
            assertThat(result.channel().isDefault()).isFalse();
            verify(linkRepository, atLeastOnce()).save(any());
        }

        @Test
        @DisplayName("connect re-runs against the winner when the same chat is connected twice at once")
        void connectRetriesTheLinkAgainstTheWinner() {
            botAnswers();
            webhookSlotIsEmpty();
            deliveryWorks();
            ChatChannelLinkEntity winner = new ChatChannelLinkEntity();
            winner.setId(UUID.randomUUID());
            winner.setBotId(UUID.randomUUID());
            winner.setChatId("-100123");
            // Both calls read nothing and both insert; this one loses uq_chat_channel_links_bot_chat.
            // The second read is the winner's row, which by then exists.
            when(linkRepository.findByBotIdAndChatId(any(), anyString()))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(winner));
            // do-style, because when(mock.save(any())) CALLS the echoing answer that setUp
            // installed, with a null argument, and it dies inside the stubbing itself.
            doThrow(new DataIntegrityViolationException("duplicate key value violates unique "
                    + "constraint \"uq_chat_channel_links_bot_chat\""))
                    .doAnswer(invocation -> invocation.getArgument(0))
                    .when(linkRepository).save(any());

            ConnectResult result = connect();

            // Arriving second is not a reason to refuse a connect: the fields both calls are
            // writing are the same, so the loser is re-run against the row that won and the
            // result is what a sequential pair would have produced. Failing instead would put
            // a constraint name in front of somebody who double-clicked a button.
            assertThat(result.delivered()).isTrue();
            assertThat(result.channel().chatId()).isEqualTo("-100123");
        }

        @Test
        @DisplayName("connect re-runs the bot against the winner when two chats connect at once")
        void connectRetriesTheBotAgainstTheWinner() {
            botAnswers();
            webhookSlotIsEmpty();
            deliveryWorks();
            // The row the other call committed: it already carries the identity fields, which
            // is why the re-run only reapplies what this call is changing.
            ChatChannelBotEntity winner = new ChatChannelBotEntity();
            winner.setId(UUID.randomUUID());
            winner.setTenantId(TENANT);
            winner.setOrganizationId(ORG);
            winner.setChannel("telegram");
            winner.setCredentialId(9L);
            // Two DIFFERENT chats on the same bot token, connected together: both read no bot
            // row for that credential and both insert one, and uq_chat_channel_bots_scope lets
            // exactly one through.
            when(botRepository.findByOrganizationIdAndChannelAndCredentialId(anyString(), anyString(), anyLong()))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(winner));
            doThrow(new DataIntegrityViolationException("duplicate key value violates unique "
                    + "constraint \"uq_chat_channel_bots_scope\""))
                    .doAnswer(invocation -> invocation.getArgument(0))
                    .when(botRepository).save(any());

            ConnectResult result = connect();

            // The loser is re-run against the winner's row, so the second chat connects
            // normally instead of failing on a bot both calls were describing identically.
            assertThat(result.delivered()).isTrue();
            assertThat(result.channel().botUsername()).isEqualTo("ops_bot");
        }

        @Test
        @DisplayName("connect raises the collision it cannot explain away")
        void connectRaisesAnUnexplainedCollision() {
            botAnswers();
            webhookSlotIsEmpty();
            deliveryWorks();
            // The index fired but the row is still not there on the re-read, so the collision
            // was not the race this retry knows how to absorb. Swallowing it would turn an
            // unknown constraint failure into a connect that silently did nothing.
            when(linkRepository.findByBotIdAndChatId(any(), anyString())).thenReturn(Optional.empty());
            doThrow(new DataIntegrityViolationException("some other constraint"))
                    .when(linkRepository).save(any());

            assertThatThrownBy(ChatChannelServiceTest.this::connect)
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("set_default says the answer changed instead of leaking the constraint")
        void setDefaultReportsALostRace() {
            UUID linkId = UUID.randomUUID();
            ChatChannelLinkEntity link = new ChatChannelLinkEntity();
            link.setId(linkId);
            link.setBotId(UUID.randomUUID());
            link.setActive(true);
            link.setVerifiedAt(Instant.parse("2026-01-01T00:00:00Z"));
            when(linkRepository.findByIdAndOrganizationId(linkId, ORG)).thenReturn(Optional.of(link));
            serviceOn(transactionManagerFailingCommit(1));

            // Somebody asked for THIS destination by id, so unlike the connect path the loss
            // is reported. What must not reach them is the database sentence: the tool
            // surface turns any other exception into "Error: duplicate key value violates
            // unique constraint ...", which names nothing they can act on.
            assertThatThrownBy(() -> service.setDefault(ORG, linkId))
                    .isInstanceOf(ChatChannelException.class)
                    .hasMessageContaining("landed first")
                    .hasMessageContaining("try again")
                    .hasMessageNotContaining("constraint");
        }
    }

    @Nested
    @DisplayName("connect() for a provider that needs more than its credential")
    class ProviderSpecificConnect {

        private ConnectResult connectWith(String chatId, String accountSetting, List<String> allowed) {
            return service.connect(TENANT, ORG, new ConnectRequest("telegram", 9L, chatId, "Ops", "private",
                    false, allowed, accountSetting));
        }

        @Test
        @DisplayName("asks for the missing account setting by name, before any call to the provider")
        void refusesAMissingAccountSettingUpFront() {
            when(connector.accountSettingLabel()).thenReturn("the phone number id");

            // A WhatsApp account without its sending number cannot send anything; finding that
            // out from the provider would cost a round trip and word the refusal in its terms.
            assertThatThrownBy(() -> connectWith("+33612345678", null, null))
                    .isInstanceOf(ChatChannelException.class)
                    .hasMessageContaining("the phone number id");
            verify(connector, never()).verifyBot(anyString(), anyLong(), any());
        }

        @Test
        @DisplayName("a known bot reconnects without the setting: the stored one is recovered and verified")
        void reconnectRecoversTheStoredSetting() {
            when(connector.accountSettingLabel()).thenReturn("the phone number id");
            ChatChannelBotEntity known = new ChatChannelBotEntity();
            known.setId(UUID.randomUUID());
            known.setBotIdentity("106540352242922");
            known.setCredentialId(9L);
            known.setChannel("telegram");
            known.setInboundKey("verify-token");
            when(botRepository.findByOrganizationIdAndChannelAndCredentialId(anyString(), anyString(), anyLong()))
                    .thenReturn(Optional.of(known));
            when(connector.storedAccountSetting("106540352242922", "verify-token")).thenReturn("106540352242922");
            // do-style: when() would run the real 3-arg default, which calls the 2-arg
            // overload, and the stub would land on that one instead.
            org.mockito.Mockito.doReturn(Outcome.of(new BotIdentity("106540352242922", "+1 555", "Shop")))
                    .when(connector).verifyBot(anyString(), anyLong(), any());
            webhookSlotIsEmpty();
            deliveryWorks();

            connectWith("+33612345678", null, null);

            // Asking again for a value the row already holds would make every reconnect a
            // trip back to the Meta dashboard.
            verify(connector).verifyBot(TENANT, 9L, "106540352242922");
        }

        @Test
        @DisplayName("stores the connector's inbound key and hands it, with this bot's callback URL, to the setup steps")
        void storesInboundKeyAndReturnsSetupSteps() {
            // do-style: when() would run the real 3-arg default, which calls the 2-arg
            // overload, and the stub would land on that one instead.
            org.mockito.Mockito.doReturn(Outcome.of(new BotIdentity("77", "ops_bot", "Ops")))
                    .when(connector).verifyBot(anyString(), anyLong(), any());
            webhookSlotIsEmpty();
            when(connector.newInboundKey(eq("public-key"), any())).thenReturn("public-key");
            when(connector.setupInstructions(anyString(), anyString()))
                    .thenAnswer(invocation -> "Paste " + invocation.getArgument(0) + " / " + invocation.getArgument(1));
            deliveryWorks();
            ArgumentCaptor<ChatChannelBotEntity> saved = ArgumentCaptor.forClass(ChatChannelBotEntity.class);

            ConnectResult result = connectWith("-100123", "public-key", null);

            verify(botRepository, atLeastOnce()).save(saved.capture());
            ChatChannelBotEntity bot = saved.getValue();
            assertThat(bot.getInboundKey()).isEqualTo("public-key");
            // Per bot, not per provider: the controller finds the key to verify with by this id.
            assertThat(result.setupInstructions())
                    .isEqualTo("Paste " + OUR_BASE + "/approval-callback/telegram/" + bot.getId() + " / public-key");
        }

        @Test
        @DisplayName("stores the destination as the provider writes it, not as it was typed")
        void normalizesTheDestination() {
            botAnswers();
            webhookSlotIsEmpty();
            deliveryWorks();
            when(connector.normalizeChatId("+33 6 12 34 56 78")).thenReturn("33612345678");

            ConnectResult result = connectWith("+33 6 12 34 56 78", null, null);

            // Stored as typed, the link would never match the number a reply comes back from.
            assertThat(result.channel().chatId()).isEqualTo("33612345678");
            verify(connector).sendTest(eq(TENANT), eq(9L), eq("33612345678"), anyString());
        }

        @Test
        @DisplayName("refuses a destination that normalizes to nothing, before any call to the provider")
        void refusesADestinationWithNothingUsable() {
            when(connector.normalizeChatId("call me")).thenReturn("");

            assertThatThrownBy(() -> connectWith("call me", null, null))
                    .isInstanceOf(ChatChannelException.class)
                    .hasMessageContaining("not one telegram can write to");
            verify(connector, never()).verifyBot(anyString(), anyLong(), any());
        }

        @Test
        @DisplayName("refuses allowed people where a press cannot say who pressed, before any call")
        void refusesAnAllowListItCannotEnforce() {
            when(connector.identifiesPresser()).thenReturn(false);

            // Saved, the list would refuse every press on that destination, including the
            // owner's, with nothing on the connect saying why.
            assertThatThrownBy(() -> connectWith("19:abc@thread.v2", null, List.of("u1")))
                    .isInstanceOf(ChatChannelException.class)
                    .hasMessageContaining("cannot be enforced");
            verify(connector, never()).verifyBot(anyString(), anyLong(), any());
        }

        @Test
        @DisplayName("the same provider connects normally with no allowed people")
        void acceptsNoAllowListWhereAPressIsAnonymous() {
            when(connector.identifiesPresser()).thenReturn(false);
            botAnswers();
            webhookSlotIsEmpty();
            deliveryWorks();

            assertThat(connectWith("19:abc@thread.v2", null, List.of()).delivered()).isTrue();
        }

        @Test
        @DisplayName("regression: a delivered connect still warns when presses cannot come back on this installation")
        void deliveredButPressesRefusedIsSaid() {
            botAnswers();
            webhookSlotIsEmpty();
            deliveryWorks();
            when(connector.inboundProblem()).thenReturn("no Slack signing secret");

            ConnectResult result = connectWith("C1", null, null);

            // Before: "delivered", no warning, and then a 401 on every press with nothing said.
            assertThat(result.delivered()).isTrue();
            assertThat(result.warning()).isEqualTo("no Slack signing secret");
        }

        @Test
        @DisplayName("an undelivered test message carries the provider's own usual reason, and no other")
        void undeliveredWarningIsTheProvidersOwn() {
            botAnswers();
            webhookSlotIsEmpty();
            when(connector.sendTest(anyString(), anyLong(), anyString(), anyString()))
                    .thenReturn(Outcome.failed("Outside the 24-hour window."));

            // No hint: a provider whose failures explain themselves gets nothing appended, in
            // particular not Telegram's "nobody has opened a conversation with the bot".
            assertThat(connectWith("-100123", null, null).warning())
                    .endsWith("Outside the 24-hour window.")
                    .doesNotContain("opened a conversation");

            when(connector.undeliveredHint()).thenReturn("Start the bot first.");
            assertThat(connectWith("-100123", null, null).warning())
                    .endsWith("Outside the 24-hour window. Start the bot first.");
        }
    }

    @Nested
    @DisplayName("sendNotice - one-way alerts to the workspace default (V528)")
    class SendNotice {

        private ChatChannelLinkEntity defaultLink(UUID botId) {
            ChatChannelLinkEntity link = new ChatChannelLinkEntity();
            link.setId(UUID.randomUUID());
            link.setOrganizationId(ORG);
            link.setBotId(botId);
            link.setChatId("-100555");
            link.setDefault(true);
            link.setActive(true);
            return link;
        }

        private void connectDefault() {
            ChatChannelBotEntity bot = new ChatChannelBotEntity();
            bot.setId(UUID.randomUUID());
            bot.setOrganizationId(ORG);
            bot.setChannel("telegram");
            bot.setCredentialId(9L);
            when(linkRepository.findByOrganizationIdAndIsDefaultTrueAndActiveTrue(ORG))
                    .thenReturn(Optional.of(defaultLink(bot.getId())));
            when(botRepository.findByIdAndOrganizationId(bot.getId(), ORG)).thenReturn(Optional.of(bot));
        }

        @Test
        @DisplayName("No default destination: nothing is sent and the result names no channel")
        void noDefault() {
            ChatChannelService.NoticeResult result = service.sendNotice(ORG, TENANT, "hello");

            assertThat(result.delivered()).isFalse();
            assertThat(result.channel()).isNull();
            verify(connector, never()).sendNotice(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Delivered through the connector's plain-text notice, to the default chat with its credential")
        void delivered() {
            connectDefault();
            when(connector.sendNotice(TENANT, 9L, "-100555", "hello")).thenReturn(Outcome.of(null));

            ChatChannelService.NoticeResult result = service.sendNotice(ORG, TENANT, "hello");

            assertThat(result.delivered()).isTrue();
            assertThat(result.channel()).isEqualTo("telegram");
        }

        @Test
        @DisplayName("A provider refusal comes back with its own sentence, never as success")
        void refused() {
            connectDefault();
            when(connector.sendNotice(TENANT, 9L, "-100555", "hello")).thenReturn(Outcome.failed("chat not found"));

            ChatChannelService.NoticeResult result = service.sendNotice(ORG, TENANT, "hello");

            assertThat(result.delivered()).isFalse();
            assertThat(result.error()).isEqualTo("chat not found");
        }

        @Test
        @DisplayName("The default notice IS the plain test send: no buttons, no answer expected")
        void defaultNoticeIsPlainSend() {
            ChatChannelConnector plain = mock(ChatChannelConnector.class);
            when(plain.sendNotice(any(), any(), any(), any())).thenCallRealMethod();
            when(plain.sendTest("t", 1L, "c", "x")).thenReturn(Outcome.of(null));

            assertThat(plain.sendNotice("t", 1L, "c", "x").ok()).isTrue();
            verify(plain).sendTest("t", 1L, "c", "x");
        }
    }

    @Nested
    @DisplayName("analytics: channel_connected / channel_disconnected / channel_default_set")
    class Analytics {

        private com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter analytics;

        @BeforeEach
        void wire() {
            analytics = mock(com.apimarketplace.orchestrator.services.analytics.EngagementAnalyticsEmitter.class);
            when(analytics.isActive()).thenReturn(true);
            org.springframework.test.util.ReflectionTestUtils.setField(service, "analytics", analytics);
        }

        @Test
        @DisplayName("a delivered connect reports provider, source, delivered, default and webhook outcome")
        void connectReported() {
            botAnswers();
            webhookSlotIsEmpty();
            deliveryWorks();

            ConnectResult result = service.connect(TENANT, ORG,
                    new ConnectRequest("telegram", 9L, "-100123", "Ops room", "group", false),
                    ChatChannelService.ChangeSource.ASSISTANT);

            verify(analytics).channelConnected(TENANT, ORG, "telegram", ChatChannelService.ChangeSource.ASSISTANT,
                    true, result.channel().isDefault(), WebhookOutcome.POINTED, true);
        }

        @Test
        @DisplayName("a reconnect of a chat that already has a link reports is_new_link=false")
        void reconnectReportedAsNotNew() {
            botAnswers();
            webhookSlotIsEmpty();
            deliveryWorks();
            ChatChannelLinkEntity existing = new ChatChannelLinkEntity();
            existing.setId(UUID.randomUUID());
            existing.setChatId("-100123");
            when(linkRepository.findByBotIdAndChatId(any(), anyString())).thenReturn(Optional.of(existing));

            service.connect(TENANT, ORG,
                    new ConnectRequest("telegram", 9L, "-100123", "Ops room", "group", false),
                    ChatChannelService.ChangeSource.MANUAL);

            verify(analytics).channelConnected(eq(TENANT), eq(ORG), eq("telegram"),
                    eq(ChatChannelService.ChangeSource.MANUAL), org.mockito.ArgumentMatchers.anyBoolean(),
                    org.mockito.ArgumentMatchers.anyBoolean(), any(), eq(false));
        }

        @Test
        @DisplayName("a refused connect reports nothing: nothing was connected")
        void refusedConnectSilent() {
            assertThatThrownBy(() -> service.connect(TENANT, ORG,
                    new ConnectRequest("telegram", 9L, " ", null, null, false),
                    ChatChannelService.ChangeSource.MANUAL)).isInstanceOf(ChatChannelException.class);

            verify(analytics, never()).channelConnected(any(), any(), any(), any(),
                    org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.anyBoolean(), any(),
                    org.mockito.ArgumentMatchers.anyBoolean());
        }

        @Test
        @DisplayName("disconnect reports the provider read before the bot row goes, and whether it was the default")
        void disconnectReported() {
            UUID botId = UUID.randomUUID();
            UUID linkId = UUID.randomUUID();
            ChatChannelLinkEntity link = new ChatChannelLinkEntity();
            link.setId(linkId);
            link.setBotId(botId);
            ChatChannelBotEntity bot = new ChatChannelBotEntity();
            bot.setId(botId);
            bot.setChannel("slack");
            when(linkRepository.findByIdAndOrganizationId(linkId, ORG)).thenReturn(Optional.of(link));
            when(linkRepository.findByBotId(botId)).thenReturn(List.of());
            when(botRepository.findByIdAndOrganizationId(botId, ORG)).thenReturn(Optional.of(bot));

            service.disconnect(TENANT, ORG, linkId, ChatChannelService.ChangeSource.MANUAL);

            verify(botRepository).delete(bot);
            verify(analytics).channelDisconnected(TENANT, ORG, "slack", ChatChannelService.ChangeSource.MANUAL, false);
        }

        @Test
        @DisplayName("disconnect with analytics inactive adds no bot lookup and reports nothing")
        void disconnectInactiveAddsNoLookup() {
            when(analytics.isActive()).thenReturn(false);
            UUID botId = UUID.randomUUID();
            UUID linkId = UUID.randomUUID();
            ChatChannelLinkEntity link = new ChatChannelLinkEntity();
            link.setId(linkId);
            link.setBotId(botId);
            when(linkRepository.findByIdAndOrganizationId(linkId, ORG)).thenReturn(Optional.of(link));
            // The bot keeps another link, so the cleanup lookup does not run either: any
            // lookup of the bot here could only be the analytics one.
            when(linkRepository.findByBotId(botId)).thenReturn(List.of(new ChatChannelLinkEntity()));

            service.disconnect(TENANT, ORG, linkId, ChatChannelService.ChangeSource.MANUAL);

            verify(linkRepository).delete(link);
            verify(botRepository, never()).findByIdAndOrganizationId(any(), any());
            verify(analytics, never()).channelDisconnected(any(), any(), any(), any(),
                    org.mockito.ArgumentMatchers.anyBoolean());
        }

        @Test
        @DisplayName("set default reports the provider of the new default")
        void setDefaultReported() {
            ChatChannelLinkEntity link = new ChatChannelLinkEntity();
            UUID id = UUID.randomUUID();
            UUID botId = UUID.randomUUID();
            link.setId(id);
            link.setBotId(botId);
            link.setVerifiedAt(Instant.now());
            link.setActive(true);
            ChatChannelBotEntity bot = new ChatChannelBotEntity();
            bot.setId(botId);
            bot.setChannel("discord");
            bot.setCredentialId(9L);
            when(linkRepository.findByIdAndOrganizationId(id, ORG)).thenReturn(Optional.of(link));
            when(botRepository.findByIdAndOrganizationId(botId, ORG)).thenReturn(Optional.of(bot));

            service.setDefault(TENANT, ORG, id);

            verify(analytics).channelDefaultSet(TENANT, ORG, "discord");
        }
    }
}
