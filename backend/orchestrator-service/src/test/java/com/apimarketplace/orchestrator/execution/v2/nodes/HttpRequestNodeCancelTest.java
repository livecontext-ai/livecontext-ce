package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.services.streaming.redis.WorkflowRedisPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * F2.3 - verifies HttpRequestNode releases the orchestrator thread within
 * ~200ms of a STOP signal instead of waiting for the full HTTP timeout.
 *
 * <p>Pre-fix, {@code restTemplate.exchange} was called synchronously and the
 * orchestrator worker stayed parked until the HTTP response or read-timeout
 * (default ~5min on the shared RestTemplate). A user STOP arriving mid-call
 * had zero effect until the call returned.
 *
 * <p>Post-fix: the call runs on a separate future and we poll
 * {@code workflowRedisPublisher.isAgentCancelSignalSet} every 200ms; if set,
 * {@code future.cancel(true)} releases the wait and the node returns a
 * cancelled-style failure.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpRequestNode - F2.3 cancel signal aborts in-flight call")
class HttpRequestNodeCancelTest {

    @Mock private WorkflowPlan mockPlan;
    @Mock private RestTemplate mockRestTemplate;
    @Mock private WorkflowRedisPublisher publisher;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create("run-cancel", "wr-1", "tenant-1", "item-0", 0, Map.of(), mockPlan);
    }

    private void wirePublisher(HttpRequestNode node) throws Exception {
        // The node's publisher is normally injected via acceptServices(ServiceRegistry).
        // For unit tests we set it directly via reflection to avoid building a full registry.
        Field f = HttpRequestNode.class.getDeclaredField("workflowRedisPublisher");
        f.setAccessible(true);
        f.set(node, publisher);
    }

    @Test
    @DisplayName("Cancel signal flips during call - node returns cancelled failure within ~500ms")
    void cancelSignalDuringCallReleasesThread() throws Exception {
        HttpRequestNode node = HttpRequestNode.builder()
            .nodeId("core:http").urlExpression("http://example.com/slow").method("GET").build();
        node.setRestTemplate(mockRestTemplate);
        wirePublisher(node);

        // Mock RestTemplate.exchange to block 10 seconds (simulating slow remote)
        CountDownLatch started = new CountDownLatch(1);
        when(mockRestTemplate.exchange(anyString(), any(), any(), eq(String.class)))
            .thenAnswer(inv -> {
                started.countDown();
                Thread.sleep(10_000);
                return new ResponseEntity<>("never-arrives", HttpStatus.OK);
            });

        // Cancel polling: return false a few times, then true
        AtomicBoolean cancelled = new AtomicBoolean(false);
        when(publisher.isAgentCancelSignalSet("run-cancel"))
            .thenAnswer(inv -> cancelled.get());

        // Flip cancel after the request has actually started
        Thread flipper = new Thread(() -> {
            try {
                started.await(2, TimeUnit.SECONDS);
                Thread.sleep(150);
                cancelled.set(true);
            } catch (InterruptedException ignored) {}
        });
        flipper.start();

        long start = System.currentTimeMillis();
        NodeExecutionResult result = node.execute(context);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.isSuccess())
            .as("cancelled call must NOT report success")
            .isFalse();
        assertThat(result.errorMessage().orElse(""))
            .as("error message must indicate cancellation")
            .containsIgnoringCase("cancelled");
        assertThat(elapsed)
            .as("STOP must release the thread within ~one poll cycle (200ms) plus jitter")
            .isLessThan(2000);
    }

    @Test
    @DisplayName("No publisher wired - falls back to direct future.get() (regression: existing tests)")
    void nullPublisherFallsBack() throws Exception {
        HttpRequestNode node = HttpRequestNode.builder()
            .nodeId("core:http").urlExpression("http://example.com/").method("GET").build();
        node.setRestTemplate(mockRestTemplate);
        // No wirePublisher - publisher stays null

        when(mockRestTemplate.exchange(anyString(), any(), any(), eq(String.class)))
            .thenReturn(new ResponseEntity<>("{\"ok\":1}", HttpStatus.OK));

        NodeExecutionResult result = node.execute(context);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("Cancel never signaled - call completes normally even with publisher wired")
    void noCancelDuringCallCompletesNormally() throws Exception {
        HttpRequestNode node = HttpRequestNode.builder()
            .nodeId("core:http").urlExpression("http://example.com/").method("GET").build();
        node.setRestTemplate(mockRestTemplate);
        wirePublisher(node);

        // lenient: the fast path may complete before the first poll fires
        lenient().when(publisher.isAgentCancelSignalSet(anyString())).thenReturn(false);
        when(mockRestTemplate.exchange(anyString(), any(), any(), eq(String.class)))
            .thenReturn(new ResponseEntity<>("{\"ok\":1}", HttpStatus.OK));

        NodeExecutionResult result = node.execute(context);
        assertThat(result.isSuccess()).isTrue();
    }
}
