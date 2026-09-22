package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.CreditService;
import com.apimarketplace.auth.service.ModelPricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The pre-flight gate must answer about the source type the caller will DEBIT (V494).
 *
 * <p>This endpoint used to hardcode {@code CHAT_CONVERSATION}. That was harmless while
 * every source type resolved to the same buckets, and stopped being harmless the moment
 * the AI allowance funded some LLM sources and not others: a CE relay turn debits
 * {@code CE_LLM_RELAY}, which the pot deliberately does not pay for, so gating it as a
 * chat turn counted money the debit could never draw. The tokens then ran and the whole
 * cost landed on an empty PAYG bucket - repeatedly, because the pot never decremented,
 * so the gate kept saying yes.
 *
 * <p>Gate/debit divergence is invisible to every test that exercises one side alone,
 * which is how it got here. This pins the seam itself.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("POST /api/credits/check-chat - the gate asks about the DEBIT's source type")
class CreditControllerChatBudgetSourceTypeTest {

    private static final Long USER_ID = 42L;

    @Mock private CreditService creditService;
    @Mock private ModelPricingService pricingService;

    @Mock private com.apimarketplace.auth.service.LlmCostEstimateService estimateService;

    private CreditController controller;

    @BeforeEach
    void setUp() {
        controller = new CreditController(creditService, pricingService, estimateService);

        when(pricingService.calculateCost(anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(new BigDecimal("2.00"));
        when(pricingService.hasPricing(anyString(), anyString())).thenReturn(true);
        when(creditService.getBalance(anyLong())).thenReturn(new BigDecimal("0.00"));
        when(creditService.canAfford(anyLong(), any(), anyString(), anyString(), anyString()))
                .thenReturn(true);
    }

    private ResponseEntity<Map<String, Object>> check(String sourceType) {
        return controller.checkChatBudget(USER_ID, new CreditController.ChatBudgetRequest(
                "anthropic", "claude-haiku-4-5", 1000, 500, sourceType));
    }

    @Test
    @DisplayName("a CE relay pre-flight is gated as CE_LLM_RELAY, which the allowance does not fund")
    void relaySourceTypeIsForwarded() {
        check("CE_LLM_RELAY");

        verify(creditService).canAfford(USER_ID, new BigDecimal("2.00"), "CE_LLM_RELAY",
                "anthropic", "claude-haiku-4-5");
    }

    @Test
    @DisplayName("an agent pre-flight is gated as AGENT_EXECUTION, not as a chat turn")
    void agentSourceTypeIsForwarded() {
        check("AGENT_EXECUTION");

        verify(creditService).canAfford(USER_ID, new BigDecimal("2.00"), "AGENT_EXECUTION",
                "anthropic", "claude-haiku-4-5");
    }

    @Test
    @DisplayName("an absent source type keeps the pre-V494 meaning, so old callers are unchanged")
    void absentSourceTypeDefaultsToChat() {
        check(null);

        verify(creditService).canAfford(USER_ID, new BigDecimal("2.00"), "CHAT_CONVERSATION",
                "anthropic", "claude-haiku-4-5");
    }

    @Test
    @DisplayName("a blank source type is treated as absent rather than passed through")
    void blankSourceTypeDefaultsToChat() {
        // A blank string is not a source type; forwarding it would scope the gate
        // against something CreditService does not recognise, silently widening it.
        check("   ");

        verify(creditService).canAfford(USER_ID, new BigDecimal("2.00"), "CHAT_CONVERSATION",
                "anthropic", "claude-haiku-4-5");
    }

    @Test
    @DisplayName("the verdict still reaches the caller unchanged")
    void verdictIsReturned() {
        when(creditService.canAfford(anyLong(), any(), anyString(), anyString(), anyString()))
                .thenReturn(false);

        ResponseEntity<Map<String, Object>> response = check("CE_LLM_RELAY");

        assertThat(response.getStatusCode().value()).isEqualTo(402);
        assertThat(response.getBody()).containsEntry("allowed", false);
    }
}
