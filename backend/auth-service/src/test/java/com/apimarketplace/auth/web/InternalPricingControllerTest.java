package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.ModelPricing;
import com.apimarketplace.auth.repository.ModelPricingRepository;
import com.apimarketplace.auth.service.LlmTokenBreakdown;
import com.apimarketplace.auth.service.ModelPricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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
        lenient().when(pricingService.cacheRates(any(), any()))
                .thenAnswer(invocation -> {
                    ModelPricing p = invocation.getArgument(1);
                    return new ModelPricingService.CacheRates(p.getInputRate(), p.getInputRate());
                });
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

    /**
     * The cache prices the budget guards read (2026-09-30). Without them the guards priced
     * every cache read at the input rate and killed a free account's Claude Code turn at
     * about five times its real debit. These run the REAL resolver, so they prove the
     * published figure is the one the ledger charges, not a restatement of it.
     */
    @Nested
    @DisplayName("cache rates published for the budget guards")
    class PublishedCacheRates {

        private final ModelPricingRepository repository = org.mockito.Mockito.mock(ModelPricingRepository.class);
        private final ModelPricingService realService = new ModelPricingService(repository, new BigDecimal("1.5"));
        private final InternalPricingController realController = new InternalPricingController(realService);

        private ModelPricing row(String provider, String model, String input, String output,
                                 String cacheRead, String cacheWrite) {
            ModelPricing p = pricing(provider, model, input, output, "0");
            p.setCacheReadRate(cacheRead != null ? new BigDecimal(cacheRead) : null);
            p.setCacheWriteRate(cacheWrite != null ? new BigDecimal(cacheWrite) : null);
            return p;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> publishedRow(ModelPricing p) {
            when(repository.findByIsActiveTrue()).thenReturn(List.of(p));
            List<Map<String, Object>> rates =
                (List<Map<String, Object>>) realController.snapshot().getBody().get("rates");
            return rates.get(0);
        }

        @Test
        @DisplayName("a guard pricing the killed Claude Code turn with the published rates lands on the ledger debit")
        void publishedRatesReproduceTheLedgerDebit() {
            ModelPricing sonnet = row("claude-code", "claude-sonnet-5-5", "2.0", "10.0", "0.2", "2.5");
            when(repository.findCurrentPricing("claude-code", "claude-sonnet-5-5")).thenReturn(Optional.of(sonnet));
            Map<String, Object> published = publishedRow(sonnet);

            // The guard's formula over the published rates: 8 plain + 18,971 writes + 182,073 reads.
            BigDecimal guardCost = ((BigDecimal) published.get("inputRate")).multiply(BigDecimal.valueOf(8))
                .add(((BigDecimal) published.get("cacheWriteRate")).multiply(BigDecimal.valueOf(18_971)))
                .add(((BigDecimal) published.get("cacheReadRate")).multiply(BigDecimal.valueOf(182_073)))
                .add(((BigDecimal) published.get("outputRate")).multiply(BigDecimal.valueOf(47)))
                .divide(new BigDecimal("1000"), 6, java.math.RoundingMode.HALF_UP);
            BigDecimal ledger = realService.calculateCost("claude-code", "claude-sonnet-5-5",
                new LlmTokenBreakdown(201_052, 47, 18_971, 182_073, 0, 0));

            assertThat(guardCost).isCloseTo(ledger, within(new BigDecimal("0.0001")));
        }

        @Test
        @DisplayName("cache rates carry the cloud multiplier, like every other published rate")
        void cacheRatesCarryTheMultiplier() {
            Map<String, Object> published = publishedRow(row("anthropic", "claude-sonnet-5-5", "2.0", "10.0", "0.2", "2.5"));

            assertThat((BigDecimal) published.get("cacheReadRate")).isEqualByComparingTo("0.3");
            assertThat((BigDecimal) published.get("cacheWriteRate")).isEqualByComparingTo("3.75");
        }

        @Test
        @DisplayName("an Anthropic row without its own cache prices publishes the 0.1x read / 1.25x write fallback")
        void anthropicFallbackWeights() {
            Map<String, Object> published = publishedRow(row("anthropic", "claude-x", "2.0", "10.0", null, null));

            assertThat((BigDecimal) published.get("cacheReadRate")).isEqualByComparingTo("0.3");   // 2.0 x 0.1 x 1.5
            assertThat((BigDecimal) published.get("cacheWriteRate")).isEqualByComparingTo("3.75"); // 2.0 x 1.25 x 1.5
        }

        @Test
        @DisplayName("an OpenAI-family row never publishes its cache-WRITE price: the ledger never charges one")
        void subsetFamilyWriteRateIsTheInputRate() {
            Map<String, Object> published = publishedRow(row("codex", "gpt-6-luna", "0.1", "0.5", "0.01", "0.125"));

            assertThat((BigDecimal) published.get("cacheReadRate")).isEqualByComparingTo("0.015");
            assertThat((BigDecimal) published.get("cacheWriteRate"))
                .isEqualByComparingTo((BigDecimal) published.get("inputRate"));
        }
    }
}
