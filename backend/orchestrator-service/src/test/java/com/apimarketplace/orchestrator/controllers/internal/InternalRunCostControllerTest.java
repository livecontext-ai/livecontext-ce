package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.services.credit.RunCostService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalRunCostController - parse body + delegate, best-effort")
class InternalRunCostControllerTest {

    @Mock
    private RunCostService runCostService;

    @Test
    @DisplayName("parses organizationId/epoch/credits and delegates")
    void parsesAndDelegates() {
        InternalRunCostController controller = new InternalRunCostController(runCostService);
        Map<String, Object> body = new HashMap<>();
        body.put("organizationId", "org-9");
        body.put("epoch", 3);
        body.put("credits", 0.75);

        ResponseEntity<Map<String, String>> resp = controller.recordCost("run-42", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<BigDecimal> credits = ArgumentCaptor.forClass(BigDecimal.class);
        verify(runCostService).recordAgentCost(org.mockito.ArgumentMatchers.eq("run-42"),
                org.mockito.ArgumentMatchers.eq("org-9"),
                org.mockito.ArgumentMatchers.eq(3),
                credits.capture());
        assertThat(credits.getValue()).isEqualByComparingTo("0.75");
    }

    @Test
    @DisplayName("blank organizationId parses to null (personal scope)")
    void blankOrgIsNull() {
        InternalRunCostController controller = new InternalRunCostController(runCostService);
        Map<String, Object> body = new HashMap<>();
        body.put("organizationId", "");
        body.put("epoch", 0);
        body.put("credits", 1);

        controller.recordCost("run-1", body);

        ArgumentCaptor<BigDecimal> credits = ArgumentCaptor.forClass(BigDecimal.class);
        verify(runCostService).recordAgentCost(org.mockito.ArgumentMatchers.eq("run-1"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq(0),
                credits.capture());
        assertThat(credits.getValue()).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("a service failure never fails the caller (returns 200)")
    void serviceFailureStill200() {
        InternalRunCostController controller = new InternalRunCostController(runCostService);
        doThrow(new RuntimeException("boom"))
                .when(runCostService).recordAgentCost(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.any());
        Map<String, Object> body = new HashMap<>();
        body.put("epoch", 1);
        body.put("credits", 1);

        ResponseEntity<Map<String, String>> resp = controller.recordCost("run-1", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("status", "recorded");
    }
}
