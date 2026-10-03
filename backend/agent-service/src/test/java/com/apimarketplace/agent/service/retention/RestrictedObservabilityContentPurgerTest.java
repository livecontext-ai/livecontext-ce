package com.apimarketplace.agent.service.retention;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LC-011: agent observability content of an execution that read Gmail / Drive is redacted after
 * the retention window: the messages first, then the tool calls, whose REDACTED tag is what stops
 * the next sweep from selecting the execution again.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RestrictedObservabilityContentPurger")
class RestrictedObservabilityContentPurgerTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private TransactionTemplate transactionTemplate;

    private RestrictedObservabilityContentPurger purger;

    @BeforeEach
    void setUp() {
        purger = new RestrictedObservabilityContentPurger(jdbc, transactionTemplate, true, 30, 200);
    }

    @Test
    @DisplayName("redacts the messages, then every tool call, of each restricted execution past the window")
    @SuppressWarnings("unchecked")
    void redactsExecution() {
        UUID execution = UUID.randomUUID();
        Instant now = Instant.parse("2026-09-23T00:00:00Z");
        when(jdbc.queryForList(anyString(), eq(UUID.class), any(Timestamp.class), anyInt()))
                .thenReturn(List.of(execution));
        when(transactionTemplate.execute(any())).thenAnswer(inv ->
                ((TransactionCallback<int[]>) inv.getArgument(0)).doInTransaction(null));
        when(jdbc.update(startsWith("UPDATE agent.agent_execution_messages"), any(Object[].class))).thenReturn(4);
        when(jdbc.update(startsWith("UPDATE agent.agent_execution_tool_calls"), any(Object[].class))).thenReturn(2);

        RestrictedObservabilityContentPurger.PurgeReport report = purger.purge(now);

        assertThat(report.executions()).isEqualTo(1);
        assertThat(report.messagesRedacted()).isEqualTo(4);
        assertThat(report.toolCallsRedacted()).isEqualTo(2);

        ArgumentCaptor<String> select = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Timestamp> cutoff = ArgumentCaptor.forClass(Timestamp.class);
        verify(jdbc).queryForList(select.capture(), eq(UUID.class), cutoff.capture(), eq(200));
        assertThat(select.getValue()).contains("data_sensitivity = 'RESTRICTED'").contains("created_at < ?");
        assertThat(cutoff.getValue()).isEqualTo(Timestamp.from(now.minus(Duration.ofDays(30))));

        InOrder order = inOrder(jdbc);
        order.verify(jdbc).update(startsWith("UPDATE agent.agent_execution_messages"),
                eq(RestrictedObservabilityContentPurger.PLACEHOLDER), eq(execution));
        ArgumentCaptor<String> toolCallSql = ArgumentCaptor.forClass(String.class);
        order.verify(jdbc).update(toolCallSql.capture(),
                eq(RestrictedObservabilityContentPurger.PLACEHOLDER), eq(execution));
        assertThat(toolCallSql.getValue())
                .contains("arguments = NULL")
                .contains("data_sensitivity = 'REDACTED'")
                .contains("data_sensitivity <> 'REDACTED'");
    }

    @Test
    @DisplayName("nothing past the window: nothing is written")
    void nothingToDo() {
        when(jdbc.queryForList(anyString(), eq(UUID.class), any(Timestamp.class), anyInt())).thenReturn(List.of());

        assertThat(purger.purge(Instant.now()).executions()).isZero();
        verifyNoInteractions(transactionTemplate);
    }

    @Test
    @DisplayName("LC-011: selection is by execution age (created_at), never by a stamp, so the first armed pass reaches executions written while disarmed")
    void lc011AgeBasedSoArmingCatchesUpTheBacklog() {
        Instant now = Instant.parse("2026-10-01T00:00:00Z");
        when(jdbc.queryForList(anyString(), eq(UUID.class), any(Timestamp.class), anyInt())).thenReturn(List.of());

        purger.purge(now);

        ArgumentCaptor<String> select = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Timestamp> cutoff = ArgumentCaptor.forClass(Timestamp.class);
        verify(jdbc).queryForList(select.capture(), eq(UUID.class), cutoff.capture(), eq(200));
        assertThat(select.getValue())
                .contains("FROM agent.agent_executions")
                .contains("data_sensitivity = 'RESTRICTED' AND created_at < ?")
                .doesNotContain("expires");
        assertThat(cutoff.getValue()).isEqualTo(Timestamp.from(now.minus(Duration.ofDays(30))));
    }

    @Test
    @DisplayName("LC-011: disarmed, nothing is redacted even far past the window, from the scheduled tick or a direct call")
    void lc011DisarmedRedactsNothing() {
        RestrictedObservabilityContentPurger off =
                new RestrictedObservabilityContentPurger(jdbc, transactionTemplate, false, 30, 200);

        off.scheduledPurge();
        assertThat(off.purge(Instant.now().plus(Duration.ofDays(3650))).executions()).isZero();

        verifyNoInteractions(jdbc, transactionTemplate);
    }

    @Test
    @DisplayName("disabled: touches nothing")
    void disabled() {
        RestrictedObservabilityContentPurger off =
                new RestrictedObservabilityContentPurger(jdbc, transactionTemplate, false, 30, 200);
        off.purge(Instant.now());
        verifyNoInteractions(jdbc, transactionTemplate);
    }
}
