package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.ModelPricing;
import com.apimarketplace.auth.repository.ModelPricingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The receiving half of the free-tier mirror (V493).
 *
 * <p>agent-service owns {@code free_tier_enabled} and pushes it here; this service
 * stores it in {@code auth.model_pricing.free_tier} and answers the gate. The sending
 * half is covered by {@code ModelCatalogServiceSyncPricingTest} - the two halves are
 * a contract, so both ends are pinned rather than one.
 *
 * <p>Two guarantees matter more than the happy path. The flag FAILS CLOSED for
 * anything the mirror has not heard of, so a catalog edit that has not propagated
 * withholds a benefit instead of handing out grant-funded inference. And an absent
 * flag on a sync PRESERVES the stored value, so a caller that does not track it
 * cannot silently close a model an admin opened.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ModelPricingService - the free-tier mirror")
class ModelPricingServiceFreeTierMirrorTest {

    @Mock
    private ModelPricingRepository pricingRepository;

    private ModelPricingService service;

    @BeforeEach
    void setUp() {
        service = new ModelPricingService(pricingRepository, new BigDecimal("1.11"));
    }

    private ModelPricing row(String provider, String model, Boolean freeTier) {
        ModelPricing p = new ModelPricing();
        p.setProvider(provider);
        p.setModel(model);
        p.setInputRate(BigDecimal.ONE);
        p.setOutputRate(BigDecimal.ONE);
        p.setFixedCost(BigDecimal.ZERO);
        p.setFreeTier(freeTier);
        return p;
    }

    @Nested
    @DisplayName("reading the flag")
    class Reading {

        @Test
        @DisplayName("a mirrored open model answers true")
        void openModelIsFreeTier() {
            when(pricingRepository.findCurrentPricing("anthropic", "claude-haiku-4-5"))
                    .thenReturn(Optional.of(row("anthropic", "claude-haiku-4-5", true)));

            assertThat(service.isFreeTierModel("anthropic", "claude-haiku-4-5")).isTrue();
        }

        @Test
        @DisplayName("a mirrored closed model answers false")
        void closedModelIsNotFreeTier() {
            when(pricingRepository.findCurrentPricing("anthropic", "claude-opus-4-6"))
                    .thenReturn(Optional.of(row("anthropic", "claude-opus-4-6", false)));

            assertThat(service.isFreeTierModel("anthropic", "claude-opus-4-6")).isFalse();
        }

        @Test
        @DisplayName("a model absent from the mirror answers false - the synthetic default row fails closed")
        void unknownModelFailsClosed() {
            when(pricingRepository.findCurrentPricing("openai", "ghost")).thenReturn(Optional.empty());

            assertThat(service.isFreeTierModel("openai", "ghost"))
                    .as("a catalog edit that has not propagated must not hand out grant-funded inference")
                    .isFalse();
        }

        @Test
        @DisplayName("a legacy row with a NULL flag answers false rather than throwing")
        void nullFlagIsNotFreeTier() {
            when(pricingRepository.findCurrentPricing("openai", "legacy"))
                    .thenReturn(Optional.of(row("openai", "legacy", null)));

            assertThat(service.isFreeTierModel("openai", "legacy")).isFalse();
        }

        @Test
        @DisplayName("null provider or model answers false without touching the repository")
        void nullsFailClosed() {
            assertThat(service.isFreeTierModel(null, "m")).isFalse();
            assertThat(service.isFreeTierModel("p", null)).isFalse();
            assertThat(service.isFreeTierModel(null, null)).isFalse();
        }
    }

    @Nested
    @DisplayName("writing the flag")
    class Writing {

        @Test
        @DisplayName("an explicit TRUE opens the model")
        void explicitTrueIsStored() {
            when(pricingRepository.findCurrentPricing("anthropic", "haiku"))
                    .thenReturn(Optional.of(row("anthropic", "haiku", false)));

            service.upsertPricing("anthropic", "haiku", BigDecimal.ONE, BigDecimal.ONE, "byok", null, null, true);

            ArgumentCaptor<ModelPricing> saved = ArgumentCaptor.forClass(ModelPricing.class);
            verify(pricingRepository).save(saved.capture());
            assertThat(saved.getValue().getFreeTier()).isTrue();
        }

        @Test
        @DisplayName("an explicit FALSE closes it")
        void explicitFalseIsStored() {
            when(pricingRepository.findCurrentPricing("anthropic", "haiku"))
                    .thenReturn(Optional.of(row("anthropic", "haiku", true)));

            service.upsertPricing("anthropic", "haiku", BigDecimal.ONE, BigDecimal.ONE, "byok", null, null, false);

            ArgumentCaptor<ModelPricing> saved = ArgumentCaptor.forClass(ModelPricing.class);
            verify(pricingRepository).save(saved.capture());
            assertThat(saved.getValue().getFreeTier()).isFalse();
        }

        @Test
        @DisplayName("an ABSENT flag preserves the stored value - a legacy sync cannot silently close a model")
        void absentFlagPreservesStoredValue() {
            when(pricingRepository.findCurrentPricing("anthropic", "haiku"))
                    .thenReturn(Optional.of(row("anthropic", "haiku", true)));

            // The 5-arg form is what an older agent-service (or any caller that does not
            // track the flag) uses; it must leave the admin's choice alone.
            service.upsertPricing("anthropic", "haiku", BigDecimal.ONE, BigDecimal.ONE, "byok");

            ArgumentCaptor<ModelPricing> saved = ArgumentCaptor.forClass(ModelPricing.class);
            verify(pricingRepository).save(saved.capture());
            assertThat(saved.getValue().getFreeTier())
                    .as("the receiving half of the never-silently-close guarantee")
                    .isTrue();
        }

        @Test
        @DisplayName("a brand-new row defaults to closed")
        void newRowDefaultsToClosed() {
            when(pricingRepository.findCurrentPricing("openai", "brand-new")).thenReturn(Optional.empty());

            service.upsertPricing("openai", "brand-new", BigDecimal.ONE, BigDecimal.ONE, "byok");

            ArgumentCaptor<ModelPricing> saved = ArgumentCaptor.forClass(ModelPricing.class);
            verify(pricingRepository).save(saved.capture());
            assertThat(saved.getValue().getFreeTier()).isFalse();
        }
    }

    @Nested
    @DisplayName("the cached flag goes stale, but not forever")
    class Caching {

        private final Instant t0 = Instant.parse("2026-09-14T12:00:00Z");

        @Test
        @DisplayName("a model opened on ANOTHER replica is answered correctly once the entry expires")
        void staleClosedFlagExpires() {
            service.setClock(Clock.fixed(t0, ZoneOffset.UTC));
            when(pricingRepository.findCurrentPricing("anthropic", "haiku"))
                    .thenReturn(Optional.of(row("anthropic", "haiku", false)));
            assertThat(service.isFreeTierModel("anthropic", "haiku")).isFalse();

            // The admin opens it. The write lands on a different replica, so nothing
            // here is invalidated: this replica keeps refusing a model that is open.
            when(pricingRepository.findCurrentPricing("anthropic", "haiku"))
                    .thenReturn(Optional.of(row("anthropic", "haiku", true)));
            assertThat(service.isFreeTierModel("anthropic", "haiku"))
                    .as("within the TTL the stale answer stands - that is the bound, stated")
                    .isFalse();

            service.setClock(Clock.fixed(t0.plusSeconds(61), ZoneOffset.UTC));
            assertThat(service.isFreeTierModel("anthropic", "haiku"))
                    .as("a minute later it must agree with the database, with no write on this replica")
                    .isTrue();
        }

        @Test
        @DisplayName("a model CLOSED on another replica stops being free once the entry expires")
        void staleOpenFlagExpires() {
            // The direction that costs money: a model left open here keeps spending the
            // free allowance after the admin closed it.
            service.setClock(Clock.fixed(t0, ZoneOffset.UTC));
            when(pricingRepository.findCurrentPricing("anthropic", "haiku"))
                    .thenReturn(Optional.of(row("anthropic", "haiku", true)));
            assertThat(service.isFreeTierModel("anthropic", "haiku")).isTrue();

            when(pricingRepository.findCurrentPricing("anthropic", "haiku"))
                    .thenReturn(Optional.of(row("anthropic", "haiku", false)));

            service.setClock(Clock.fixed(t0.plusSeconds(61), ZoneOffset.UTC));
            assertThat(service.isFreeTierModel("anthropic", "haiku")).isFalse();
        }

        @Test
        @DisplayName("within the TTL the row is read once, not on every gate check")
        void freshEntriesAreServedFromMemory() {
            // The reason the cache exists at all: this runs on the pre-flight path of
            // every turn. Dropping the cache to fix staleness would be a hot-path read.
            service.setClock(Clock.fixed(t0, ZoneOffset.UTC));
            when(pricingRepository.findCurrentPricing("anthropic", "haiku"))
                    .thenReturn(Optional.of(row("anthropic", "haiku", true)));

            for (int i = 0; i < 5; i++) {
                assertThat(service.isFreeTierModel("anthropic", "haiku")).isTrue();
            }

            verify(pricingRepository, times(1)).findCurrentPricing("anthropic", "haiku");
        }

        @Test
        @DisplayName("the replica that took the write sees it immediately, without waiting out the TTL")
        void ownWriteIsVisibleAtOnce() {
            service.setClock(Clock.fixed(t0, ZoneOffset.UTC));
            when(pricingRepository.findCurrentPricing("anthropic", "haiku"))
                    .thenReturn(Optional.of(row("anthropic", "haiku", false)));
            assertThat(service.isFreeTierModel("anthropic", "haiku")).isFalse();

            ModelPricing opened = row("anthropic", "haiku", false);
            when(pricingRepository.findCurrentPricing("anthropic", "haiku")).thenReturn(Optional.of(opened));
            service.upsertPricing("anthropic", "haiku", BigDecimal.ONE, BigDecimal.ONE, "byok", null, null, true);

            assertThat(service.isFreeTierModel("anthropic", "haiku"))
                    .as("the admin who just flipped the switch must not be told it is still closed")
                    .isTrue();
        }
    }
}
