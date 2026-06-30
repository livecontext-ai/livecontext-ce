package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.ModelPricing;
import com.apimarketplace.auth.service.ModelPricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@DisplayName("InternalPricingController")
@ExtendWith(MockitoExtension.class)
class InternalPricingControllerTest {

    @Mock
    private ModelPricingService pricingService;

    private InternalPricingController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalPricingController(pricingService);
        lenient().when(pricingService.applyCloudLlmBillingMultiplier(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
    }

    private ModelPricing pricing(String provider, String model, String input, String output, String fixed) {
        ModelPricing p = new ModelPricing();
        p.setProvider(provider);
        p.setModel(model);
        p.setInputRate(new BigDecimal(input));
        p.setOutputRate(new BigDecimal(output));
        p.setFixedCost(new BigDecimal(fixed));
        return p;
    }

    @Test
    @DisplayName("returns version + rates list with the expected fields")
    @SuppressWarnings("unchecked")
    void snapshotShape() {
        when(pricingService.getAllActivePricing()).thenReturn(List.of(
            pricing("openai", "gpt-4o", "0.005", "0.015", "0"),
            pricing("anthropic", "claude-opus-4-6", "0.015", "0.075", "0")
        ));

        ResponseEntity<Map<String, Object>> response = controller.snapshot();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKey("version");
        assertThat(body).containsKey("rates");

        List<Map<String, Object>> rates = (List<Map<String, Object>>) body.get("rates");
        assertThat(rates).hasSize(2);
        assertThat(rates.get(0))
            .containsEntry("provider", "openai")
            .containsEntry("model", "gpt-4o")
            .containsEntry("inputRate", new BigDecimal("0.005"))
            .containsEntry("outputRate", new BigDecimal("0.015"));
    }

    @Test
    @DisplayName("returns empty rates list when no pricing rows are active")
    @SuppressWarnings("unchecked")
    void emptySnapshot() {
        when(pricingService.getAllActivePricing()).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> response = controller.snapshot();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> rates = (List<Map<String, Object>>) response.getBody().get("rates");
        assertThat(rates).isEmpty();
    }

    @Test
    @DisplayName("coerces null fixedCost to BigDecimal.ZERO")
    @SuppressWarnings("unchecked")
    void nullFixedCostBecomesZero() {
        ModelPricing p = pricing("openai", "gpt-4o", "0.001", "0.002", "0");
        p.setFixedCost(null);
        when(pricingService.getAllActivePricing()).thenReturn(List.of(p));

        ResponseEntity<Map<String, Object>> response = controller.snapshot();

        List<Map<String, Object>> rates = (List<Map<String, Object>>) response.getBody().get("rates");
        assertThat(rates.get(0).get("fixedCost")).isEqualTo(BigDecimal.ZERO);
    }
}
