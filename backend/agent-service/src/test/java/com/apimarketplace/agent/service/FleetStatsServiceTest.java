package com.apimarketplace.agent.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link FleetStatsService} - the cache-aside + single-flight + parallel
 * orchestration that replaced the controller's four sequential GROUP-BY calls.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FleetStatsService")
class FleetStatsServiceTest {

    private static final String TENANT = "tenant-42";
    private static final String ORG = "org-acme";

    @Mock private AgentMetricsQueryService queryService;
    @Mock private FleetStatsCacheService cacheService;

    private FleetStatsService service;

    @BeforeEach
    void setUp() {
        service = new FleetStatsService(queryService, cacheService);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    @DisplayName("cache HIT returns the cached payload and never touches the query service")
    void cacheHitShortCircuitsTheQueryService() {
        Map<String, Object> cached = Map.of(
            "toolStats", List.of(Map.of("agentId", "a")),
            "resourceStats", List.of(),
            "subAgentStats", List.of(),
            "modelStats", List.of());
        when(cacheService.get(ORG)).thenReturn(Optional.of(cached));

        Map<String, Object> result = service.getFleetStats(TENANT, ORG);

        assertThat(result).isSameAs(cached);
        // The whole point of the cache: a warm read does ZERO database work.
        verifyNoInteractions(queryService);
        verify(cacheService, never()).put(anyString(), any());
    }

    @Test
    @DisplayName("cache MISS runs all four aggregations, assembles the four keys, and caches the complete result")
    void cacheMissComputesAssemblesAndCaches() {
        when(cacheService.get(ORG)).thenReturn(Optional.empty());

        List<Map<String, Object>> tools = List.of(Map.of("agentId", "a", "toolName", "web_search"));
        List<Map<String, Object>> resources = List.of(Map.of("agentId", "a", "resourceId", "123"));
        List<Map<String, Object>> subAgents = List.of(Map.of("agentId", "a", "calleeAgentId", "b"));
        List<Map<String, Object>> models = List.of(Map.of("agentId", "a", "model", "deepseek-chat"));
        when(queryService.getAllToolStatsByAgent(TENANT, ORG)).thenReturn(tools);
        when(queryService.getAllResourceStatsByAgent(TENANT, ORG)).thenReturn(resources);
        when(queryService.getAllSubAgentCallStats(TENANT, ORG)).thenReturn(subAgents);
        when(queryService.getAllModelStatsByAgent(TENANT, ORG)).thenReturn(models);

        Map<String, Object> result = service.getFleetStats(TENANT, ORG);

        assertThat(result)
            .containsOnlyKeys("toolStats", "resourceStats", "subAgentStats", "modelStats")
            .containsEntry("toolStats", tools)
            .containsEntry("resourceStats", resources)
            .containsEntry("subAgentStats", subAgents)
            .containsEntry("modelStats", models);
        // A complete result is cached for the next reader.
        verify(cacheService).put(ORG, result);
    }

    @Test
    @DisplayName("the four aggregations run CONCURRENTLY on the dedicated fleet-stats pool, not sequentially")
    void aggregationsRunConcurrentlyOnDedicatedPool() {
        when(cacheService.get(ORG)).thenReturn(Optional.empty());

        // A 4-party barrier: every aggregation must reach it before ANY can proceed. A
        // sequential implementation would deadlock here (only one query in flight) and the
        // 2s barrier wait would trip - degrading queries to null and leaving < 4 threads
        // observed. Passing proves genuine parallelism.
        CyclicBarrier barrier = new CyclicBarrier(4);
        Set<String> threads = ConcurrentHashMap.newKeySet();
        org.mockito.stubbing.Answer<List<Map<String, Object>>> rendezvous = inv -> {
            threads.add(Thread.currentThread().getName());
            barrier.await(2, TimeUnit.SECONDS);
            return List.of();
        };
        when(queryService.getAllToolStatsByAgent(TENANT, ORG)).thenAnswer(rendezvous);
        when(queryService.getAllResourceStatsByAgent(TENANT, ORG)).thenAnswer(rendezvous);
        when(queryService.getAllSubAgentCallStats(TENANT, ORG)).thenAnswer(rendezvous);
        when(queryService.getAllModelStatsByAgent(TENANT, ORG)).thenAnswer(rendezvous);

        service.getFleetStats(TENANT, ORG);

        assertThat(threads).hasSize(4);
        assertThat(threads).allMatch(name -> name.startsWith("fleet-stats-"));
    }

    @Test
    @DisplayName("single-flight: concurrent cache misses for the same workspace collapse onto ONE computation")
    void concurrentMissesAreSingleFlighted() throws Exception {
        AtomicInteger cacheGets = new AtomicInteger();
        when(cacheService.get(ORG)).thenAnswer(inv -> {
            cacheGets.incrementAndGet();
            return Optional.empty();
        });

        // The leader's tool query blocks until released, so a follower arrives mid-compute.
        CountDownLatch leaderInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(queryService.getAllToolStatsByAgent(TENANT, ORG)).thenAnswer(inv -> {
            leaderInside.countDown();
            release.await(2, TimeUnit.SECONDS);
            return List.of(Map.of("agentId", "a", "toolName", "web_search"));
        });
        when(queryService.getAllResourceStatsByAgent(TENANT, ORG)).thenReturn(List.of());
        when(queryService.getAllSubAgentCallStats(TENANT, ORG)).thenReturn(List.of());
        when(queryService.getAllModelStatsByAgent(TENANT, ORG)).thenReturn(List.of());

        CompletableFuture<Map<String, Object>> leader =
            CompletableFuture.supplyAsync(() -> service.getFleetStats(TENANT, ORG));
        assertThat(leaderInside.await(2, TimeUnit.SECONDS)).isTrue(); // leader is mid-compute

        CompletableFuture<Map<String, Object>> follower =
            CompletableFuture.supplyAsync(() -> service.getFleetStats(TENANT, ORG));
        // Wait until the follower has passed its own cache lookup (so it has reached the
        // single-flight registry and attached to the leader's in-flight promise) before
        // letting the leader finish - makes the assertion race-free.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (cacheGets.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(cacheGets.get()).isGreaterThanOrEqualTo(2);
        Thread.sleep(100); // cover the few statements between cache.get() and putIfAbsent
        release.countDown();

        Map<String, Object> leaderResult = leader.get(2, TimeUnit.SECONDS);
        Map<String, Object> followerResult = follower.get(2, TimeUnit.SECONDS);

        // Both callers got the SAME computed payload, but the expensive query ran ONCE.
        assertThat(followerResult).isEqualTo(leaderResult);
        verify(queryService, times(1)).getAllToolStatsByAgent(TENANT, ORG);
        verify(queryService, times(1)).getAllModelStatsByAgent(TENANT, ORG);
        verify(cacheService, times(1)).put(eq(ORG), any());
    }

    @Test
    @DisplayName("a single failing aggregation degrades to an empty group and the partial result is NOT cached")
    void partialFailureReturnsEmptyGroupAndSkipsCache() {
        when(cacheService.get(ORG)).thenReturn(Optional.empty());

        List<Map<String, Object>> tools = List.of(Map.of("agentId", "a", "toolName", "web_search"));
        when(queryService.getAllToolStatsByAgent(TENANT, ORG)).thenReturn(tools);
        // resourceStats blows up - must not nuke the other three, must not pin a degraded view.
        when(queryService.getAllResourceStatsByAgent(TENANT, ORG))
            .thenThrow(new RuntimeException("boom"));
        when(queryService.getAllSubAgentCallStats(TENANT, ORG)).thenReturn(List.of());
        when(queryService.getAllModelStatsByAgent(TENANT, ORG)).thenReturn(List.of());

        Map<String, Object> result = service.getFleetStats(TENANT, ORG);

        assertThat(result).containsEntry("toolStats", tools);
        assertThat(result.get("resourceStats")).asInstanceOf(
            org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();
        // Incomplete → do not cache, so the next request retries the DB instead of being
        // served a degraded payload for the whole TTL.
        verify(cacheService, never()).put(eq(ORG), any());
    }
}
