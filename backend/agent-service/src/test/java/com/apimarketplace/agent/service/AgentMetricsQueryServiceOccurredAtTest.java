package com.apimarketplace.agent.service;

import com.apimarketplace.agent.repository.AgentExecutionIterationRepository;
import com.apimarketplace.agent.repository.AgentExecutionMessageRepository;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentExecutionToolCallRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every aggregate that answers "when did this run happen" reads {@code ended_at}.
 *
 * <p>These queries have always meant the moment the run landed in the table, because
 * {@code started_at} used to BE that moment: the row is written once, at the end, and
 * {@code started_at} was stamped at persist time (which is how it came to sit AFTER
 * {@code ended_at} on all 7,953 production rows). Now that
 * {@code AgentObservabilityService.startedAtFrom} makes it a real start, a query left on
 * it would silently re-date its answer by the run's own duration: a 20-minute
 * browser-agent session would report "last run 22 minutes ago" one second after
 * finishing, and a run crossing UTC midnight would move to the previous day's bucket.
 * Nothing would error; a user-visible number would just quietly move.
 *
 * <p>So the assertion below is deliberately negative as well as positive. Asserting only
 * that {@code COALESCE(ae.ended_at, ae.started_at)} appears would still pass if a bare
 * {@code ae.started_at} were added beside it, which is precisely how the next occurrence
 * would arrive.
 *
 * <p>{@code EntityManager} is mocked, as in the sibling metrics tests: what is under test
 * is which column each statement names, and a live database cannot tell a right column
 * from a wrong one that also parses.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentMetricsQueryService - aggregates date a run by when it ENDED")
class AgentMetricsQueryServiceOccurredAtTest {

    private static final String TENANT_ID = "tenant-42";
    private static final String ORG_ID = "org-acme";
    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Mock private AgentExecutionRepository executionRepository;
    @Mock private AgentExecutionMessageRepository messageRepository;
    @Mock private AgentExecutionToolCallRepository toolCallRepository;
    @Mock private AgentExecutionIterationRepository iterationRepository;
    @Mock private EntityManager entityManager;
    @Mock private Query query;

    private AgentMetricsQueryService service;

    @BeforeEach
    void setUp() {
        service = new AgentMetricsQueryService(
            executionRepository, messageRepository, toolCallRepository,
            iterationRepository, entityManager);
        lenient().when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        lenient().when(query.setParameter(anyString(), any())).thenReturn(query);
    }

    /**
     * The five statements that date a run - two summaries and three daily series - each
     * named with the question it answers. They are every native query in the service that
     * dates an {@code agent_executions} row. The other timestamp readers there
     * ({@code MAX(tc.created_at) AS last_used_at}) date a TOOL CALL, whose own timestamp
     * this change does not touch.
     */
    static Stream<Object[]> datingReaders() {
        return Stream.of(
            new Object[]{"agent-type summary - last run", (Consumer<AgentMetricsQueryService>)
                s -> s.getAgentTypeSummary(TENANT_ID, ORG_ID, "classify")},
            new Object[]{"chat summary - last run", (Consumer<AgentMetricsQueryService>)
                s -> s.getChatSummary(TENANT_ID, ORG_ID)},
            new Object[]{"chat daily series", (Consumer<AgentMetricsQueryService>)
                s -> s.getChatDailyStats(TENANT_ID, ORG_ID, 30)},
            new Object[]{"fleet daily series", (Consumer<AgentMetricsQueryService>)
                s -> s.getDailyStats(TENANT_ID, ORG_ID, 30)},
            new Object[]{"per-agent daily series", (Consumer<AgentMetricsQueryService>)
                s -> s.getDailyStatsByAgent(TENANT_ID, ORG_ID, 30, AGENT_ID)}
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("datingReaders")
    @DisplayName("dates the run by ended_at, and names started_at nowhere on its own")
    void datesTheRunByEndedAt(String label, Consumer<AgentMetricsQueryService> call) {
        // lenient: a summary reader calls getSingleResult and a series reader
        // getResultList, so exactly one of these two is used per case.
        lenient().when(query.getSingleResult()).thenReturn(summaryRow());
        lenient().when(query.getResultList()).thenReturn(List.of());

        call.accept(service);

        String sql = capturedSql();
        assertThat(sql).contains("COALESCE(ae.ended_at, ae.started_at)");
        assertThat(bareStartedAtOccurrences(sql))
            .as("a bare ae.started_at left in: %s", sql)
            .isZero();
    }

    @Test
    @DisplayName("a summary asks for the LATEST end, not the latest start")
    void summaryTakesTheLatestEnd() {
        when(query.getSingleResult()).thenReturn(summaryRow());

        service.getChatSummary(TENANT_ID, ORG_ID);

        assertThat(capturedSql())
            .contains("MAX(COALESCE(ae.ended_at, ae.started_at)) AS last_execution_at");
    }

    @Test
    @DisplayName("a daily series buckets, filters and groups on the SAME expression, or Postgres rejects the grouping")
    void dailySeriesUsesOneExpressionThroughout() {
        when(query.getResultList()).thenReturn(List.of());

        service.getDailyStats(TENANT_ID, ORG_ID, 30);

        String sql = capturedSql();
        String bucket = "DATE(COALESCE(ae.ended_at, ae.started_at) AT TIME ZONE 'UTC')";
        // SELECT, WHERE and GROUP BY must be textually identical: a GROUP BY that does not
        // match the selected expression is a runtime error, not a wrong number.
        assertThat(sql).contains("SELECT " + bucket + " AS execution_date");
        assertThat(sql).contains("WHERE " + bucket + " >= :since");
        assertThat(sql).contains("GROUP BY " + bucket);
    }

    @Test
    @DisplayName("an in-flight row with no end still dates by its start rather than vanishing from the aggregate")
    void inFlightRowFallsBackToItsStart() {
        when(query.getSingleResult()).thenReturn(summaryRow());

        service.getChatSummary(TENANT_ID, ORG_ID);

        // The COALESCE is the whole reason a NULL ended_at does not turn MAX into NULL or
        // drop the row from a DATE bucket. There is no such producer today - production
        // holds zero RUNNING rows - which is exactly why it must be asserted rather than
        // assumed to be obvious.
        assertThat(capturedSql()).contains("COALESCE(ae.ended_at, ae.started_at)");
    }

    @Test
    @DisplayName("no query in the class names ae.started_at outside the OCCURRED_AT constant")
    void noQueryReadsStartedAtDirectly() throws Exception {
        // The five cases above are a hand-maintained list, so a SIXTH dating query written
        // tomorrow on ae.started_at would land green. This reads the class's own source and
        // closes that: the only place the column may be spelled is the constant that
        // documents why. Same shape as the repo's other call-site invariants
        // (ParamAliasCreatorParityTest, JsonbWritesCallsiteInvariantTest) - the point is
        // that the next occurrence fails a build instead of moving a number on a dashboard.
        Path source = Path.of("src/main/java/com/apimarketplace/agent/service/AgentMetricsQueryService.java");
        assertThat(source).as("source must be readable from the module root").exists();

        List<String> offenders = new ArrayList<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(source)) {
            lineNumber++;
            String code = line.contains("//") ? line.substring(0, line.indexOf("//")) : line;
            if (code.contains("* ")) continue;                      // javadoc prose
            if (code.contains("OCCURRED_AT =")) continue;           // the constant itself
            if (code.replace("COALESCE(ae.ended_at, ae.started_at)", "").contains("ae.started_at")) {
                offenders.add(lineNumber + ": " + line.trim());
            }
        }
        assertThat(offenders)
            .as("a query dating an execution by started_at bypasses OCCURRED_AT")
            .isEmpty();
    }

    private String capturedSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createNativeQuery(sql.capture());
        return sql.getValue();
    }

    /** Occurrences of {@code ae.started_at} that are NOT inside the COALESCE fallback. */
    private static int bareStartedAtOccurrences(String sql) {
        return sql.replace("COALESCE(ae.ended_at, ae.started_at)", "").split("ae\\.started_at", -1).length - 1;
    }

    /** A summary row shaped like the 12-column SELECT the two summary readers issue. */
    private static Object[] summaryRow() {
        return new Object[]{0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, null, null, null};
    }
}
