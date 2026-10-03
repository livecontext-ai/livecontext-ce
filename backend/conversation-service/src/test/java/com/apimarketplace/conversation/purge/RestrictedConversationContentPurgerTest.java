package com.apimarketplace.conversation.purge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LC-011: restricted (Gmail / Drive) chat content older than the window is redacted: the tool
 * result loses its content, the assistant message its text and the content-bearing keys of its
 * tool calls. The SQL itself runs on real Postgres in {@link RestrictedConversationContentPurgerPostgresTest};
 * these pin what the purge sends.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RestrictedConversationContentPurger")
class RestrictedConversationContentPurgerTest {

    private static final String TOOL_RESULTS = "WITH redacted AS (UPDATE conversation.tool_results";
    private static final String MESSAGES = "WITH redacted AS (UPDATE conversation.messages";

    @Mock private JdbcTemplate jdbc;

    private RestrictedConversationContentPurger purger;

    @BeforeEach
    void setUp() {
        purger = new RestrictedConversationContentPurger(jdbc, new ObjectMapper(), true, 30, 500);
        lenient().when(jdbc.queryForMap(startsWith(TOOL_RESULTS), any(Object[].class)))
                .thenReturn(Map.of("redacted", 0L, "cleared", 0L));
        lenient().when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
    }

    @Test
    @DisplayName("redacts restricted tool results older than the window and clears their summaries in the same statement")
    void redactsToolResults() {
        when(jdbc.queryForMap(startsWith(TOOL_RESULTS), any(Object[].class)))
                .thenReturn(Map.of("redacted", 3L, "cleared", 2L));
        Instant now = Instant.parse("2026-09-23T00:00:00Z");

        RestrictedConversationContentPurger.PurgeReport report = purger.purge(now);

        assertThat(report.toolResultsRedacted()).isEqualTo(3);
        assertThat(report.summariesCleared()).isEqualTo(2);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForMap(sql.capture(), args.capture());
        assertThat(sql.getValue())
                .contains("content_full = NULL")
                .contains("data_sensitivity = 'REDACTED'")
                .contains("WHERE data_sensitivity = 'RESTRICTED' AND created_at < ?")
                .contains("RETURNING conversation_id")
                .contains("UPDATE conversation.conversations SET summary_cold = NULL");
        assertThat(args.getValue()).containsExactly(RestrictedConversationContentPurger.PLACEHOLDER,
                Timestamp.from(now.minus(Duration.ofDays(30))), 500);
    }

    @Test
    @DisplayName("redacts restricted assistant messages: text replaced, tool-call arguments removed, only while still RESTRICTED")
    void redactsMessages() {
        String toolCalls = "[{\"id\":\"c1\",\"toolName\":\"catalog\",\"arguments\":\"{\\\"q\\\":\\\"from:ceo\\\"}\","
                + "\"iconSlug\":\"gmail\",\"visualization\":{\"rows\":[\"secret\"]}}]";
        when(jdbc.queryForList(startsWith("SELECT id, tool_calls FROM conversation.messages"), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", "m1", "tool_calls", toolCalls)));
        when(jdbc.queryForMap(startsWith(MESSAGES), any(Object[].class)))
                .thenReturn(Map.of("redacted", 1L, "cleared", 1L));

        RestrictedConversationContentPurger.PurgeReport report = purger.purge(Instant.now());

        assertThat(report.messagesRedacted()).isEqualTo(1);
        assertThat(report.summariesCleared()).isEqualTo(1);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).queryForMap(sql.capture(), args.capture());
        int messageCall = sql.getAllValues().get(0).startsWith(MESSAGES) ? 0 : 1;
        assertThat(sql.getAllValues().get(messageCall))
                .startsWith(MESSAGES).contains("WHERE id = ? AND data_sensitivity = 'RESTRICTED'");
        Object[] messageArgs = args.getAllValues().get(messageCall);
        assertThat(messageArgs[0]).isEqualTo(RestrictedConversationContentPurger.PLACEHOLDER);
        assertThat((String) messageArgs[1]).contains("\"iconSlug\":\"gmail\"")
                .doesNotContain("from:ceo").doesNotContain("secret");
        assertThat(messageArgs[2]).isEqualTo("m1");
    }

    @Test
    @DisplayName("unparseable tool-call JSON is dropped rather than kept unread")
    void unparseableToolCallsDropped() {
        assertThat(purger.redactToolCalls("not json")).isNull();
        assertThat(purger.redactToolCalls(null)).isNull();
    }

    @Test
    @DisplayName("LC-011: selection is by row age (created_at), never by a stamp, so the first armed pass reaches content written while disarmed")
    void lc011AgeBasedSoArmingCatchesUpTheBacklog() {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        Timestamp cutoff = Timestamp.from(now.minus(Duration.ofDays(30)));

        purger.purge(now);

        ArgumentCaptor<String> updateSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> updateArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForMap(updateSql.capture(), updateArgs.capture());
        assertThat(updateSql.getValue()).contains("created_at < ?").doesNotContain("expires");
        assertThat(updateArgs.getValue()).contains(cutoff);

        ArgumentCaptor<String> selectSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> selectArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(selectSql.capture(), selectArgs.capture());
        assertThat(selectSql.getValue()).contains("data_sensitivity = 'RESTRICTED' AND created_at < ?")
                .doesNotContain("expires");
        assertThat(selectArgs.getValue()).containsExactly(cutoff, 500);
    }

    @Test
    @DisplayName("LC-011: disarmed, nothing is redacted even far past the window")
    void lc011DisarmedRedactsNothing() {
        RestrictedConversationContentPurger off =
                new RestrictedConversationContentPurger(jdbc, new ObjectMapper(), false, 30, 500);

        off.scheduledPurge();
        RestrictedConversationContentPurger.PurgeReport report =
                off.purge(Instant.now().plus(Duration.ofDays(3650)));

        assertThat(report.toolResultsRedacted()).isZero();
        assertThat(report.messagesRedacted()).isZero();
        verifyNoInteractions(jdbc);
    }

    @Test
    @DisplayName("disabled: touches nothing")
    void disabled() {
        RestrictedConversationContentPurger off =
                new RestrictedConversationContentPurger(jdbc, new ObjectMapper(), false, 30, 500);
        off.purge(Instant.now());
        verifyNoInteractions(jdbc);
    }
}
