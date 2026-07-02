package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.client.queue.AgentQueueProducer;
import com.apimarketplace.agent.client.queue.RedisResultWaiter;
import com.apimarketplace.agent.config.AgentScalingConfig;
import com.apimarketplace.agent.provider.LLMProviderException;
import com.apimarketplace.agent.ratelimit.ModelRateLimit;
import com.apimarketplace.agent.ratelimit.ProviderRateLimiter;
import com.apimarketplace.agent.ratelimit.RateLimitConfig;
import com.apimarketplace.agent.ratelimit.RateLimitMode;
import com.apimarketplace.agent.ratelimit.RateLimitStrategy;
import com.apimarketplace.agent.ratelimit.RateLimitWindow;
import com.apimarketplace.agent.ratelimit.RateLimitWindowFactory;
import com.apimarketplace.common.web.TenantResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AgentQueueWorkerService}.
 *
 * Tests cover:
 * 1. Dequeue → execute → publish flow for all three agent types
 * 2. Error handling with error result publishing
 * 3. Graceful shutdown behavior
 * 4. JSON string escaping
 * 5. Result publishing to Redis key + pub/sub channel
 */
@DisplayName("AgentQueueWorkerService")
@ExtendWith(MockitoExtension.class)
class AgentQueueWorkerServiceTest {

    @Mock
    private AgentRemoteExecutionService executionService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private ListOperations<String, String> listOperations;

    @Mock
    private ActiveStreamRegistry activeStreamRegistry;

    /** Generous drain window for regular tests: idle/fast pools terminate well within it. */
    private static final long TEST_DRAIN_TIMEOUT_SECONDS = 5;

    private ObjectMapper objectMapper;
    private AgentScalingConfig config;
    private AgentQueueWorkerService workerService;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        config = new AgentScalingConfig();
        // Use minimal pool sizes for tests
        config.getWorker().setAgentPoolSize(1);
        config.getWorker().setClassifyPoolSize(1);
        config.getWorker().setGuardrailPoolSize(1);
        config.getConsumer().setPollIntervalMs(1000);

        workerService = new AgentQueueWorkerService(config, executionService, objectMapper, redisTemplate,
            activeStreamRegistry, TEST_DRAIN_TIMEOUT_SECONDS);
    }

    // ========== processTask Tests ==========

    @Nested
    @DisplayName("processTask")
    class ProcessTaskTests {

        @BeforeEach
        void setUpRedis() {
            lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            lenient().when(redisTemplate.hasKey(anyString())).thenReturn(false);
        }

        @Test
        @DisplayName("Agent task: deserializes, executes, and publishes result")
        void agentTaskExecuteAndPublish() throws Exception {
            String payload = "{\"prompt\":\"hello\"}";
            AgentExecutionTask task = new AgentExecutionTask(
                "corr-1", AgentExecutionTask.TYPE_AGENT, payload, 0, Map.of());

            String expectedResult = "{\"success\":true,\"response\":\"world\"}";
            when(executionService.executeByType("agent", payload)).thenReturn(expectedResult);

            workerService.processTask(task);

            // Verify result was published to Redis key
            verify(valueOperations).set(
                eq("agent:result:corr-1"), eq(expectedResult), eq(1L), eq(TimeUnit.HOURS));

            // Verify result was published to pub/sub channel
            verify(redisTemplate).convertAndSend(
                eq("agent:result:channel:corr-1"), eq(expectedResult));
        }

        @Test
        @DisplayName("Classify task: routes to classify execution")
        void classifyTaskExecuteAndPublish() throws Exception {
            String payload = "{\"content\":\"test\",\"categories\":[]}";
            AgentExecutionTask task = new AgentExecutionTask(
                "corr-2", AgentExecutionTask.TYPE_CLASSIFY, payload, 10, Map.of());

            String expectedResult = "{\"success\":true,\"selectedCategory\":\"billing\"}";
            when(executionService.executeByType("classify", payload)).thenReturn(expectedResult);

            workerService.processTask(task);

            verify(valueOperations).set(
                eq("agent:result:corr-2"), eq(expectedResult), eq(1L), eq(TimeUnit.HOURS));
            verify(redisTemplate).convertAndSend(
                eq("agent:result:channel:corr-2"), eq(expectedResult));
        }

        @Test
        @DisplayName("Guardrail task: routes to guardrail execution")
        void guardrailTaskExecuteAndPublish() throws Exception {
            String payload = "{\"content\":\"test\",\"rules\":[]}";
            AgentExecutionTask task = new AgentExecutionTask(
                "corr-3", AgentExecutionTask.TYPE_GUARDRAIL, payload, 5, Map.of());

            String expectedResult = "{\"success\":true,\"passed\":true}";
            when(executionService.executeByType("guardrail", payload)).thenReturn(expectedResult);

            workerService.processTask(task);

            verify(valueOperations).set(
                eq("agent:result:corr-3"), eq(expectedResult), eq(1L), eq(TimeUnit.HOURS));
        }

        @Test
        @DisplayName("Execution failure publishes error result")
        void executionFailurePublishesError() throws Exception {
            String payload = "{\"prompt\":\"fail\"}";
            AgentExecutionTask task = new AgentExecutionTask(
                "corr-err", AgentExecutionTask.TYPE_AGENT, payload, 0, Map.of());

            when(executionService.executeByType("agent", payload))
                .thenThrow(new RuntimeException("LLM timeout"));

            workerService.processTask(task);

            // Should publish error result (not throw)
            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(valueOperations).set(
                eq("agent:result:corr-err"), captor.capture(), eq(1L), eq(TimeUnit.HOURS));

            String errorResult = captor.getValue();
            assertThat(errorResult).contains("\"success\":false");
            assertThat(errorResult).contains("LLM timeout");
        }

        @Test
        @DisplayName("Org scope falls back to top-level organizationId when credentials are absent")
        void orgScopeFallsBackToTopLevelOrganizationId() throws Exception {
            String payload = objectMapper.writeValueAsString(Map.of(
                "prompt", "hello",
                "organizationId", "org-top-level"));
            AgentExecutionTask task = new AgentExecutionTask(
                "corr-org-top", AgentExecutionTask.TYPE_AGENT, payload, 0, Map.of());
            AtomicReference<String> observedOrg = new AtomicReference<>();

            when(executionService.executeByType(AgentExecutionTask.TYPE_AGENT, payload))
                .thenAnswer(inv -> {
                    observedOrg.set(TenantResolver.currentRequestOrganizationId());
                    return "{\"success\":true}";
                });

            workerService.processTask(task);

            assertThat(observedOrg).hasValue("org-top-level");
        }

        @Test
        @DisplayName("Queued worker restores organization role from payload credentials")
        void queuedWorkerRestoresOrganizationRoleFromPayloadCredentials() throws Exception {
            String payload = objectMapper.writeValueAsString(Map.of(
                "prompt", "role scoped",
                "credentials", Map.of(
                    "__orgId__", "org-role-bound",
                    "__orgRole__", "OWNER")));
            AgentExecutionTask task = new AgentExecutionTask(
                "corr-org-role", AgentExecutionTask.TYPE_AGENT, payload, 0, Map.of());
            AtomicReference<String> observedOrg = new AtomicReference<>();
            AtomicReference<String> observedRole = new AtomicReference<>();

            when(executionService.executeByType(AgentExecutionTask.TYPE_AGENT, payload))
                .thenAnswer(inv -> {
                    observedOrg.set(TenantResolver.currentRequestOrganizationId());
                    observedRole.set(TenantResolver.currentRequestOrganizationRole());
                    return "{\"success\":true}";
                });

            workerService.processTask(task);

            assertThat(observedOrg).hasValue("org-role-bound");
            assertThat(observedRole).hasValue("OWNER");
            assertThat(TenantResolver.currentRequestOrganizationId()).isNull();
            assertThat(TenantResolver.currentRequestOrganizationRole()).isNull();
        }

        @Test
        @DisplayName("Regression backstop: org scope binds from structured metadata when the payload "
                + "credentials were never stamped with __orgId__")
        void orgScopeBindsFromMetadataWhenPayloadHasNoOrg() throws Exception {
            // A producer that forgets credentials.__orgId__ used to dequeue into a NULL
            // org scope: every org-scoped persist on the worker then fail-louded. The
            // producer now also writes orgId/orgRole into the structured metadata and
            // the worker falls back to it.
            String payload = "{\"prompt\":\"no credentials stamp\"}";
            AgentExecutionTask task = new AgentExecutionTask(
                "corr-org-meta", AgentExecutionTask.TYPE_AGENT, payload, 0,
                Map.of("orgId", "org-from-metadata", "orgRole", "MEMBER"));
            AtomicReference<String> observedOrg = new AtomicReference<>();
            AtomicReference<String> observedRole = new AtomicReference<>();

            when(executionService.executeByType(AgentExecutionTask.TYPE_AGENT, payload))
                .thenAnswer(inv -> {
                    observedOrg.set(TenantResolver.currentRequestOrganizationId());
                    observedRole.set(TenantResolver.currentRequestOrganizationRole());
                    return "{\"success\":true}";
                });

            workerService.processTask(task);

            assertThat(observedOrg).hasValue("org-from-metadata");
            assertThat(observedRole).hasValue("MEMBER");
        }

        @Test
        @DisplayName("Payload credentials org takes precedence over the metadata backstop")
        void payloadCredentialsOrgWinsOverMetadata() throws Exception {
            String payload = objectMapper.writeValueAsString(Map.of(
                "prompt", "both channels",
                "credentials", Map.of("__orgId__", "org-from-payload")));
            AgentExecutionTask task = new AgentExecutionTask(
                "corr-org-both", AgentExecutionTask.TYPE_AGENT, payload, 0,
                Map.of("orgId", "org-from-metadata"));
            AtomicReference<String> observedOrg = new AtomicReference<>();

            when(executionService.executeByType(AgentExecutionTask.TYPE_AGENT, payload))
                .thenAnswer(inv -> {
                    observedOrg.set(TenantResolver.currentRequestOrganizationId());
                    return "{\"success\":true}";
                });

            workerService.processTask(task);

            assertThat(observedOrg).hasValue("org-from-payload");
        }

        @Test
        @DisplayName("User roles metadata is forwarded to execution router for queued bridge policies")
        void userRolesMetadataForwardedToExecutionRouter() throws Exception {
            String payload = "{\"prompt\":\"admin bridge\"}";
            AgentExecutionTask task = new AgentExecutionTask(
                "corr-admin", AgentExecutionTask.TYPE_AGENT, payload, 0,
                Map.of("userRoles", "ADMIN,USER"));

            String expectedResult = "{\"success\":true}";
            when(executionService.executeByType(AgentExecutionTask.TYPE_AGENT, payload, "ADMIN,USER"))
                .thenReturn(expectedResult);

            workerService.processTask(task);

            verify(executionService).executeByType(AgentExecutionTask.TYPE_AGENT, payload, "ADMIN,USER");
            verify(valueOperations).set(
                eq("agent:result:corr-admin"), eq(expectedResult), eq(1L), eq(TimeUnit.HOURS));
        }

        @Test
        @DisplayName("Hundred task mixed model burst: realistic mocked agent responses preserve per-model RPM/TPM outcomes and org scope")
        void hundredTaskMixedModelBurstPublishesRealisticResponsesWithPerModelRateLimits() throws Exception {
            ProviderRateLimiter rateLimiter = modelAwareRateLimiter();
            ConcurrentMap<String, String> publishedResults = new ConcurrentHashMap<>();
            ConcurrentMap<String, String> expectedOrgByRunId = new ConcurrentHashMap<>();
            ConcurrentMap<String, String> observedOrgByRunId = new ConcurrentHashMap<>();

            doAnswer(inv -> {
                publishedResults.put(inv.getArgument(0), inv.getArgument(1));
                return null;
            }).when(valueOperations).set(anyString(), anyString(), eq(1L), eq(TimeUnit.HOURS));

            when(executionService.executeByType(eq(AgentExecutionTask.TYPE_AGENT), anyString()))
                .thenAnswer(inv -> executeRealisticAgentResponse(inv.getArgument(1), rateLimiter, observedOrgByRunId));

            List<AgentExecutionTask> tasks = hundredMixedModelAgentTasks(expectedOrgByRunId);

            ExecutorService executor = Executors.newFixedThreadPool(16);
            List<Future<?>> futures = new ArrayList<>();
            try {
                for (AgentExecutionTask task : tasks) {
                    futures.add(executor.submit(() -> workerService.processTask(task)));
                }
                for (Future<?> future : futures) {
                    future.get(5, TimeUnit.SECONDS);
                }
            } finally {
                executor.shutdownNow();
            }

            assertThat(publishedResults).hasSize(tasks.size());
            assertThat(observedOrgByRunId).isEqualTo(expectedOrgByRunId);

            List<JsonNode> responses = new ArrayList<>();
            for (String result : publishedResults.values()) {
                responses.add(objectMapper.readTree(result));
            }

            assertHundredMixedModelOutcomes(tasks, responses, rateLimiter);
        }

        @Test
        @DisplayName("Queue drain metrics: hundred agent tasks respect per-model RPM/TPM and drain with worker concurrency bound")
        void queueWorkersDrainHundredAgentTasksWithRateLimitMetrics() throws Exception {
            config.getWorker().setAgentPoolSize(8);
            config.getWorker().setClassifyPoolSize(1);
            config.getWorker().setGuardrailPoolSize(1);
            config.getConsumer().setPollIntervalMs(10);
            workerService = new AgentQueueWorkerService(config, executionService, objectMapper, redisTemplate,
                activeStreamRegistry, TEST_DRAIN_TIMEOUT_SECONDS);

            ProviderRateLimiter rateLimiter = modelAwareRateLimiter();
            ConcurrentMap<String, String> publishedResults = new ConcurrentHashMap<>();
            ConcurrentMap<String, String> expectedOrgByRunId = new ConcurrentHashMap<>();
            ConcurrentMap<String, String> observedOrgByRunId = new ConcurrentHashMap<>();
            List<AgentExecutionTask> tasks = hundredMixedModelAgentTasks(expectedOrgByRunId);
            ConcurrentLinkedQueue<String> queuedTasks = new ConcurrentLinkedQueue<>();
            for (AgentExecutionTask task : tasks) {
                queuedTasks.add(objectMapper.writeValueAsString(task));
            }

            CountDownLatch publishedLatch = new CountDownLatch(tasks.size());
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger maxInFlight = new AtomicInteger();
            AtomicInteger executions = new AtomicInteger();

            when(redisTemplate.opsForList()).thenReturn(listOperations);
            doAnswer(inv -> {
                String queueKey = inv.getArgument(0);
                if ((AgentQueueWorkerService.QUEUE_PREFIX + AgentExecutionTask.TYPE_AGENT).equals(queueKey)) {
                    String nextTask = queuedTasks.poll();
                    if (nextTask != null) {
                        return nextTask;
                    }
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }).when(listOperations).rightPop(anyString(), anyLong(), any(TimeUnit.class));

            doAnswer(inv -> {
                publishedResults.put(inv.getArgument(0), inv.getArgument(1));
                publishedLatch.countDown();
                return null;
            }).when(valueOperations).set(anyString(), anyString(), eq(1L), eq(TimeUnit.HOURS));

            when(executionService.executeByType(eq(AgentExecutionTask.TYPE_AGENT), anyString()))
                .thenAnswer(inv -> {
                    int current = inFlight.incrementAndGet();
                    maxInFlight.accumulateAndGet(current, Math::max);
                    executions.incrementAndGet();
                    try {
                        Thread.sleep(15);
                        return executeRealisticAgentResponse(inv.getArgument(1), rateLimiter, observedOrgByRunId);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });

            long startedAtNanos = System.nanoTime();
            boolean drained;
            workerService.start();
            try {
                drained = publishedLatch.await(5, TimeUnit.SECONDS);
            } finally {
                workerService.shutdown();
            }
            long drainMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);

            assertThat(drained).as("agent queue should publish all results before the timeout").isTrue();
            assertThat(queuedTasks).isEmpty();
            assertThat(executions.get()).isEqualTo(tasks.size());
            assertThat(maxInFlight.get()).isBetween(2, 8);
            assertThat(drainMs).isLessThan(5_000L);
            assertThat(observedOrgByRunId).isEqualTo(expectedOrgByRunId);

            List<JsonNode> responses = parseResponses(publishedResults);
            assertHundredMixedModelOutcomes(tasks, responses, rateLimiter);

            long succeeded = responses.stream().filter(response -> response.path("success").asBoolean()).count();
            long rateLimited = responses.stream()
                .filter(response -> "RATE_LIMITED".equals(response.path("stopReason").asText()))
                .count();
            System.out.printf(
                "AGENT_QUEUE_STRESS_METRICS tasks=%d published=%d executed=%d success=%d rateLimited=%d drainMs=%d maxConcurrent=%d workers=%d%n",
                tasks.size(), publishedResults.size(), executions.get(), succeeded, rateLimited, drainMs,
                maxInFlight.get(), config.getWorker().getAgentPoolSize());
        }

        @Test
        @DisplayName("Production config queues all shared-queue origins and physical types with fail-fast RPM/TPM denial metrics")
        void productionConfigQueuesAllAgentOriginsAndPhysicalTypesWithRateLimitMetrics() throws Exception {
            config.getWorker().setAgentPoolSize(8);
            config.getWorker().setClassifyPoolSize(4);
            config.getWorker().setGuardrailPoolSize(4);
            config.getConsumer().setPollIntervalMs(100);
            workerService = new AgentQueueWorkerService(config, executionService, objectMapper, redisTemplate,
                activeStreamRegistry, TEST_DRAIN_TIMEOUT_SECONDS);

            ProviderRateLimiter rateLimiter = productionLikeRateLimiter();
            ConcurrentMap<String, String> publishedResults = new ConcurrentHashMap<>();
            ConcurrentMap<String, String> expectedOrgByCorrelationId = new ConcurrentHashMap<>();
            ConcurrentMap<String, String> observedOrgByCorrelationId = new ConcurrentHashMap<>();

            List<AgentExecutionTask> agentTasks = List.of(
                typedTask("prod-agent-workflow-0", AgentExecutionTask.TYPE_AGENT, "workflow",
                    "tenant-agent-prod", "org-agent-prod", "google", "gemini-3-pro-preview", 1_000, 0,
                    expectedOrgByCorrelationId),
                typedTask("prod-agent-chat-1", AgentExecutionTask.TYPE_AGENT, "chat_general",
                    "tenant-agent-prod", "org-agent-prod", "google", "gemini-3-pro-preview", 1_000, 1,
                    expectedOrgByCorrelationId),
                typedTask("prod-agent-widget-2", AgentExecutionTask.TYPE_AGENT, "widget",
                    "tenant-agent-prod", "org-agent-prod", "google", "gemini-3-pro-preview", 1_000, 2,
                    expectedOrgByCorrelationId),
                typedTask("prod-agent-webhook-3", AgentExecutionTask.TYPE_AGENT, "webhook",
                    "tenant-agent-prod", "org-agent-prod", "google", "gemini-3-pro-preview", 1_000, 3,
                    expectedOrgByCorrelationId),
                typedTask("prod-agent-schedule-4", AgentExecutionTask.TYPE_AGENT, "schedule",
                    "tenant-agent-prod", "org-agent-prod", "google", "gemini-3-pro-preview", 1_000, 4,
                    expectedOrgByCorrelationId),
                typedTask("prod-agent-workflow-6", AgentExecutionTask.TYPE_AGENT, "workflow",
                    "tenant-agent-prod", "org-agent-prod", "google", "gemini-3-pro-preview", 1_000, 6,
                    expectedOrgByCorrelationId),
                typedTask("prod-agent-task-7", AgentExecutionTask.TYPE_AGENT, "task",
                    "tenant-task-prod", "org-task-prod", "deepseek", "deepseek-chat", 1_000, 7,
                    expectedOrgByCorrelationId),
                typedTask("prod-agent-reviewer-8", AgentExecutionTask.TYPE_AGENT, "task_review",
                    "tenant-task-prod", "org-task-prod", "deepseek", "deepseek-chat", 1_000, 8,
                    expectedOrgByCorrelationId)
            );
            List<AgentExecutionTask> classifyTasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                classifyTasks.add(typedTask("prod-classify-" + i, AgentExecutionTask.TYPE_CLASSIFY, "classify",
                    "tenant-classify-prod", "org-classify-prod", "mistral", "mistral-large-latest", 1_000, i,
                    expectedOrgByCorrelationId));
            }
            List<AgentExecutionTask> guardrailTasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                guardrailTasks.add(typedTask("prod-guardrail-" + i, AgentExecutionTask.TYPE_GUARDRAIL, "guardrail",
                    "tenant-guardrail-prod", "org-guardrail-prod", "google", "gemini-3-pro-preview", 500, i,
                    expectedOrgByCorrelationId));
            }

            Map<String, ConcurrentLinkedQueue<String>> queues = Map.of(
                AgentQueueWorkerService.QUEUE_PREFIX + AgentExecutionTask.TYPE_AGENT, encodeTasks(agentTasks),
                AgentQueueWorkerService.QUEUE_PREFIX + AgentExecutionTask.TYPE_CLASSIFY, encodeTasks(classifyTasks),
                AgentQueueWorkerService.QUEUE_PREFIX + AgentExecutionTask.TYPE_GUARDRAIL, encodeTasks(guardrailTasks)
            );
            ConcurrentMap<String, ConcurrentLinkedQueue<String>> dequeueOrderByType = new ConcurrentHashMap<>();
            ConcurrentMap<String, AtomicInteger> inFlightByType = new ConcurrentHashMap<>();
            ConcurrentMap<String, AtomicInteger> maxInFlightByType = new ConcurrentHashMap<>();
            ConcurrentMap<String, AtomicInteger> executionsByType = new ConcurrentHashMap<>();
            CountDownLatch publishedLatch = new CountDownLatch(
                agentTasks.size() + classifyTasks.size() + guardrailTasks.size());
            Object dequeueLock = new Object();

            when(redisTemplate.opsForList()).thenReturn(listOperations);
            doAnswer(inv -> {
                String queueKey = inv.getArgument(0);
                synchronized (dequeueLock) {
                    ConcurrentLinkedQueue<String> queue = queues.get(queueKey);
                    if (queue != null) {
                        String nextTask = queue.poll();
                        if (nextTask != null) {
                            AgentExecutionTask task = objectMapper.readValue(nextTask, AgentExecutionTask.class);
                            dequeueOrderByType
                                .computeIfAbsent(task.agentType(), ignored -> new ConcurrentLinkedQueue<>())
                                .add(task.correlationId());
                            return nextTask;
                        }
                    }
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }).when(listOperations).rightPop(anyString(), anyLong(), any(TimeUnit.class));

            doAnswer(inv -> {
                publishedResults.put(inv.getArgument(0), inv.getArgument(1));
                publishedLatch.countDown();
                return null;
            }).when(valueOperations).set(anyString(), anyString(), eq(1L), eq(TimeUnit.HOURS));

            when(executionService.executeByType(anyString(), anyString()))
                .thenAnswer(inv -> {
                    String agentType = inv.getArgument(0);
                    int current = inFlightByType
                        .computeIfAbsent(agentType, ignored -> new AtomicInteger())
                        .incrementAndGet();
                    maxInFlightByType
                        .computeIfAbsent(agentType, ignored -> new AtomicInteger())
                        .accumulateAndGet(current, Math::max);
                    executionsByType
                        .computeIfAbsent(agentType, ignored -> new AtomicInteger())
                        .incrementAndGet();
                    try {
                        Thread.sleep(20);
                        return executeProductionLikeTypedResponse(
                            agentType, inv.getArgument(1), rateLimiter, observedOrgByCorrelationId);
                    } finally {
                        inFlightByType.get(agentType).decrementAndGet();
                    }
                });

            long startedAtNanos = System.nanoTime();
            boolean drained;
            workerService.start();
            try {
                drained = publishedLatch.await(5, TimeUnit.SECONDS);
            } finally {
                workerService.shutdown();
            }
            long drainMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);

            assertThat(drained).as("all production-like queues should drain before timeout").isTrue();
            assertThat(queues.values()).allMatch(ConcurrentLinkedQueue::isEmpty);
            assertThat(observedOrgByCorrelationId).isEqualTo(expectedOrgByCorrelationId);
            assertThat(publishedResults).hasSize(agentTasks.size() + classifyTasks.size() + guardrailTasks.size());
            assertThat(executionCount(executionsByType, AgentExecutionTask.TYPE_AGENT)).isEqualTo(agentTasks.size());
            assertThat(executionCount(executionsByType, AgentExecutionTask.TYPE_CLASSIFY)).isEqualTo(classifyTasks.size());
            assertThat(executionCount(executionsByType, AgentExecutionTask.TYPE_GUARDRAIL)).isEqualTo(guardrailTasks.size());
            assertThat(dequeueOrderByType.get(AgentExecutionTask.TYPE_AGENT))
                .containsExactlyElementsOf(agentTasks.stream().map(AgentExecutionTask::correlationId).toList());
            assertThat(dequeueOrderByType.get(AgentExecutionTask.TYPE_CLASSIFY))
                .containsExactlyElementsOf(classifyTasks.stream().map(AgentExecutionTask::correlationId).toList());
            assertThat(dequeueOrderByType.get(AgentExecutionTask.TYPE_GUARDRAIL))
                .containsExactlyElementsOf(guardrailTasks.stream().map(AgentExecutionTask::correlationId).toList());
            assertThat(maxConcurrent(maxInFlightByType, AgentExecutionTask.TYPE_AGENT))
                .isBetween(1, config.getWorker().getAgentPoolSize());
            assertThat(maxConcurrent(maxInFlightByType, AgentExecutionTask.TYPE_CLASSIFY))
                .isBetween(1, config.getWorker().getClassifyPoolSize());
            assertThat(maxConcurrent(maxInFlightByType, AgentExecutionTask.TYPE_GUARDRAIL))
                .isBetween(1, config.getWorker().getGuardrailPoolSize());
            assertThat(drainMs).isLessThan(5_000L);

            List<JsonNode> responses = parseResponses(publishedResults);
            assertProductionConfigQueueOutcomes(responses, rateLimiter);

            long succeeded = responses.stream().filter(response -> response.path("success").asBoolean()).count();
            long rateLimited = responses.stream()
                .filter(response -> "RATE_LIMITED".equals(response.path("stopReason").asText()))
                .count();
            System.out.printf(
                "AGENT_PROD_CONFIG_QUEUE_METRICS tasks=%d published=%d executed=%d success=%d rateLimited=%d drainMs=%d maxAgent=%d maxClassify=%d maxGuardrail=%d workers=%d/%d/%d%n",
                responses.size(), publishedResults.size(),
                executionCount(executionsByType, AgentExecutionTask.TYPE_AGENT)
                    + executionCount(executionsByType, AgentExecutionTask.TYPE_CLASSIFY)
                    + executionCount(executionsByType, AgentExecutionTask.TYPE_GUARDRAIL),
                succeeded, rateLimited, drainMs,
                maxConcurrent(maxInFlightByType, AgentExecutionTask.TYPE_AGENT),
                maxConcurrent(maxInFlightByType, AgentExecutionTask.TYPE_CLASSIFY),
                maxConcurrent(maxInFlightByType, AgentExecutionTask.TYPE_GUARDRAIL),
                config.getWorker().getAgentPoolSize(),
                config.getWorker().getClassifyPoolSize(),
                config.getWorker().getGuardrailPoolSize());
        }

        @Test
        @DisplayName("Horizontal scaling: two production instances drain the shared Redis agent queue with combined worker capacity")
        void twoProductionInstancesDrainSharedAgentQueueWithCombinedWorkerCapacity() throws Exception {
            int instances = 2;
            int workersPerInstance = 8;
            int totalAgentWorkers = instances * workersPerInstance;
            int taskCount = 64;

            AgentScalingConfig firstConfig = productionQueueConfig(workersPerInstance, 1, 1, 10);
            AgentScalingConfig secondConfig = productionQueueConfig(workersPerInstance, 1, 1, 10);
            AgentQueueWorkerService firstInstance =
                new AgentQueueWorkerService(firstConfig, executionService, objectMapper, redisTemplate,
                    activeStreamRegistry, TEST_DRAIN_TIMEOUT_SECONDS);
            AgentQueueWorkerService secondInstance =
                new AgentQueueWorkerService(secondConfig, executionService, objectMapper, redisTemplate,
                    activeStreamRegistry, TEST_DRAIN_TIMEOUT_SECONDS);

            ConcurrentLinkedQueue<String> agentQueue = new ConcurrentLinkedQueue<>();
            for (int i = 0; i < taskCount; i++) {
                agentQueue.add(objectMapper.writeValueAsString(horizontalAgentTask(i)));
            }

            ConcurrentMap<String, String> publishedResults = new ConcurrentHashMap<>();
            CountDownLatch firstWaveStarted = new CountDownLatch(totalAgentWorkers);
            CountDownLatch releaseFirstWave = new CountDownLatch(1);
            CountDownLatch publishedLatch = new CountDownLatch(taskCount);
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger maxInFlight = new AtomicInteger();
            AtomicInteger executions = new AtomicInteger();
            Object dequeueLock = new Object();

            when(redisTemplate.opsForList()).thenReturn(listOperations);
            doAnswer(inv -> {
                String queueKey = inv.getArgument(0);
                synchronized (dequeueLock) {
                    if ((AgentQueueWorkerService.QUEUE_PREFIX + AgentExecutionTask.TYPE_AGENT).equals(queueKey)) {
                        String nextTask = agentQueue.poll();
                        if (nextTask != null) {
                            return nextTask;
                        }
                    }
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }).when(listOperations).rightPop(anyString(), anyLong(), any(TimeUnit.class));

            doAnswer(inv -> {
                publishedResults.put(inv.getArgument(0), inv.getArgument(1));
                publishedLatch.countDown();
                return null;
            }).when(valueOperations).set(anyString(), anyString(), eq(1L), eq(TimeUnit.HOURS));

            when(executionService.executeByType(eq(AgentExecutionTask.TYPE_AGENT), anyString()))
                .thenAnswer(inv -> {
                    int current = inFlight.incrementAndGet();
                    maxInFlight.accumulateAndGet(current, Math::max);
                    int started = executions.incrementAndGet();
                    try {
                        if (started <= totalAgentWorkers) {
                            firstWaveStarted.countDown();
                            if (!releaseFirstWave.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("first worker wave was not released");
                            }
                        } else {
                            Thread.sleep(10);
                        }
                        return realisticTypedResponse(
                            true,
                            AgentExecutionTask.TYPE_AGENT,
                            "horizontal",
                            "deepseek",
                            "deepseek-chat",
                            "tenant-horizontal",
                            "horizontal-" + started,
                            100,
                            null);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });

            long startedAtNanos = System.nanoTime();
            boolean firstWaveReached;
            boolean drained;
            firstInstance.start();
            secondInstance.start();
            try {
                firstWaveReached = firstWaveStarted.await(10, TimeUnit.SECONDS);
                releaseFirstWave.countDown();
                drained = publishedLatch.await(5, TimeUnit.SECONDS);
            } finally {
                firstInstance.shutdown();
                secondInstance.shutdown();
            }
            long drainMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);

            assertThat(firstWaveReached)
                .as("both instances should fill their agent worker pools from the shared Redis queue")
                .isTrue();
            assertThat(drained).as("shared agent queue should publish all results").isTrue();
            assertThat(agentQueue).isEmpty();
            assertThat(publishedResults).hasSize(taskCount);
            assertThat(executions.get()).isEqualTo(taskCount);
            assertThat(maxInFlight.get()).isEqualTo(totalAgentWorkers);
            assertThat(drainMs).isLessThan(5_000L);

            System.out.printf(
                "AGENT_HORIZONTAL_SCALING_METRICS instances=%d tasks=%d published=%d executed=%d drainMs=%d maxConcurrent=%d workersPerInstance=%d totalAgentWorkers=%d%n",
                instances, taskCount, publishedResults.size(), executions.get(), drainMs,
                maxInFlight.get(), workersPerInstance, totalAgentWorkers);
        }

        @Test
        @DisplayName("Horizontal scaling: shared Redis rate-limit windows make WAIT mode throttle instead of fail-fast across instances")
        void twoInstancesRespectSharedWaitModeRateLimitsAcrossQueueDrain() throws Exception {
            int instances = 2;
            int workersPerInstance = 4;
            int taskCount = 12;
            RateLimitWindowFactory sharedWindows = acceleratedSharedWindowFactory(Duration.ofMillis(120));
            List<ProviderRateLimiter> instanceLimiters = List.of(
                productionLikeRateLimiter(RateLimitMode.WAIT, sharedWindows, 5),
                productionLikeRateLimiter(RateLimitMode.WAIT, sharedWindows, 5));
            AtomicInteger limiterIndex = new AtomicInteger();

            AgentScalingConfig firstConfig = productionQueueConfig(workersPerInstance, 1, 1, 10);
            AgentScalingConfig secondConfig = productionQueueConfig(workersPerInstance, 1, 1, 10);
            AgentQueueWorkerService firstInstance =
                new AgentQueueWorkerService(firstConfig, executionService, objectMapper, redisTemplate,
                    activeStreamRegistry, TEST_DRAIN_TIMEOUT_SECONDS);
            AgentQueueWorkerService secondInstance =
                new AgentQueueWorkerService(secondConfig, executionService, objectMapper, redisTemplate,
                    activeStreamRegistry, TEST_DRAIN_TIMEOUT_SECONDS);

            ConcurrentMap<String, String> expectedOrgByCorrelationId = new ConcurrentHashMap<>();
            ConcurrentMap<String, String> observedOrgByCorrelationId = new ConcurrentHashMap<>();
            ConcurrentLinkedQueue<String> agentQueue = new ConcurrentLinkedQueue<>();
            List<AgentExecutionTask> tasks = new ArrayList<>();
            for (int i = 0; i < taskCount; i++) {
                AgentExecutionTask task = typedTask("wait-horizontal-" + i, AgentExecutionTask.TYPE_AGENT,
                    "workflow", "tenant-wait-horizontal", "org-wait-horizontal",
                    "google", "gemini-3-pro-preview", 1_000, i, expectedOrgByCorrelationId);
                tasks.add(task);
                agentQueue.add(objectMapper.writeValueAsString(task));
            }

            ConcurrentMap<String, String> publishedResults = new ConcurrentHashMap<>();
            CountDownLatch publishedLatch = new CountDownLatch(taskCount);
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger maxInFlight = new AtomicInteger();
            AtomicInteger executions = new AtomicInteger();
            ConcurrentLinkedQueue<String> dequeueOrder = new ConcurrentLinkedQueue<>();
            Object dequeueLock = new Object();

            when(redisTemplate.opsForList()).thenReturn(listOperations);
            doAnswer(inv -> {
                String queueKey = inv.getArgument(0);
                synchronized (dequeueLock) {
                    if ((AgentQueueWorkerService.QUEUE_PREFIX + AgentExecutionTask.TYPE_AGENT).equals(queueKey)) {
                        String nextTask = agentQueue.poll();
                        if (nextTask != null) {
                            AgentExecutionTask task = objectMapper.readValue(nextTask, AgentExecutionTask.class);
                            dequeueOrder.add(task.correlationId());
                            return nextTask;
                        }
                    }
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }).when(listOperations).rightPop(anyString(), anyLong(), any(TimeUnit.class));

            doAnswer(inv -> {
                publishedResults.put(inv.getArgument(0), inv.getArgument(1));
                publishedLatch.countDown();
                return null;
            }).when(valueOperations).set(anyString(), anyString(), eq(1L), eq(TimeUnit.HOURS));

            when(executionService.executeByType(eq(AgentExecutionTask.TYPE_AGENT), anyString()))
                .thenAnswer(inv -> {
                    int current = inFlight.incrementAndGet();
                    maxInFlight.accumulateAndGet(current, Math::max);
                    executions.incrementAndGet();
                    ProviderRateLimiter rateLimiter = instanceLimiters.get(
                        Math.floorMod(limiterIndex.getAndIncrement(), instances));
                    try {
                        return executeProductionLikeTypedResponse(
                            AgentExecutionTask.TYPE_AGENT,
                            inv.getArgument(1),
                            rateLimiter,
                            observedOrgByCorrelationId,
                            RateLimitMode.WAIT);
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });

            long startedAtNanos = System.nanoTime();
            boolean drained;
            firstInstance.start();
            secondInstance.start();
            try {
                drained = publishedLatch.await(10, TimeUnit.SECONDS);
            } finally {
                firstInstance.shutdown();
                secondInstance.shutdown();
            }
            long drainMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);

            assertThat(drained).as("WAIT-mode shared queue should drain without terminal rate-limit errors").isTrue();
            assertThat(agentQueue).isEmpty();
            assertThat(publishedResults).hasSize(taskCount);
            assertThat(executions.get()).isEqualTo(taskCount);
            assertThat(maxInFlight.get()).isBetween(2, instances * workersPerInstance);
            assertThat(dequeueOrder).containsExactlyElementsOf(tasks.stream().map(AgentExecutionTask::correlationId).toList());
            assertThat(observedOrgByCorrelationId).isEqualTo(expectedOrgByCorrelationId);

            List<JsonNode> responses = parseResponses(publishedResults);
            assertThat(responses).hasSize(taskCount);
            assertThat(responses).allMatch(response -> response.path("success").asBoolean());
            long acquired = instanceLimiters.stream()
                .mapToLong(limiter -> limiter.getEventCounters("google:gemini-3-pro-preview")[0])
                .sum();
            long delayed = instanceLimiters.stream()
                .mapToLong(limiter -> limiter.getEventCounters("google:gemini-3-pro-preview")[1])
                .sum();
            long timedOut = instanceLimiters.stream()
                .mapToLong(limiter -> limiter.getEventCounters("google:gemini-3-pro-preview")[2])
                .sum();
            long waitMs = instanceLimiters.stream()
                .mapToLong(limiter -> limiter.getEventCounters("google:gemini-3-pro-preview")[3])
                .sum();
            assertThat(acquired).isEqualTo(taskCount);
            assertThat(delayed).as("at least one request must wait behind the 5 RPM tenant cap").isGreaterThan(0);
            assertThat(timedOut).isZero();
            assertThat(waitMs).isGreaterThan(0);

            ProviderRateLimiter.UsageStats finalStats = instanceLimiters.get(0).getTenantUsageStats(
                "google", "gemini-3-pro-preview", "tenant-wait-horizontal");
            assertThat(finalStats.currentRequests()).isLessThanOrEqualTo(finalStats.requestLimit());
            assertThat(finalStats.currentTokens()).isLessThanOrEqualTo(finalStats.tokenLimit());

            System.out.printf(
                "AGENT_WAIT_QUEUE_METRICS instances=%d tasks=%d published=%d executed=%d success=%d delayed=%d timeout=%d waitMs=%d drainMs=%d maxConcurrent=%d workersPerInstance=%d%n",
                instances, taskCount, publishedResults.size(), executions.get(), responses.size(),
                delayed, timedOut, waitMs, drainMs, maxInFlight.get(), workersPerInstance);
        }
    }

    // ========== publishResult Tests ==========

    @Nested
    @DisplayName("publishResult")
    class PublishResultTests {

        @BeforeEach
        void setUpRedis() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        }

        @Test
        @DisplayName("Stores result in Redis with 1-hour TTL and notifies via pub/sub")
        void storesAndNotifies() {
            String correlationId = "test-123";
            String resultJson = "{\"data\":\"value\"}";

            workerService.publishResult(correlationId, resultJson);

            verify(valueOperations).set(
                "agent:result:test-123", resultJson, 1L, TimeUnit.HOURS);
            verify(redisTemplate).convertAndSend(
                "agent:result:channel:test-123", resultJson);
        }
    }

    // ========== publishError Tests ==========

    @Nested
    @DisplayName("publishError")
    class PublishErrorTests {

        @BeforeEach
        void setUpRedis() {
            when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        }

        @Test
        @DisplayName("Publishes well-formed error JSON")
        void publishesErrorJson() {
            workerService.publishError("err-1", "Something went wrong");

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(valueOperations).set(eq("agent:result:err-1"), captor.capture(),
                eq(1L), eq(TimeUnit.HOURS));

            String errorJson = captor.getValue();
            assertThat(errorJson).contains("\"success\":false");
            assertThat(errorJson).contains("Something went wrong");
        }

        @Test
        @DisplayName("Handles null error message")
        void handlesNullError() {
            workerService.publishError("err-null", null);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(valueOperations).set(eq("agent:result:err-null"), captor.capture(),
                eq(1L), eq(TimeUnit.HOURS));

            assertThat(captor.getValue()).contains("\"success\":false");
            assertThat(captor.getValue()).contains("unknown error");
        }
    }

    // ========== Graceful Shutdown Tests ==========

    @Nested
    @DisplayName("Graceful shutdown")
    class GracefulShutdownTests {

        @BeforeEach
        void setUpRedis() {
            lenient().when(redisTemplate.opsForList()).thenReturn(listOperations);
            // Return null (no tasks) to keep workers idle
            lenient().when(listOperations.rightPop(anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(null);
        }

        @Test
        @DisplayName("Start sets running to true")
        void startSetsRunning() {
            workerService.start();
            assertThat(workerService.isRunning()).isTrue();
            workerService.shutdown();
        }

        @Test
        @DisplayName("Shutdown sets running to false")
        void shutdownSetsRunningFalse() {
            workerService.start();
            workerService.shutdown();
            assertThat(workerService.isRunning()).isFalse();
        }

        @Test
        @DisplayName("SmartLifecycle contract: phase=Integer.MAX_VALUE (stops FIRST, while Lettuce is still alive), no auto-startup (start stays on ApplicationReadyEvent), isRunning tracks start/stop")
        void smartLifecycleContract() {
            assertThat(workerService.getPhase())
                .as("must stop BEFORE LettuceConnectionFactory and RedisMessageListenerContainer (MAX_VALUE - 100)")
                .isEqualTo(Integer.MAX_VALUE);
            assertThat(workerService.isAutoStartup())
                .as("workers must start on ApplicationReadyEvent, not in the lifecycle start cycle")
                .isFalse();
            assertThat(workerService.isRunning()).isFalse();

            workerService.start();
            assertThat(workerService.isRunning())
                .as("isRunning() must be true after start so Spring invokes stop() on context close")
                .isTrue();

            workerService.stop();
            assertThat(workerService.isRunning()).isFalse();
        }

        @Test
        @DisplayName("stop(Runnable) drains then invokes the lifecycle callback (Spring's SmartLifecycle async-stop contract)")
        void stopWithCallbackInvokesCallbackAfterDrain() {
            workerService.start();
            java.util.concurrent.atomic.AtomicBoolean callbackRan =
                new java.util.concurrent.atomic.AtomicBoolean(false);

            workerService.stop(() -> callbackRan.set(true));

            assertThat(callbackRan.get()).isTrue();
            assertThat(workerService.isRunning()).isFalse();
        }

        @Test
        @DisplayName("Workers are NOT running at construction - start happens on ApplicationReadyEvent only (regression: 12b7f6b16 @PostConstruct raced LettuceConnectionFactory SmartLifecycle, producing 16-22K STOPPED exceptions per restart in prod 2026-04 → 2026-05)")
        void workersAreNotRunningAtConstruction() {
            // workerService was just constructed in @BeforeEach - no event fired yet.
            assertThat(workerService.isRunning())
                    .as("Workers must not start in @PostConstruct: Redis factory may still be STOPPING")
                    .isFalse();
        }

        @Test
        @DisplayName("onApplicationReady() starts workers exactly once, second call is a no-op (idempotency guard so a duplicate ApplicationReadyEvent doesn't double-spawn pools)")
        void onApplicationReadyStartsWorkersIdempotently() {
            workerService.onApplicationReady();
            assertThat(workerService.isRunning()).isTrue();

            // Second call: idempotent, must not throw or re-spin pools.
            workerService.onApplicationReady();
            assertThat(workerService.isRunning()).isTrue();

            workerService.shutdown();
        }
    }

    // ========== Shutdown Drain Tests ==========

    @Nested
    @DisplayName("Shutdown drain (graceful stream interruption)")
    class ShutdownDrainTests {

        @BeforeEach
        void setUpRedis() {
            lenient().when(redisTemplate.opsForList()).thenReturn(listOperations);
            lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
            lenient().when(redisTemplate.hasKey(anyString())).thenReturn(false);
        }

        /** Builds a worker with a single agent worker and the given drain window. */
        private AgentQueueWorkerService workerWithDrainTimeout(long drainTimeoutSeconds) {
            config.getConsumer().setPollIntervalMs(10);
            return new AgentQueueWorkerService(config, executionService, objectMapper, redisTemplate,
                activeStreamRegistry, drainTimeoutSeconds);
        }

        /** Stubs the agent queue to dispense exactly one task, then stay empty. */
        private void stubSingleAgentTask() throws Exception {
            String taskJson = objectMapper.writeValueAsString(new AgentExecutionTask(
                "corr-drain", AgentExecutionTask.TYPE_AGENT, "{\"prompt\":\"drain\"}", 0, Map.of()));
            AtomicReference<String> pending = new AtomicReference<>(taskJson);
            doAnswer(inv -> {
                String queueKey = inv.getArgument(0);
                if ((AgentQueueWorkerService.QUEUE_PREFIX + AgentExecutionTask.TYPE_AGENT).equals(queueKey)) {
                    String next = pending.getAndSet(null);
                    if (next != null) {
                        return next;
                    }
                }
                Thread.sleep(5);
                return null;
            }).when(listOperations).rightPop(anyString(), anyLong(), any(TimeUnit.class));
        }

        @Test
        @DisplayName("Drain expiry via SmartLifecycle stop(): interruptAll() finalizes survivors BEFORE shutdownNow(); a later @PreDestroy is a no-op (drain runs exactly once)")
        void drainExpiryInterruptsSurvivorsThenForcesPoolDown() throws Exception {
            // 1s drain window - the stuck task deliberately outlives it.
            workerService = workerWithDrainTimeout(1L);
            stubSingleAgentTask();

            CountDownLatch executionStarted = new CountDownLatch(1);
            CountDownLatch neverReleased = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean workerInterrupted =
                new java.util.concurrent.atomic.AtomicBoolean(false);
            when(executionService.executeByType(eq(AgentExecutionTask.TYPE_AGENT), anyString()))
                .thenAnswer(inv -> {
                    executionStarted.countDown();
                    try {
                        neverReleased.await(); // simulates an LLM stream stuck past the drain window
                    } catch (InterruptedException e) {
                        workerInterrupted.set(true);
                        Thread.currentThread().interrupt();
                    }
                    return "{\"success\":true}";
                });

            // Record the worker's interrupt state AT THE MOMENT interruptAll runs:
            // it must be false (interruptAll comes strictly before shutdownNow).
            java.util.concurrent.atomic.AtomicBoolean interruptedWhenInterruptAllRan =
                new java.util.concurrent.atomic.AtomicBoolean(true);
            doAnswer(inv -> {
                interruptedWhenInterruptAllRan.set(workerInterrupted.get());
                return null;
            }).when(activeStreamRegistry).interruptAll();

            workerService.start();
            assertThat(executionStarted.await(5, TimeUnit.SECONDS))
                .as("the stuck task should start executing before shutdown").isTrue();

            // The drain now runs in SmartLifecycle.stop() (phase MAX_VALUE), not @PreDestroy.
            workerService.stop();

            // Survivors were finalized as INTERRUPTED via the registry...
            verify(activeStreamRegistry).interruptAll();
            assertThat(interruptedWhenInterruptAllRan.get())
                .as("interruptAll must run BEFORE shutdownNow interrupts the workers")
                .isFalse();
            // ...and progress logging consulted the active-stream count while draining.
            verify(activeStreamRegistry, atLeastOnce()).size();
            // shutdownNow() must then interrupt the stuck worker thread.
            long deadline = System.currentTimeMillis() + 5_000;
            while (!workerInterrupted.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertThat(workerInterrupted.get())
                .as("the stuck agent worker must be interrupted by shutdownNow after the drain expires")
                .isTrue();
            assertThat(workerService.isRunning()).isFalse();

            // @PreDestroy fallback after stop(): must be a NO-OP - the drain already ran
            // exactly once, so no second interruptAll() may reach the registry.
            workerService.shutdown();
            verify(activeStreamRegistry, times(1)).interruptAll();

            neverReleased.countDown();
        }

        @Test
        @DisplayName("In-flight task finishing within the drain window: result is published, no interruptAll, no forced shutdown")
        void taskFinishingWithinDrainWindowIsNotInterrupted() throws Exception {
            workerService = workerWithDrainTimeout(TEST_DRAIN_TIMEOUT_SECONDS);
            stubSingleAgentTask();

            CountDownLatch executionStarted = new CountDownLatch(1);
            when(executionService.executeByType(eq(AgentExecutionTask.TYPE_AGENT), anyString()))
                .thenAnswer(inv -> {
                    executionStarted.countDown();
                    Thread.sleep(300); // finishes well within the drain window
                    return "{\"success\":true}";
                });

            workerService.start();
            assertThat(executionStarted.await(5, TimeUnit.SECONDS)).isTrue();

            workerService.stop();

            // The drain waited for the task: its result reached Redis, nothing was interrupted.
            verify(valueOperations).set(
                eq("agent:result:corr-drain"), eq("{\"success\":true}"), eq(1L), eq(TimeUnit.HOURS));
            verify(activeStreamRegistry, never()).interruptAll();
        }

        @Test
        @DisplayName("@PreDestroy fallback (no prior stop()): pools still drain cleanly without interruptAll")
        void idleShutdownDoesNotInterrupt() {
            workerService = workerWithDrainTimeout(TEST_DRAIN_TIMEOUT_SECONDS);
            lenient().when(listOperations.rightPop(anyString(), anyLong(), any(TimeUnit.class)))
                .thenReturn(null);

            workerService.start();
            workerService.shutdown();

            verify(activeStreamRegistry, never()).interruptAll();
            assertThat(workerService.isRunning()).isFalse();
        }

        @Test
        @DisplayName("Drain loop explicitly refreshes stream heartbeats on every ~30s slice - the @Scheduled tick is already dead (Spring 6.1+ stops the scheduler on ContextClosedEvent, before lifecycle stop)")
        void drainLoopRefreshesHeartbeatsWhileWaiting() throws Exception {
            // 2s drain window with a task stuck past it → the loop runs ≥ 2 iterations
            // (remaining-seconds truncation makes a 1s window expire on the first check).
            workerService = workerWithDrainTimeout(2L);
            stubSingleAgentTask();

            CountDownLatch executionStarted = new CountDownLatch(1);
            CountDownLatch neverReleased = new CountDownLatch(1);
            when(executionService.executeByType(eq(AgentExecutionTask.TYPE_AGENT), anyString()))
                .thenAnswer(inv -> {
                    executionStarted.countDown();
                    try {
                        neverReleased.await(); // outlives the drain window
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "{\"success\":true}";
                });

            workerService.start();
            assertThat(executionStarted.await(5, TimeUnit.SECONDS)).isTrue();

            workerService.stop();

            // ≥ 2 refreshes: one on drain entry AND one after the expired wait slice -
            // proving the refresh repeats WHILE waiting, keeping the 120s-TTL liveness
            // keys alive so the conversation-service reconciler does not interrupt
            // streams that are still finishing on this instance.
            verify(activeStreamRegistry, atLeast(2)).refreshHeartbeats();
            neverReleased.countDown();
        }

        @Test
        @DisplayName("M3 time budget: stuck classify/guardrail tasks drain CONCURRENTLY with the agent pool - shutdown bounded by max(agent, fast) windows, never their sum")
        void fastPoolsDrainInParallelWithAgentPool() throws Exception {
            config.getConsumer().setPollIntervalMs(10);
            // agent drain 2s, fast-pool window 2s: a sequential drain (classify 2s +
            // guardrail 2s + agent 2s) would need ≥ 6s; the parallel bound is ~2s.
            workerService = new AgentQueueWorkerService(config, executionService, objectMapper,
                redisTemplate, activeStreamRegistry, 2L, 2L);
            stubSingleTaskPerQueue();

            CountDownLatch allStarted = new CountDownLatch(3);
            CountDownLatch neverReleased = new CountDownLatch(1);
            when(executionService.executeByType(anyString(), anyString()))
                .thenAnswer(inv -> {
                    allStarted.countDown();
                    try {
                        neverReleased.await(); // every pool's task outlives its window
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return "{\"success\":true}";
                });

            workerService.start();
            assertThat(allStarted.await(5, TimeUnit.SECONDS))
                .as("one stuck task per pool (agent + classify + guardrail) should be executing")
                .isTrue();

            long startedAtNanos = System.nanoTime();
            workerService.stop();
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);

            assertThat(elapsedMs)
                .as("fast pools must drain concurrently with the agent pool (sequential would be ≥ 6000ms)")
                .isLessThan(5_000L);
            verify(activeStreamRegistry).interruptAll();
            neverReleased.countDown();
        }

        /** Stubs each of the three queues to dispense exactly one task, then stay empty. */
        private void stubSingleTaskPerQueue() throws Exception {
            ConcurrentMap<String, AtomicReference<String>> pendingByQueue = new ConcurrentHashMap<>();
            for (String type : List.of(AgentExecutionTask.TYPE_AGENT,
                    AgentExecutionTask.TYPE_CLASSIFY, AgentExecutionTask.TYPE_GUARDRAIL)) {
                pendingByQueue.put(AgentQueueWorkerService.QUEUE_PREFIX + type,
                    new AtomicReference<>(objectMapper.writeValueAsString(new AgentExecutionTask(
                        "corr-" + type, type, "{\"prompt\":\"stuck\"}", 0, Map.of()))));
            }
            doAnswer(inv -> {
                AtomicReference<String> pending = pendingByQueue.get((String) inv.getArgument(0));
                if (pending != null) {
                    String next = pending.getAndSet(null);
                    if (next != null) {
                        return next;
                    }
                }
                Thread.sleep(5);
                return null;
            }).when(listOperations).rightPop(anyString(), anyLong(), any(TimeUnit.class));
        }
    }

    // ========== AgentExecutionTask Constants ==========

    @Nested
    @DisplayName("AgentExecutionTask type constants")
    class TaskTypeTests {

        @Test
        @DisplayName("Type constants have expected values")
        void typeConstants() {
            assertThat(AgentExecutionTask.TYPE_AGENT).isEqualTo("agent");
            assertThat(AgentExecutionTask.TYPE_CLASSIFY).isEqualTo("classify");
            assertThat(AgentExecutionTask.TYPE_GUARDRAIL).isEqualTo("guardrail");
        }

        @Test
        @DisplayName("Queue/result prefixes stay byte-identical to the agent-client producer + result-waiter (cross-service Redis contract)")
        void redisPrefixesAlignWithAgentClientConstants() {
            // The producer (agent-client) LPUSHes onto QUEUE_PREFIX + agentType and this
            // worker BRPOPs the same key; the worker SETs/publishes onto RESULT_KEY_PREFIX /
            // RESULT_CHANNEL_PREFIX and RedisResultWaiter (agent-client) reads them back.
            // If any of these three string literals drifts apart, enqueued tasks land on a
            // queue no worker drains and published results land on a key/channel no caller
            // reads, with NO compile error to catch it. Pin the alignment here, the one
            // module where both sides are on the classpath (agent-service depends on agent-client).
            assertThat(AgentQueueWorkerService.QUEUE_PREFIX)
                .as("worker BRPOP key prefix must equal producer LPUSH key prefix")
                .isEqualTo(AgentQueueProducer.QUEUE_PREFIX)
                .isEqualTo("agent:queue:");

            assertThat(AgentQueueWorkerService.RESULT_KEY_PREFIX)
                .as("worker result-key prefix must equal result-waiter GETDEL key prefix")
                .isEqualTo(RedisResultWaiter.RESULT_KEY_PREFIX)
                .isEqualTo("agent:result:");

            assertThat(AgentQueueWorkerService.RESULT_CHANNEL_PREFIX)
                .as("worker result-channel prefix must equal result-waiter subscribe channel prefix")
                .isEqualTo(RedisResultWaiter.RESULT_CHANNEL_PREFIX)
                .isEqualTo("agent:result:channel:");
        }
    }

    private ProviderRateLimiter modelAwareRateLimiter() {
        RateLimitConfig rateLimitConfig = new RateLimitConfig();
        rateLimitConfig.setEnabled(true);
        rateLimitConfig.setStrategy(RateLimitStrategy.PER_TENANT);
        rateLimitConfig.setDefaultMode(RateLimitMode.FAIL_FAST);
        rateLimitConfig.getProviders().put("openai",
            new RateLimitConfig.ProviderLimit(-1, -1, 100_000, 100));

        return new ProviderRateLimiter(
            rateLimitConfig,
            null,
            (provider, model) -> {
                if ("openai".equals(provider) && "slow-model".equals(model)) {
                    return new ModelRateLimit(null, null, 10_000, 2);
                }
                if ("openai".equals(provider) && "token-tight-model".equals(model)) {
                    return new ModelRateLimit(null, null, 450, 100);
                }
                if ("openai".equals(provider) && "fast-model".equals(model)) {
                    return new ModelRateLimit(null, null, 10_000, 20);
                }
                return null;
            });
    }

    private ProviderRateLimiter productionLikeRateLimiter() {
        return productionLikeRateLimiter(RateLimitMode.FAIL_FAST, null, 600);
    }

    private ProviderRateLimiter productionLikeRateLimiter(
            RateLimitMode mode,
            RateLimitWindowFactory windowFactory,
            int maxWaitTimeSeconds) {
        RateLimitConfig rateLimitConfig = new RateLimitConfig();
        rateLimitConfig.setEnabled(true);
        rateLimitConfig.setStrategy(RateLimitStrategy.PER_TENANT);
        rateLimitConfig.setDefaultMode(mode);
        rateLimitConfig.setMaxWaitTimeSeconds(maxWaitTimeSeconds);
        rateLimitConfig.getProviders().put("google",
            new RateLimitConfig.ProviderLimit(1_000_000, 20, 50_000, 5));
        rateLimitConfig.getProviders().put("mistral",
            new RateLimitConfig.ProviderLimit(500_000, 60, 25_000, 10));
        rateLimitConfig.getProviders().put("deepseek",
            new RateLimitConfig.ProviderLimit(2_000_000, 5_000, 100_000, 250));

        return new ProviderRateLimiter(
            rateLimitConfig,
            windowFactory,
            (provider, model) -> {
                if ("google".equals(provider) && "gemini-3-pro-preview".equals(model)) {
                    return new ModelRateLimit(1_000_000, 20, 50_000, 5);
                }
                if ("mistral".equals(provider) && "mistral-large-latest".equals(model)) {
                    return new ModelRateLimit(500_000, 60, 25_000, 10);
                }
                if ("deepseek".equals(provider) && "deepseek-chat".equals(model)) {
                    return new ModelRateLimit(2_000_000, 5_000, 100_000, 250);
                }
                return null;
            });
    }

    private RateLimitWindowFactory acceleratedSharedWindowFactory(Duration entryLifetime) {
        ConcurrentMap<String, RateLimitWindow> windows = new ConcurrentHashMap<>();
        ConcurrentMap<String, Object> locks = new ConcurrentHashMap<>();
        return new RateLimitWindowFactory() {
            @Override
            public RateLimitWindow create(String windowId, int windowSizeSeconds) {
                return windows.computeIfAbsent(windowId,
                    ignored -> new AcceleratedSharedRateLimitWindow(entryLifetime.toMillis()));
            }

            @Override
            public <T> T withAtomicReservationLock(String lockKey, java.util.function.Supplier<T> operation) {
                synchronized (locks.computeIfAbsent(lockKey, ignored -> new Object())) {
                    return operation.get();
                }
            }
        };
    }

    private static final class AcceleratedSharedRateLimitWindow implements RateLimitWindow {
        private static final long REAL_WINDOW_MS = 60_000L;

        private final long entryLifetimeMs;
        private final List<Entry> entries = new ArrayList<>();
        private long lastAccessTime = System.currentTimeMillis();

        private AcceleratedSharedRateLimitWindow(long entryLifetimeMs) {
            this.entryLifetimeMs = entryLifetimeMs;
        }

        @Override
        public void add(long timestamp, int value) {
            long now = System.currentTimeMillis();
            entries.add(new Entry(now - REAL_WINDOW_MS + entryLifetimeMs, value));
            lastAccessTime = now;
        }

        @Override
        public void cleanup(long cutoffTimestamp) {
            entries.removeIf(entry -> entry.timestamp < cutoffTimestamp);
            lastAccessTime = System.currentTimeMillis();
        }

        @Override
        public int getSum() {
            return entries.stream().mapToInt(Entry::value).sum();
        }

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public boolean isEmpty() {
            return entries.isEmpty();
        }

        @Override
        public long getOldestTimestamp() {
            return entries.stream()
                .mapToLong(Entry::timestamp)
                .min()
                .orElse(0L);
        }

        @Override
        public long getLastAccessTime() {
            return lastAccessTime;
        }

        private record Entry(long timestamp, int value) {}
    }

    private AgentScalingConfig productionQueueConfig(
            int agentPoolSize,
            int classifyPoolSize,
            int guardrailPoolSize,
            int pollIntervalMs) {
        AgentScalingConfig queueConfig = new AgentScalingConfig();
        queueConfig.getWorker().setAgentPoolSize(agentPoolSize);
        queueConfig.getWorker().setClassifyPoolSize(classifyPoolSize);
        queueConfig.getWorker().setGuardrailPoolSize(guardrailPoolSize);
        queueConfig.getConsumer().setPollIntervalMs(pollIntervalMs);
        return queueConfig;
    }

    private List<AgentExecutionTask> hundredMixedModelAgentTasks(
            ConcurrentMap<String, String> expectedOrgByRunId) throws Exception {
        List<AgentExecutionTask> tasks = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            tasks.add(agentTask("slow-a-" + i, "tenant-a", "org-a", "slow-model", 100, expectedOrgByRunId));
        }
        for (int i = 0; i < 25; i++) {
            tasks.add(agentTask("slow-b-" + i, "tenant-b", "org-b", "slow-model", 100, expectedOrgByRunId));
        }
        for (int i = 0; i < 10; i++) {
            tasks.add(agentTask("token-a-" + i, "tenant-a", "org-a", "token-tight-model", 100, expectedOrgByRunId));
        }
        for (int i = 0; i < 10; i++) {
            tasks.add(agentTask("token-b-" + i, "tenant-b", "org-b", "token-tight-model", 100, expectedOrgByRunId));
        }
        for (int i = 0; i < 30; i++) {
            tasks.add(agentTask("fast-c-" + i, "tenant-c", "org-c", "fast-model", 100, expectedOrgByRunId));
        }
        return tasks;
    }

    private String executeRealisticAgentResponse(
            String payload,
            ProviderRateLimiter rateLimiter,
            ConcurrentMap<String, String> observedOrgByRunId) throws Exception {
        JsonNode root = objectMapper.readTree(payload);
        String provider = root.path("provider").asText();
        String model = root.path("model").asText();
        String tenantId = root.path("tenantId").asText();
        String runId = root.path("runId").asText();
        String prompt = root.path("prompt").asText();
        int estimatedTokens = root.path("variables").path("estimatedTokens").asInt(100);

        observedOrgByRunId.put(runId, TenantResolver.currentRequestOrganizationId());

        try {
            rateLimiter.checkRateLimit(
                provider, model, tenantId, estimatedTokens, RateLimitMode.FAIL_FAST);
            return realisticAgentResponse(
                true, provider, model, tenantId, runId, prompt, estimatedTokens, null);
        } catch (LLMProviderException e) {
            return realisticAgentResponse(
                false, provider, model, tenantId, runId, prompt, 0, e.getMessage());
        }
    }

    private String executeProductionLikeTypedResponse(
            String agentType,
            String payload,
            ProviderRateLimiter rateLimiter,
            ConcurrentMap<String, String> observedOrgByCorrelationId) throws Exception {
        return executeProductionLikeTypedResponse(
            agentType, payload, rateLimiter, observedOrgByCorrelationId, RateLimitMode.FAIL_FAST);
    }

    private String executeProductionLikeTypedResponse(
            String agentType,
            String payload,
            ProviderRateLimiter rateLimiter,
            ConcurrentMap<String, String> observedOrgByCorrelationId,
            RateLimitMode mode) throws Exception {
        JsonNode root = objectMapper.readTree(payload);
        String provider = root.path("provider").asText();
        String model = root.path("model").asText();
        String tenantId = root.path("tenantId").asText();
        String correlationId = root.path("correlationId").asText();
        String origin = root.path("origin").asText();
        int estimatedTokens = root.path("variables").path("estimatedTokens").asInt(100);

        observedOrgByCorrelationId.put(correlationId, TenantResolver.currentRequestOrganizationId());

        try {
            rateLimiter.checkRateLimit(
                provider, model, tenantId, estimatedTokens, mode);
            return realisticTypedResponse(
                true, agentType, origin, provider, model, tenantId, correlationId, estimatedTokens, null);
        } catch (LLMProviderException e) {
            return realisticTypedResponse(
                false, agentType, origin, provider, model, tenantId, correlationId, 0, e.getMessage());
        }
    }

    private List<JsonNode> parseResponses(ConcurrentMap<String, String> publishedResults) throws Exception {
        List<JsonNode> responses = new ArrayList<>();
        for (String result : publishedResults.values()) {
            responses.add(objectMapper.readTree(result));
        }
        return responses;
    }

    private void assertHundredMixedModelOutcomes(
            List<AgentExecutionTask> tasks,
            List<JsonNode> responses,
            ProviderRateLimiter rateLimiter) {
        assertThat(tasks).hasSize(100);
        assertThat(responses).hasSize(100);
        assertThat(countResponses(responses, "tenant-a", "slow-model", true)).isEqualTo(2);
        assertThat(countResponses(responses, "tenant-a", "slow-model", false)).isEqualTo(23);
        assertThat(countResponses(responses, "tenant-b", "slow-model", true)).isEqualTo(2);
        assertThat(countResponses(responses, "tenant-b", "slow-model", false)).isEqualTo(23);
        assertThat(countResponses(responses, "tenant-a", "token-tight-model", true)).isEqualTo(4);
        assertThat(countResponses(responses, "tenant-a", "token-tight-model", false)).isEqualTo(6);
        assertThat(countResponses(responses, "tenant-b", "token-tight-model", true)).isEqualTo(4);
        assertThat(countResponses(responses, "tenant-b", "token-tight-model", false)).isEqualTo(6);
        assertThat(countResponses(responses, "tenant-c", "fast-model", true)).isEqualTo(20);
        assertThat(countResponses(responses, "tenant-c", "fast-model", false)).isEqualTo(10);

        responses.stream()
            .filter(response -> !response.path("success").asBoolean())
            .forEach(response -> {
                assertThat(response.path("stopReason").asText()).isEqualTo("RATE_LIMITED");
                assertThat(response.path("error").asText()).containsIgnoringCase("limit");
            });

        assertTenantLimitStats(rateLimiter, "slow-model", "tenant-a", 200, 10_000, 2, 2);
        assertTenantLimitStats(rateLimiter, "slow-model", "tenant-b", 200, 10_000, 2, 2);
        assertTenantLimitStats(rateLimiter, "token-tight-model", "tenant-a", 400, 450, 4, 100);
        assertTenantLimitStats(rateLimiter, "token-tight-model", "tenant-b", 400, 450, 4, 100);
        assertTenantLimitStats(rateLimiter, "fast-model", "tenant-c", 2_000, 10_000, 20, 20);
    }

    private void assertProductionConfigQueueOutcomes(
            List<JsonNode> responses,
            ProviderRateLimiter rateLimiter) {
        assertThat(responses).hasSize(26);
        assertThat(responses).extracting(response -> response.path("origin").asText())
            .contains(
                "workflow",
                "chat_general",
                "widget",
                "webhook",
                "schedule",
                "task",
                "task_review",
                "classify",
                "guardrail");

        assertThat(countTypedResponses(
            responses, AgentExecutionTask.TYPE_AGENT, "tenant-agent-prod", "gemini-3-pro-preview", true))
            .isEqualTo(5);
        assertThat(countTypedResponses(
            responses, AgentExecutionTask.TYPE_AGENT, "tenant-agent-prod", "gemini-3-pro-preview", false))
            .isEqualTo(1);
        assertThat(countTypedResponses(
            responses, AgentExecutionTask.TYPE_AGENT, "tenant-task-prod", "deepseek-chat", true))
            .isEqualTo(2);
        assertThat(countTypedResponses(
            responses, AgentExecutionTask.TYPE_CLASSIFY, "tenant-classify-prod", "mistral-large-latest", true))
            .isEqualTo(10);
        assertThat(countTypedResponses(
            responses, AgentExecutionTask.TYPE_CLASSIFY, "tenant-classify-prod", "mistral-large-latest", false))
            .isEqualTo(2);
        assertThat(countTypedResponses(
            responses, AgentExecutionTask.TYPE_GUARDRAIL, "tenant-guardrail-prod", "gemini-3-pro-preview", true))
            .isEqualTo(5);
        assertThat(countTypedResponses(
            responses, AgentExecutionTask.TYPE_GUARDRAIL, "tenant-guardrail-prod", "gemini-3-pro-preview", false))
            .isEqualTo(1);

        responses.stream()
            .filter(response -> !response.path("success").asBoolean())
            .forEach(response -> {
                assertThat(response.path("stopReason").asText()).isEqualTo("RATE_LIMITED");
                assertThat(response.path("error").asText()).containsIgnoringCase("limit");
            });

        assertTenantLimitStats(rateLimiter, "google", "gemini-3-pro-preview",
            "tenant-agent-prod", 5_000, 50_000, 5, 5);
        assertTenantLimitStats(rateLimiter, "deepseek", "deepseek-chat",
            "tenant-task-prod", 2_000, 100_000, 2, 250);
        assertTenantLimitStats(rateLimiter, "mistral", "mistral-large-latest",
            "tenant-classify-prod", 10_000, 25_000, 10, 10);
        assertTenantLimitStats(rateLimiter, "google", "gemini-3-pro-preview",
            "tenant-guardrail-prod", 2_500, 50_000, 5, 5);
    }

    private void assertTenantLimitStats(
            ProviderRateLimiter rateLimiter,
            String model,
            String tenantId,
            int expectedTokens,
            int expectedTokenLimit,
            int expectedRequests,
            int expectedRequestLimit) {
        assertTenantLimitStats(rateLimiter, "openai", model, tenantId, expectedTokens, expectedTokenLimit,
            expectedRequests, expectedRequestLimit);
    }

    private void assertTenantLimitStats(
            ProviderRateLimiter rateLimiter,
            String provider,
            String model,
            String tenantId,
            int expectedTokens,
            int expectedTokenLimit,
            int expectedRequests,
            int expectedRequestLimit) {
        ProviderRateLimiter.UsageStats stats = rateLimiter.getTenantUsageStats(provider, model, tenantId);
        assertThat(stats.currentTokens()).isEqualTo(expectedTokens);
        assertThat(stats.tokenLimit()).isEqualTo(expectedTokenLimit);
        assertThat(stats.currentRequests()).isEqualTo(expectedRequests);
        assertThat(stats.requestLimit()).isEqualTo(expectedRequestLimit);
        assertThat(stats.currentTokens()).isLessThanOrEqualTo(stats.tokenLimit());
        assertThat(stats.currentRequests()).isLessThanOrEqualTo(stats.requestLimit());
    }

    private AgentExecutionTask agentTask(
            String runId,
            String tenantId,
            String organizationId,
            String model,
            int estimatedTokens,
            ConcurrentMap<String, String> expectedOrgByRunId) throws Exception {
        expectedOrgByRunId.put(runId, organizationId);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("prompt", "Summarize invoice batch for " + runId);
        request.put("systemPrompt", "You are a billing operations assistant. Return concise JSON-safe text.");
        request.put("provider", "openai");
        request.put("model", model);
        request.put("temperature", 0.2);
        request.put("maxTokens", 512);
        request.put("tenantId", tenantId);
        request.put("organizationId", organizationId);
        request.put("runId", runId);
        request.put("nodeId", "agent:billing_assistant");
        request.put("streamingFormat", "workflow");
        request.put("variables", Map.of("estimatedTokens", estimatedTokens));
        request.put("credentials", Map.of("__orgId__", organizationId));
        request.put("tools", List.of(
            Map.of(
                "id", "tool-search-invoices",
                "name", "search_invoices",
                "description", "Find invoices by customer or status",
                "parameters", Map.of("status", "overdue"))));

        return new AgentExecutionTask(
            "corr-" + runId,
            AgentExecutionTask.TYPE_AGENT,
            objectMapper.writeValueAsString(request),
            0,
            Map.of("runId", runId, "nodeId", "agent:billing_assistant", "tenantId", tenantId));
    }

    private AgentExecutionTask typedTask(
            String correlationId,
            String agentType,
            String origin,
            String tenantId,
            String organizationId,
            String provider,
            String model,
            int estimatedTokens,
            int sequence,
            ConcurrentMap<String, String> expectedOrgByCorrelationId) throws Exception {
        expectedOrgByCorrelationId.put(correlationId, organizationId);
        boolean conversationOrigin = isConversationQueueOrigin(origin);
        String runId = conversationOrigin ? "" : "run-" + correlationId;
        String nodeId = conversationOrigin ? "" : "agent:" + origin;

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("correlationId", correlationId);
        request.put("agentType", agentType);
        request.put("origin", origin);
        request.put("prompt", "Execute production-like " + origin + " request #" + sequence);
        request.put("provider", provider);
        request.put("model", model);
        request.put("tenantId", tenantId);
        request.put("organizationId", organizationId);
        request.put("runId", runId);
        request.put("nodeId", nodeId);
        request.put("streamingFormat", conversationOrigin ? "conversation" : "workflow");
        request.put("variables", Map.of("estimatedTokens", estimatedTokens, "sequence", sequence));
        Map<String, Object> credentials = new LinkedHashMap<>();
        credentials.put("__orgId__", organizationId);
        if ("task".equals(origin) || "task_review".equals(origin)) {
            request.put("taskId", "task-prod-" + sequence);
            credentials.put("__taskId__", "task-prod-" + sequence);
        }
        if (conversationOrigin) {
            request.put("conversationId", "conv-prod-" + origin);
        }
        request.put("source", sourceForOrigin(origin));
        request.put("credentials", credentials);

        return new AgentExecutionTask(
            correlationId,
            agentType,
            objectMapper.writeValueAsString(request),
            0,
            Map.of("runId", runId, "nodeId", nodeId, "tenantId", tenantId));
    }

    private AgentExecutionTask horizontalAgentTask(int sequence) throws Exception {
        String correlationId = "horizontal-" + sequence;
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("correlationId", correlationId);
        request.put("agentType", AgentExecutionTask.TYPE_AGENT);
        request.put("origin", "horizontal");
        request.put("prompt", "Horizontal scaling task " + sequence);
        request.put("provider", "deepseek");
        request.put("model", "deepseek-chat");
        request.put("tenantId", "tenant-horizontal");
        request.put("organizationId", "org-horizontal");
        request.put("runId", "");
        request.put("nodeId", "");
        request.put("streamingFormat", "conversation");
        request.put("conversationId", "conv-horizontal");
        request.put("variables", Map.of("estimatedTokens", 100, "sequence", sequence));
        request.put("credentials", Map.of("__orgId__", "org-horizontal"));

        return new AgentExecutionTask(
            correlationId,
            AgentExecutionTask.TYPE_AGENT,
            objectMapper.writeValueAsString(request),
            0,
            Map.of("runId", "", "nodeId", "", "tenantId", "tenant-horizontal"));
    }

    private boolean isConversationQueueOrigin(String origin) {
        return "chat_general".equals(origin)
            || "widget".equals(origin)
            || "webhook".equals(origin)
            || "schedule".equals(origin)
            || "task".equals(origin)
            || "task_review".equals(origin);
    }

    private String sourceForOrigin(String origin) {
        return switch (origin) {
            case "chat_general" -> "CHAT";
            case "widget" -> "WIDGET";
            case "webhook" -> "WEBHOOK";
            case "schedule" -> "SCHEDULE";
            case "task" -> "TASK";
            case "task_review" -> "TASK_REVIEW";
            default -> "WORKFLOW";
        };
    }

    private ConcurrentLinkedQueue<String> encodeTasks(List<AgentExecutionTask> tasks) throws Exception {
        ConcurrentLinkedQueue<String> encoded = new ConcurrentLinkedQueue<>();
        for (AgentExecutionTask task : tasks) {
            encoded.add(objectMapper.writeValueAsString(task));
        }
        return encoded;
    }

    private String realisticAgentResponse(
            boolean success,
            String provider,
            String model,
            String tenantId,
            String runId,
            String prompt,
            int inputTokens,
            String error) throws Exception {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", success);
        response.put("finalResponse", success
            ? "Processed " + runId + " with " + model + ": " + prompt
            : null);
        response.put("content", success
            ? "Processed " + runId + " with " + model + ": " + prompt
            : null);
        response.put("toolResults", List.of());
        response.put("iterations", success ? 1 : 0);
        response.put("totalUsage", success
            ? Map.of("inputTokens", inputTokens, "outputTokens", 42, "totalTokens", inputTokens + 42)
            : Map.of("inputTokens", 0, "outputTokens", 0, "totalTokens", 0));
        response.put("error", error);
        response.put("durationMs", success ? 180L : 0L);
        response.put("provider", provider);
        response.put("model", model);
        response.put("conversationHistory", List.of(
            Map.of("role", "user", "content", prompt),
            Map.of("role", "assistant", "content", success ? "Processed " + runId : "")));
        response.put("stopReason", success ? "COMPLETED" : "RATE_LIMITED");
        response.put("metrics", Map.of("tenantId", tenantId, "runId", runId));
        response.put("usagePerIteration", success ? List.of(Map.of("totalTokens", inputTokens + 42)) : List.of());
        response.put("iterationDurations", success ? List.of(180L) : List.of());
        response.put("finishReasonsPerIteration", success ? List.of("stop") : List.of());
        response.put("thinkingSections", List.of());
        response.put("orderedEntries", List.of());
        return objectMapper.writeValueAsString(response);
    }

    private String realisticTypedResponse(
            boolean success,
            String agentType,
            String origin,
            String provider,
            String model,
            String tenantId,
            String correlationId,
            int inputTokens,
            String error) throws Exception {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", success);
        response.put("agentType", agentType);
        response.put("origin", origin);
        response.put("provider", provider);
        response.put("model", model);
        response.put("content", success ? "Processed " + origin + " request " + correlationId : null);
        response.put("finalResponse", success ? "Processed " + origin + " request " + correlationId : null);
        response.put("selectedCategory",
            success && AgentExecutionTask.TYPE_CLASSIFY.equals(agentType) ? "support" : null);
        response.put("passed",
            success && AgentExecutionTask.TYPE_GUARDRAIL.equals(agentType) ? Boolean.TRUE : null);
        response.put("error", error);
        response.put("stopReason", success ? "COMPLETED" : "RATE_LIMITED");
        response.put("totalUsage", success
            ? Map.of("inputTokens", inputTokens, "outputTokens", 32, "totalTokens", inputTokens + 32)
            : Map.of("inputTokens", 0, "outputTokens", 0, "totalTokens", 0));
        response.put("metrics", Map.of(
            "tenantId", tenantId,
            "correlationId", correlationId,
            "origin", origin));
        return objectMapper.writeValueAsString(response);
    }

    private long countResponses(List<JsonNode> responses, String tenantId, String model, boolean success) {
        return responses.stream()
            .filter(response -> response.path("success").asBoolean() == success)
            .filter(response -> model.equals(response.path("model").asText()))
            .filter(response -> tenantId.equals(response.path("metrics").path("tenantId").asText()))
            .count();
    }

    private long countTypedResponses(
            List<JsonNode> responses,
            String agentType,
            String tenantId,
            String model,
            boolean success) {
        return responses.stream()
            .filter(response -> response.path("success").asBoolean() == success)
            .filter(response -> agentType.equals(response.path("agentType").asText()))
            .filter(response -> model.equals(response.path("model").asText()))
            .filter(response -> tenantId.equals(response.path("metrics").path("tenantId").asText()))
            .count();
    }

    private int executionCount(ConcurrentMap<String, AtomicInteger> executionsByType, String agentType) {
        AtomicInteger counter = executionsByType.get(agentType);
        return counter == null ? 0 : counter.get();
    }

    private int maxConcurrent(ConcurrentMap<String, AtomicInteger> maxInFlightByType, String agentType) {
        AtomicInteger counter = maxInFlightByType.get(agentType);
        return counter == null ? 0 : counter.get();
    }
}
