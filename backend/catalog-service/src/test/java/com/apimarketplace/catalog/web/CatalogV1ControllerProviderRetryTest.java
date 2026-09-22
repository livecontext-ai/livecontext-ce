package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.service.CatalogV1Service;
import com.apimarketplace.catalog.service.http.ProviderRetryContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The PRIMARY door: the one line that joins the node's retry budget to the execution that will use
 * it, on every cloud call and every non-relay call.
 *
 * <p><b>Why this needs its own test.</b> The budget rides the request as a plain field and is read
 * by a thread-local the execution consults far below. Between the two sits a single
 * {@code ProviderRetryContext.begin(...)} in the controller. Delete it and the field is accepted,
 * parsed, and never applied: every workflow that asked the platform not to re-send underneath it
 * gets re-sent underneath anyway, the run is green, and every other test on both sides still passes
 * because each one exercises its own half. The CE relay door had exactly this test; this is the door
 * almost every call actually takes.
 *
 * <p>The {@code clear()} matters as much. The re-send COUNT does not self-heal the way the budget
 * does, so one left on a pooled request thread is reported on the next request through it, for a
 * different tenant, as a re-send that never happened.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogV1Controller - the node's provider-retry budget reaches the execution")
class CatalogV1ControllerProviderRetryTest {

    private static final String TOOL = "slack/slack-send-message";

    @Mock private CatalogV1Service catalogV1Service;
    @Mock private com.apimarketplace.catalog.service.execution.MockToolExecutionService mockToolExecutionService;

    private MockMvc mockMvc;

    /** What the thread-local held while the execution was running. */
    private final AtomicReference<Long> budgetDuringExecution = new AtomicReference<>();
    private final AtomicInteger countDuringExecution = new AtomicInteger(-1);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CatalogV1Controller(catalogV1Service, mockToolExecutionService))
                .build();
    }

    /** Captures what the thread-local held at the moment the execution ran. */
    private void executionCapturesTheContext() {
        when(catalogV1Service.executeTool(anyString(), any(), anyString(), any(), anyString()))
                .thenAnswer(invocation -> {
                    budgetDuringExecution.set(ProviderRetryContext.getMaxWaitMs());
                    countDuringExecution.set(ProviderRetryContext.getRetries());
                    return ToolExecutionResponse.builder().success(true).toolId(TOOL).build();
                });
    }

    @AfterEach
    void clearContext() {
        ProviderRetryContext.clear();
    }

    private void execute(String body) throws Exception {
        mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", TOOL)
                        .header("X-User-ID", "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a zero budget on the request is bound to the thread for the execution")
    void zeroBudgetReachesTheExecution() throws Exception {
        executionCapturesTheContext();
        // The case the feature exists for: the node paces itself, so the platform must not re-send
        // underneath it. If this line goes missing the request still succeeds and the setting is
        // simply never applied.
        execute("{\"parameters\":{\"text\":\"hi\"},\"providerRetryMaxWaitSeconds\":0}");

        assertThat(budgetDuringExecution.get()).isEqualTo(0L);
    }

    @Test
    @DisplayName("a positive budget arrives in milliseconds, converted once")
    void positiveBudgetReachesTheExecution() throws Exception {
        executionCapturesTheContext();
        execute("{\"parameters\":{\"text\":\"hi\"},\"providerRetryMaxWaitSeconds\":45}");

        assertThat(budgetDuringExecution.get()).isEqualTo(45_000L);
    }

    @Test
    @DisplayName("a request that says nothing leaves the platform's own budget in place")
    void anAbsentBudgetIsNotInvented() throws Exception {
        executionCapturesTheContext();
        // Absent and 0 are different instructions, and this is the one that must not become the
        // other: reading silence as 0 would disable the platform retry for every existing workflow.
        execute("{\"parameters\":{\"text\":\"hi\"}}");

        assertThat(budgetDuringExecution.get()).isNull();
    }

    @Test
    @DisplayName("the count is reset on the way IN, so a pooled thread cannot carry one forward")
    void theCountIsResetOnEntry() throws Exception {
        executionCapturesTheContext();
        // Simulates a previous request on this thread that was re-sent once. Without begin()'s
        // reset, this request would report that re-send as its own, for a different tenant.
        ProviderRetryContext.recordRetry();

        execute("{\"parameters\":{\"text\":\"hi\"}}");

        assertThat(countDuringExecution.get()).isZero();
    }

    @Test
    @DisplayName("nothing is left on the thread when the request returns")
    void theContextIsClearedOnTheWayOut() throws Exception {
        executionCapturesTheContext();
        execute("{\"parameters\":{\"text\":\"hi\"},\"providerRetryMaxWaitSeconds\":0}");

        assertThat(ProviderRetryContext.getMaxWaitMs()).isNull();
        assertThat(ProviderRetryContext.getRetries()).isZero();
    }

    @Test
    @DisplayName("and nothing is left on the thread when the execution THROWS")
    void theContextIsClearedAfterAFailure() throws Exception {
        // The finally is what makes the leak impossible, so the failing path is the one worth
        // pinning: a budget of 0 left behind would silence the platform retry for whatever request
        // reuses this thread.
        when(catalogV1Service.executeTool(anyString(), any(), anyString(), any(), anyString()))
                .thenThrow(new RuntimeException("upstream exploded"));

        try {
            mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", TOOL)
                    .header("X-User-ID", "42")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"parameters\":{\"text\":\"hi\"},\"providerRetryMaxWaitSeconds\":0}"));
        } catch (Exception ignored) {
            // However the controller reports it, the thread must come back clean.
        }

        assertThat(ProviderRetryContext.getMaxWaitMs()).isNull();
        assertThat(ProviderRetryContext.getRetries()).isZero();
    }
}
