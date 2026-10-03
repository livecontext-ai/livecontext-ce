package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.agent.summary.ColdSummarizerPromptBuilder.Turn;
import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.entity.Message;
import com.apimarketplace.conversation.purge.RestrictedConversationContentPurger;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.repository.MessageRepository;
import com.apimarketplace.conversation.service.ai.ColdSummarizerService.LlmJsonInvoker;
import com.apimarketplace.conversation.service.ai.ColdSummarizerService.SummarizeOutcome;
import com.apimarketplace.conversation.service.ai.ColdSummarizerService.SummarizeRequest;
import com.apimarketplace.conversation.streaming.StreamPubSubService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CASA LC-011 / LC-066: the restricted-content purge redacts a message and clears the
 * conversation's cold summary in one statement, but a compaction that loaded the message just
 * before could write its summary just after, putting back content the purge had just removed. A
 * RESTRICTED message within {@link ChatCompactionOrchestrator#RESTRICTED_SUMMARY_MARGIN} of the
 * retention limit is therefore summarised as already removed.
 */
@DisplayName("ChatCompactionOrchestrator summarises restricted content near its retention limit as removed (LC-011)")
class ChatCompactionRestrictedSummaryTest {

    private static final String CONV = "conv-restricted";

    private MessageRepository messageRepo;
    private ColdSummarizerService summarizer;
    private ChatCompactionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        messageRepo = mock(MessageRepository.class);
        ConversationRepository conversationRepo = mock(ConversationRepository.class);
        Conversation conversation = new Conversation();
        conversation.setSummaryCold(null);
        when(conversationRepo.findById(CONV)).thenReturn(Optional.of(conversation));
        summarizer = mock(ColdSummarizerService.class);
        when(summarizer.summarize(any(SummarizeRequest.class), any(LlmJsonInvoker.class)))
                .thenReturn(new SummarizeOutcome.SkippedGate());
        AgentConfigProvider agentConfigProvider = mock(AgentConfigProvider.class);
        when(agentConfigProvider.getCompactionOverride(anyString(), any()))
                .thenReturn(AgentConfigProvider.CompactionOverride.NONE);
        CompactionDefaultsConfig config = new CompactionDefaultsConfig();
        config.setEnabled(true);
        config.setHotWarmTurnWindow(2);
        orchestrator = new ChatCompactionOrchestrator(messageRepo, conversationRepo, summarizer,
                mock(HttpLlmJsonInvoker.class), config, mock(StreamPubSubService.class),
                agentConfigProvider, new SimpleMeterRegistry());
    }

    /** {@code daysOld} days and one hour old, so no assertion sits on the exact cutoff instant. */
    private static Message message(String body, String sensitivity, int daysOld) {
        Message m = new Message();
        m.setRole(Message.MessageRole.ASSISTANT);
        m.setContent(body);
        m.setDataSensitivity(sensitivity);
        m.setCreatedAt(LocalDateTime.now().minusDays(daysOld).minusHours(1));
        return m;
    }

    /** Three COLD messages (window 2), then two recent ones; returns what the summariser was given. */
    private List<String> coldBodies(Message... cold) {
        List<Message> all = new ArrayList<>(List.of(cold));
        all.add(message("recent 1", "NORMAL", 0));
        all.add(message("recent 2", "NORMAL", 0));
        when(messageRepo.findByConversationIdOrderByCreatedAtAsc(CONV)).thenReturn(all);

        orchestrator.afterTurn(CONV, "anthropic", "claude", "tenant-1", null);

        ArgumentCaptor<SummarizeRequest> request = ArgumentCaptor.forClass(SummarizeRequest.class);
        verify(summarizer).summarize(request.capture(), any(LlmJsonInvoker.class));
        return request.getValue().coldTurns().stream().map(Turn::body).toList();
    }

    @Test
    @DisplayName("regression: a Gmail message within a day of its deletion reaches the summariser as the placeholder")
    void restrictedContentNearTheLimitIsWithheld() {
        List<String> bodies = coldBodies(
                message("wire 45000 EUR to the new IBAN", "RESTRICTED", 29 /* purged in about a day */),
                message("still well inside the window", "RESTRICTED", 10),
                message("an ordinary old message", "NORMAL", 29));

        assertThat(bodies).containsExactly(
                RestrictedConversationContentPurger.PLACEHOLDER,
                "still well inside the window",
                "an ordinary old message");
    }

    @Test
    @DisplayName("with the purge off nothing is ever redacted, so nothing is withheld early")
    void purgeOffWithholdsNothing() {
        ReflectionTestUtils.setField(orchestrator, "restrictedPurgeEnabled", false);

        List<String> bodies = coldBodies(
                message("wire 45000 EUR to the new IBAN", "RESTRICTED", 29),
                message("b", "NORMAL", 1),
                message("c", "NORMAL", 1));

        assertThat(bodies.get(0)).isEqualTo("wire 45000 EUR to the new IBAN");
    }

    @Test
    @DisplayName("the cutoff follows the configured retention, minus the margin")
    void cutoffFollowsTheRetention() {
        ReflectionTestUtils.setField(orchestrator, "restrictedRetentionDays", 10);

        List<String> bodies = coldBodies(
                message("nine days old", "RESTRICTED", 9),
                message("eight days old", "RESTRICTED", 8),
                message("c", "NORMAL", 9));

        assertThat(bodies).containsExactly(
                RestrictedConversationContentPurger.PLACEHOLDER, "eight days old", "c");
    }
}
