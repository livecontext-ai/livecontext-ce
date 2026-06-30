package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Price;
import com.apimarketplace.auth.repository.PriceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PriceCacheService Tests")
class PriceCacheServiceTest {

    @Mock
    private PriceRepository priceRepository;

    @InjectMocks
    private PriceCacheService priceCacheService;

    private Plan proPlan;
    private Price monthlyPrice;
    private Price yearlyPrice;

    @BeforeEach
    void setUp() {
        proPlan = new Plan("PRO", "Pro Plan", "Pro plan");
        proPlan.setId(2L);

        monthlyPrice = new Price(proPlan, "monthly", 2900);
        monthlyPrice.setProviderPriceId("price_pro_monthly");

        yearlyPrice = new Price(proPlan, "yearly", 24900);
        yearlyPrice.setProviderPriceId("price_pro_yearly");
    }

    @Nested
    @DisplayName("initializeCache()")
    class InitializeCacheTests {

        @Test
        @DisplayName("should load prices from database")
        void shouldLoadPricesFromDatabase() {
            when(priceRepository.findAllWithPlans()).thenReturn(Arrays.asList(monthlyPrice, yearlyPrice));

            priceCacheService.initializeCache();

            assertThat(priceCacheService.getPriceId("PRO", "monthly")).isPresent();
            assertThat(priceCacheService.getPriceId("PRO", "yearly")).isPresent();
        }

        @Test
        @DisplayName("should skip prices with null providerPriceId")
        void shouldSkipPricesWithNullProviderPriceId() {
            Price freePrice = new Price(proPlan, "monthly", 0);
            freePrice.setProviderPriceId(null);
            when(priceRepository.findAllWithPlans()).thenReturn(Collections.singletonList(freePrice));

            priceCacheService.initializeCache();

            assertThat(priceCacheService.getAllCachedPrices()).isEmpty();
        }

        @Test
        @DisplayName("should skip prices with blank providerPriceId")
        void shouldSkipPricesWithBlankProviderPriceId() {
            Price blankPrice = new Price(proPlan, "monthly", 0);
            blankPrice.setProviderPriceId("   ");
            when(priceRepository.findAllWithPlans()).thenReturn(Collections.singletonList(blankPrice));

            priceCacheService.initializeCache();

            assertThat(priceCacheService.getAllCachedPrices()).isEmpty();
        }

        @Test
        @DisplayName("should handle database errors gracefully")
        void shouldHandleDatabaseErrorsGracefully() {
            when(priceRepository.findAllWithPlans()).thenThrow(new RuntimeException("DB error"));

            // Should not throw
            priceCacheService.initializeCache();

            assertThat(priceCacheService.getAllCachedPrices()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getPriceId(String planCode, String cadence)")
    class GetPriceIdWithCadenceTests {

        @Test
        @DisplayName("should return price ID from cache")
        void shouldReturnPriceIdFromCache() {
            when(priceRepository.findAllWithPlans()).thenReturn(Collections.singletonList(monthlyPrice));
            priceCacheService.initializeCache();

            Optional<String> result = priceCacheService.getPriceId("PRO", "monthly");

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo("price_pro_monthly");
        }

        @Test
        @DisplayName("should return empty when not in cache")
        void shouldReturnEmptyWhenNotInCache() {
            when(priceRepository.findAllWithPlans()).thenReturn(Collections.emptyList());
            priceCacheService.initializeCache();

            Optional<String> result = priceCacheService.getPriceId("NONEXISTENT", "monthly");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getPriceId(String planCode)")
    class GetPriceIdDefaultCadenceTests {

        @Test
        @DisplayName("should default to monthly cadence")
        void shouldDefaultToMonthlyCadence() {
            when(priceRepository.findAllWithPlans()).thenReturn(Collections.singletonList(monthlyPrice));
            priceCacheService.initializeCache();

            Optional<String> result = priceCacheService.getPriceId("PRO");

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo("price_pro_monthly");
        }
    }

    @Nested
    @DisplayName("getBillingCycleByPriceId()")
    class GetBillingCycleByPriceIdTests {

        @Test
        @DisplayName("should return cadence for known price ID")
        void shouldReturnCadenceForKnownPriceId() {
            when(priceRepository.findAllWithPlans()).thenReturn(Arrays.asList(monthlyPrice, yearlyPrice));
            priceCacheService.initializeCache();

            Optional<String> result = priceCacheService.getBillingCycleByPriceId("price_pro_monthly");

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo("monthly");
        }

        @Test
        @DisplayName("should return yearly cadence for yearly price ID")
        void shouldReturnYearlyCadence() {
            when(priceRepository.findAllWithPlans()).thenReturn(Arrays.asList(monthlyPrice, yearlyPrice));
            priceCacheService.initializeCache();

            Optional<String> result = priceCacheService.getBillingCycleByPriceId("price_pro_yearly");

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo("yearly");
        }

        @Test
        @DisplayName("should return empty for null price ID")
        void shouldReturnEmptyForNullPriceId() {
            Optional<String> result = priceCacheService.getBillingCycleByPriceId(null);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return empty for unknown price ID")
        void shouldReturnEmptyForUnknownPriceId() {
            when(priceRepository.findAllWithPlans()).thenReturn(Collections.emptyList());
            priceCacheService.initializeCache();

            Optional<String> result = priceCacheService.getBillingCycleByPriceId("price_unknown");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getAllCachedPrices()")
    class GetAllCachedPricesTests {

        @Test
        @DisplayName("should return copy of all cached prices")
        void shouldReturnCopyOfAllCachedPrices() {
            when(priceRepository.findAllWithPlans()).thenReturn(Arrays.asList(monthlyPrice, yearlyPrice));
            priceCacheService.initializeCache();

            Map<String, String> prices = priceCacheService.getAllCachedPrices();

            assertThat(prices).hasSize(2);
            assertThat(prices).containsEntry("PRO_monthly", "price_pro_monthly");
            assertThat(prices).containsEntry("PRO_yearly", "price_pro_yearly");
        }
    }

    @Nested
    @DisplayName("forceRefreshCache()")
    class ForceRefreshCacheTests {

        @Test
        @DisplayName("should reload from database")
        void shouldReloadFromDatabase() {
            when(priceRepository.findAllWithPlans()).thenReturn(Collections.emptyList());
            priceCacheService.initializeCache();

            assertThat(priceCacheService.getAllCachedPrices()).isEmpty();

            when(priceRepository.findAllWithPlans()).thenReturn(Collections.singletonList(monthlyPrice));
            priceCacheService.forceRefreshCache();

            assertThat(priceCacheService.getAllCachedPrices()).hasSize(1);
        }
    }
}
