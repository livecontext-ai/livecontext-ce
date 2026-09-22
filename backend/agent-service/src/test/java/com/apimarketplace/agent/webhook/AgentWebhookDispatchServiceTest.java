package com.apimarketplace.agent.webhook;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentWebhookTokenEntity;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.common.credit.ChatCreditRefusal;
import com.apimarketplace.conversation.client.ConversationClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentWebhookDispatchService")
class AgentWebhookDispatchServiceTest {

    private static final UUID AGENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String TOKEN = "token-prod";
    private static final String TENANT_ID = "tenant-webhook";
    private static final String ORGANIZATION_ID = "org-webhook";
    private static final String CONVERSATION_ID = "conv-webhook";

    @Mock
    private AgentWebhookTokenService tokenService;

    @Mock
    private AgentRepository agentRepository;

    @Mock
    private ConversationClient conversationClient;

    private AgentWebhookDispatchService service;
    private ListAppender<ILoggingEvent> appender;
    private ch.qos.logback.classic.Logger dispatchLogger;

    @AfterEach
    void detachAppender() {
        if (dispatchLogger != null) {
            dispatchLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @BeforeEach
    void setUp() {
        service = new AgentWebhookDispatchService(
            tokenService, agentRepository, conversationClient, new ObjectMapper());
        dispatchLogger = (ch.qos.logback.classic.Logger)
            LoggerFactory.getLogger(AgentWebhookDispatchService.class);
        appender = new ListAppender<>();
        appender.start();
        dispatchLogger.addAppender(appender);
    }

    @Test
    @DisplayName("Memory webhook reuses the org-scoped agent conversation and dispatches source=WEBHOOK with org")
    void memoryWebhookForwardsOrganizationToConversationAndSyncDispatch() {
        AgentWebhookTokenEntity token = token(true);
        AgentEntity agent = agent();

        when(tokenService.findActiveByToken(TOKEN)).thenReturn(Optional.of(token));
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent));
        when(conversationClient.findOrCreateAgentConversation(
            AGENT_ID.toString(), TENANT_ID, "Webhook Bot", ORGANIZATION_ID))
            .thenReturn(CONVERSATION_ID);
        when(conversationClient.sendChatSync(
            eq(TENANT_ID), eq(CONVERSATION_ID), contains("event"),
            eq(AGENT_ID.toString()), eq("deepseek-chat"), eq("deepseek"),
            eq("WEBHOOK"), isNull(), eq(ORGANIZATION_ID)))
            .thenReturn(Map.of("success", true, "content", "ok"));

        AgentWebhookResponse response = service.dispatch(TOKEN, Map.of("event", "created"), true);

        assertThat(response.status()).isEqualTo("success");
        assertThat(response.conversationId()).isEqualTo(CONVERSATION_ID);
        verify(conversationClient).findOrCreateAgentConversation(
            AGENT_ID.toString(), TENANT_ID, "Webhook Bot", ORGANIZATION_ID);
        verify(conversationClient).sendChatSync(
            eq(TENANT_ID), eq(CONVERSATION_ID), contains("event"),
            eq(AGENT_ID.toString()), eq("deepseek-chat"), eq("deepseek"),
            eq("WEBHOOK"), isNull(), eq(ORGANIZATION_ID));
    }

    @Test
    @DisplayName("Memory-off webhook creates the isolated conversation in the agent org before queued dispatch")
    void memoryOffWebhookForwardsOrganizationToIsolatedConversationAndSyncDispatch() {
        AgentWebhookTokenEntity token = token(false);
        AgentEntity agent = agent();

        when(tokenService.findActiveByToken(TOKEN)).thenReturn(Optional.of(token));
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent));
        when(conversationClient.createConversation(
            eq(TENANT_ID), startsWith("Webhook: Webhook Bot"),
            eq("deepseek-chat"), eq("deepseek"), eq(AGENT_ID.toString()), eq(false), eq(ORGANIZATION_ID)))
            .thenReturn(CONVERSATION_ID);
        when(conversationClient.sendChatSync(
            eq(TENANT_ID), eq(CONVERSATION_ID), contains("event"),
            eq(AGENT_ID.toString()), eq("deepseek-chat"), eq("deepseek"),
            eq("WEBHOOK"), isNull(), eq(ORGANIZATION_ID)))
            .thenReturn(Map.of("success", true, "content", "ok"));

        AgentWebhookResponse response = service.dispatch(TOKEN, Map.of("event", "created"), true);

        assertThat(response.status()).isEqualTo("success");
        verify(conversationClient).createConversation(
            eq(TENANT_ID), startsWith("Webhook: Webhook Bot"),
            eq("deepseek-chat"), eq("deepseek"), eq(AGENT_ID.toString()), eq(false), eq(ORGANIZATION_ID));
        verify(conversationClient).sendChatSync(
            eq(TENANT_ID), eq(CONVERSATION_ID), contains("event"),
            eq(AGENT_ID.toString()), eq("deepseek-chat"), eq("deepseek"),
            eq("WEBHOOK"), isNull(), eq(ORGANIZATION_ID));
    }

    @Test
    @DisplayName("out of credits: WARN, no ERROR, and the caller still gets the same error response")
    void creditRefusalIsWarnedNotErrored() {
        // Same class as the agent-schedule and task paths: a webhook caller that retries hits the
        // credit gate every time, correctly, and each refusal was an ERROR line.
        AgentWebhookResponse response = dispatchWithChatError(ChatCreditRefusal.MESSAGE);

        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(appender.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("refused");
        });
        // Only the level moved: the webhook caller is still told it failed, with the same reason.
        assertThat(response.status()).isEqualTo("error");
        assertThat(response.message()).isEqualTo(ChatCreditRefusal.MESSAGE);
    }

    @Test
    @DisplayName("the WRAPPED transport string is recognised too - the chain, not just the unwrap")
    void wrappedTransportStringIsAlsoARefusal() {
        // See the matching case in AgentTaskServiceRefusalLogLevelTest: feeding only the clean
        // sentence would let a revert of ConversationClient's unwrap pass unnoticed.
        dispatchWithChatError("402  on POST request for \"http://livecontext-livecontext-conversation:8087"
            + "/api/internal/chat/sync\": \"{\"error\":\"Insufficient credits\"}\"");

        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(appender.list).anySatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
    }

    @Test
    @DisplayName("a genuine fault keeps ERROR")
    void platformFaultStaysError() {
        dispatchWithChatError("NullPointerException in the agent loop");

        assertThat(appender.list).anySatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.ERROR));
        // Scoped to the refusal line: a blanket "no WARN" would fail on any unrelated
        // future warning on this logger, for a reason that has nothing to do with this test.
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("refused"));
    }

    private AgentWebhookResponse dispatchWithChatError(String chatError) {
        // lenient() on these two only: the pre-existing tests in this class rely on strict-stub
        // checking, so the class must not be made LENIENT wholesale just to serve this helper.
        lenient().when(tokenService.findActiveByToken(TOKEN)).thenReturn(Optional.of(token(true)));
        lenient().when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent()));
        lenient().when(conversationClient.findOrCreateAgentConversation(
            AGENT_ID.toString(), TENANT_ID, "Webhook Bot", ORGANIZATION_ID))
            .thenReturn(CONVERSATION_ID);
        lenient().when(conversationClient.sendChatSync(
            eq(TENANT_ID), eq(CONVERSATION_ID), contains("event"),
            eq(AGENT_ID.toString()), eq("deepseek-chat"), eq("deepseek"),
            eq("WEBHOOK"), isNull(), eq(ORGANIZATION_ID)))
            .thenReturn(Map.of("success", false, "error", chatError));

        return service.dispatch(TOKEN, Map.of("event", "created"), true);
    }

    private AgentWebhookTokenEntity token(boolean memoryEnabled) {
        AgentWebhookTokenEntity token = new AgentWebhookTokenEntity(AGENT_ID, TOKEN);
        token.setMemoryEnabled(memoryEnabled);
        token.setIsActive(true);
        return token;
    }

    private AgentEntity agent() {
        AgentEntity agent = new AgentEntity();
        agent.setId(AGENT_ID);
        agent.setTenantId(TENANT_ID);
        agent.setOrganizationId(ORGANIZATION_ID);
        agent.setName("Webhook Bot");
        agent.setModelProvider("deepseek");
        agent.setModelName("deepseek-chat");
        agent.setIsActive(true);
        return agent;
    }
    @Test
    @DisplayName("an agent that has spent its own cap is refused BEFORE the webhook buys a turn")
    void agentOverItsOwnBudgetIsRefusedUpFront() {
        // The same gap the schedule had, through the other unattended door. This endpoint
        // checked isActive and nothing else, so a caller retrying it in a loop bought a fresh
        // turn every time, whatever the owner had capped the agent at.
        AgentEntity agent = agent();
        agent.setCreditBudget(new java.math.BigDecimal("1"));
        agent.setCreditsConsumed(new java.math.BigDecimal("3"));
        agent.setBudgetResetMode("cumulative");

        when(tokenService.findActiveByToken(TOKEN)).thenReturn(Optional.of(token(true)));
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent));

        AgentWebhookResponse response = service.dispatch(TOKEN, Map.of("event", "created"), true);

        assertThat(response.status()).isNotEqualTo("success");
        assertThat(response.message()).contains("spent its own credit budget");
        // The assertion that matters: no conversation is opened and no turn is sent.
        verifyNoInteractions(conversationClient);
    }

    @Test
    @DisplayName("the figure counts what a sub-agent is holding, not just what was spent")
    void theRefusalFigureCountsReservedCredits() {
        // Otherwise the caller reads "6 of 10 credits" beside a refusal, which looks like a
        // bug: what stopped the run is the 4 an in-flight sub-agent has committed. The guard
        // one layer down already words it this way, and two sentences for one condition is
        // how a reader learns to distrust both.
        AgentEntity agent = agent();
        agent.setCreditBudget(new java.math.BigDecimal("10"));
        agent.setCreditsConsumed(new java.math.BigDecimal("6"));
        agent.setCreditsReserved(new java.math.BigDecimal("4"));
        agent.setBudgetResetMode("cumulative");

        when(tokenService.findActiveByToken(TOKEN)).thenReturn(Optional.of(token(true)));
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent));

        AgentWebhookResponse response = service.dispatch(TOKEN, Map.of("event", "created"), true);

        assertThat(response.message()).contains("10 of 10 credits");
        verifyNoInteractions(conversationClient);
    }

    @Test
    @DisplayName("that refusal is a WARN, not an ERROR: a retrying caller must not page anyone")
    void theBudgetRefusalIsWarned() {
        AgentEntity agent = agent();
        agent.setCreditBudget(new java.math.BigDecimal("1"));
        agent.setCreditsConsumed(new java.math.BigDecimal("3"));
        agent.setBudgetResetMode("cumulative");
        when(tokenService.findActiveByToken(TOKEN)).thenReturn(Optional.of(token(true)));
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent));

        service.dispatch(TOKEN, Map.of("event", "created"), true);

        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("refused by its own budget"));
        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
    }

    @Test
    @DisplayName("an agent whose period has rolled over runs, rather than being held by a stale counter")
    void aRolledOverAgentIsNotRefused() {
        // The counter is only zeroed when the agent next RUNS, so a monthly agent that hit its
        // cap last month still reads at the cap. Refusing on the stored figure would silence
        // its webhook for a whole month after the allowance came back.
        AgentEntity agent = agent();
        agent.setCreditBudget(new java.math.BigDecimal("1"));
        agent.setCreditsConsumed(new java.math.BigDecimal("3"));
        agent.setBudgetResetMode("monthly");
        agent.setBudgetLastReset(java.time.Instant.now().minus(45, java.time.temporal.ChronoUnit.DAYS));

        when(tokenService.findActiveByToken(TOKEN)).thenReturn(Optional.of(token(true)));
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent));
        when(conversationClient.findOrCreateAgentConversation(
            AGENT_ID.toString(), TENANT_ID, "Webhook Bot", ORGANIZATION_ID))
            .thenReturn(CONVERSATION_ID);
        when(conversationClient.sendChatSync(
            eq(TENANT_ID), eq(CONVERSATION_ID), contains("event"),
            eq(AGENT_ID.toString()), eq("deepseek-chat"), eq("deepseek"),
            eq("WEBHOOK"), isNull(), eq(ORGANIZATION_ID)))
            .thenReturn(Map.of("success", true, "content", "ok"));

        assertThat(service.dispatch(TOKEN, Map.of("event", "created"), true).status()).isEqualTo("success");
    }

    @Test
    @DisplayName("an uncapped agent is untouched")
    void anUncappedAgentIsUntouched() {
        when(tokenService.findActiveByToken(TOKEN)).thenReturn(Optional.of(token(true)));
        when(agentRepository.findById(AGENT_ID)).thenReturn(Optional.of(agent()));
        when(conversationClient.findOrCreateAgentConversation(
            AGENT_ID.toString(), TENANT_ID, "Webhook Bot", ORGANIZATION_ID))
            .thenReturn(CONVERSATION_ID);
        when(conversationClient.sendChatSync(
            eq(TENANT_ID), eq(CONVERSATION_ID), contains("event"),
            eq(AGENT_ID.toString()), eq("deepseek-chat"), eq("deepseek"),
            eq("WEBHOOK"), isNull(), eq(ORGANIZATION_ID)))
            .thenReturn(Map.of("success", true, "content", "ok"));

        assertThat(service.dispatch(TOKEN, Map.of("event", "created"), true).status()).isEqualTo("success");
    }
}
