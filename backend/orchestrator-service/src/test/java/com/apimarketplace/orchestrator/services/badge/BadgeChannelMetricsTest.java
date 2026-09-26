package com.apimarketplace.orchestrator.services.badge;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.publication.client.PublicationClient;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The chat-channel trophies: what they measure, and what they must NOT count.
 *
 * <p>The {@code EntityManager} is a mock, so this pins the statement's shape and the mapping of
 * its columns, not Postgres semantics; the rules that matter are written as SQL fragments the
 * test asserts on, each one the difference between a trophy earned and a trophy given away.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("badge channel metrics")
class BadgeChannelMetricsTest {

    private static final String TENANT = "42";

    @Mock private PublicationClient publicationClient;
    @Mock private AuthClient authClient;
    @Mock private EntityManager entityManager;

    private BadgeStatsCollector collector;
    private final List<String> statements = new ArrayList<>();
    private final List<Map.Entry<String, Object>> bound = new ArrayList<>();
    private Object[] row = new Object[]{3L, 2L, 7L};
    private RuntimeException failure;

    @BeforeEach
    void setUp() throws Exception {
        collector = new BadgeStatsCollector(publicationClient, authClient);
        Field em = BadgeStatsCollector.class.getDeclaredField("entityManager");
        em.setAccessible(true);
        em.set(collector, entityManager);
        when(entityManager.createNativeQuery(anyString())).thenAnswer(invocation -> {
            statements.add(invocation.getArgument(0));
            Query query = mock(Query.class);
            when(query.setParameter(anyString(), any())).thenAnswer(bind -> {
                bound.add(Map.entry(bind.getArgument(0), bind.getArgument(1)));
                return query;
            });
            if (failure != null) {
                when(query.getSingleResult()).thenThrow(failure);
            } else {
                when(query.getSingleResult()).thenReturn(row);
            }
            return query;
        });
    }

    private String channelStatement() {
        return statements.stream().filter(sql -> sql.contains("chat_channel_links")).findFirst()
                .orElseThrow(() -> new AssertionError("no chat-channel statement was issued"));
    }

    @Test
    @DisplayName("maps connected destinations, distinct services and remote decisions from one statement")
    void mapsTheThreeMetrics() {
        BadgeStats stats = collector.collect(TENANT, Set.of(BadgeMetric.CHANNELS_CONNECTED));

        assertThat(stats.get(BadgeMetric.CHANNELS_CONNECTED)).isEqualTo(3);
        assertThat(stats.get(BadgeMetric.CHANNEL_SERVICES)).isEqualTo(2);
        assertThat(stats.get(BadgeMetric.REMOTE_DECISIONS)).isEqualTo(7);
        assertThat(statements.stream().filter(sql -> sql.contains("chat_channel_links")).count()).isEqualTo(1);
        assertThat(bound).contains(Map.entry("tenantId", TENANT));
    }

    @Test
    @DisplayName("a destination counts only once a real message reached it, not when a connect was merely tried")
    void onlyVerifiedDestinationsCount() {
        collector.collect(TENANT, Set.of(BadgeMetric.CHANNELS_CONNECTED));

        String sql = channelStatement();
        assertThat(sql).contains("tenant_id = :tenantId AND verified_at IS NOT NULL");
        assertThat(sql).contains("COUNT(DISTINCT b.channel)");
    }

    @Test
    @DisplayName("an approval decided in the app is not a remote decision: only one resolved by its own service counts")
    void appDecisionsDoNotCount() {
        collector.collect(TENANT, Set.of(BadgeMetric.REMOTE_DECISIONS));

        String sql = channelStatement();
        // Without this join the badge would count every delegated approval, including the ones
        // somebody clicked in the app, and "remote control" would be handed out for nothing.
        assertThat(sql).contains("s.resolved_by LIKE d.channel || ':%'");
        // Agent requests are only ever resolved from their chat, and record who pressed.
        assertThat(sql).contains("status = 'RESOLVED'").contains("decided_by IS NOT NULL");
    }

    @Test
    @DisplayName("the statement is not issued once every channel trophy is unlocked")
    void skippedWhenNotNeeded() {
        collector.collect(TENANT, Set.of(BadgeMetric.WORKFLOWS_CREATED));

        assertThat(statements).noneMatch(sql -> sql.contains("chat_channel_links"));
        verifyNoInteractions(publicationClient);
    }

    @Test
    @DisplayName("a failing statement leaves the metrics at zero instead of failing the badges page")
    void failureReadsAsZero() {
        // What a missing table or a lock timeout really raises, not a stand-in NPE.
        failure = new PersistenceException("relation \"orchestrator.chat_channel_links\" does not exist");

        BadgeStats stats = collector.collect(TENANT, Set.of(BadgeMetric.CHANNELS_CONNECTED));

        assertThat(stats.get(BadgeMetric.CHANNELS_CONNECTED)).isZero();
        assertThat(stats.get(BadgeMetric.CHANNEL_SERVICES)).isZero();
        assertThat(stats.get(BadgeMetric.REMOTE_DECISIONS)).isZero();
    }

    @ParameterizedTest(name = "{0} alone still reads all three")
    @EnumSource(value = BadgeMetric.class, names = {"CHANNELS_CONNECTED", "CHANNEL_SERVICES", "REMOTE_DECISIONS"})
    @DisplayName("any one channel metric still needed issues the statement, which fills all three")
    void anyOneMetricFillsAllThree(BadgeMetric needed) {
        BadgeStats stats = collector.collect(TENANT, Set.of(needed));

        assertThat(statements.stream().filter(sql -> sql.contains("chat_channel_links")).count()).isEqualTo(1);
        assertThat(stats.get(BadgeMetric.CHANNELS_CONNECTED)).isEqualTo(3);
        assertThat(stats.get(BadgeMetric.CHANNEL_SERVICES)).isEqualTo(2);
        assertThat(stats.get(BadgeMetric.REMOTE_DECISIONS)).isEqualTo(7);
    }

    @Test
    @DisplayName("every new metric has trophies, climbing in tier inside its family")
    void catalogCoversTheNewMetrics() {
        Map<BadgeMetric, List<BadgeDefinition>> byMetric = BadgeCatalog.all().stream()
                .collect(Collectors.groupingBy(BadgeDefinition::metric));

        for (BadgeMetric metric : List.of(BadgeMetric.CHANNELS_CONNECTED, BadgeMetric.CHANNEL_SERVICES,
                BadgeMetric.REMOTE_DECISIONS)) {
            List<BadgeDefinition> defs = byMetric.get(metric);
            assertThat(defs).as("trophies for %s", metric).isNotEmpty();
            for (int i = 1; i < defs.size(); i++) {
                assertThat(defs.get(i).threshold()).isGreaterThan(defs.get(i - 1).threshold());
                assertThat(defs.get(i).tier().ordinal()).isGreaterThanOrEqualTo(defs.get(i - 1).tier().ordinal());
            }
        }
        // Up to four services exist, so the top multichannel trophy must be reachable.
        assertThat(byMetric.get(BadgeMetric.CHANNEL_SERVICES))
                .allMatch(def -> def.threshold() <= 5);
    }
}
