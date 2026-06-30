package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Price;
import com.apimarketplace.auth.repository.PlanRepository;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlanCacheService Tests")
class PlanCacheServiceTest {

    @Mock
    private PlanRepository planRepository;

    @Mock
    private PriceRepository priceRepository;

    @InjectMocks
    private PlanCacheService planCacheService;

    private Plan freePlan;
    private Plan proPlan;
    private Price monthlyPrice;

    @BeforeEach
    void setUp() {
        freePlan = new Plan("FREE", "Free Plan", "Free plan description");
        freePlan.setId(1L);

        proPlan = new Plan("PRO", "Pro Plan", "Pro plan description");
        proPlan.setId(2L);

        monthlyPrice = new Price(proPlan, "monthly", 2900);
        monthlyPrice.setProviderPriceId("price_pro_monthly");
    }

    @Nested
    @DisplayName("initializeCache()")
    class InitializeCacheTests {

        @Test
        @DisplayName("should load plans and prices from database")
        void shouldLoadPlansAndPricesFromDatabase() {
            when(planRepository.findAll()).thenReturn(Arrays.asList(freePlan, proPlan));
            when(priceRepository.findAllWithPlans()).thenReturn(Collections.singletonList(monthlyPrice));

            planCacheService.initializeCache();

            assertThat(planCacheService.getPlan("FREE")).isPresent();
            assertThat(planCacheService.getPlan("PRO")).isPresent();
            assertThat(planCacheService.getPrice("PRO", "monthly")).isPresent();
        }

        @Test
        @DisplayName("should handle database errors gracefully")
        void shouldHandleDatabaseErrorsGracefully() {
            when(planRepository.findAll()).thenThrow(new RuntimeException("DB error"));

            // Should not throw
            planCacheService.initializeCache();

            assertThat(planCacheService.getAllPlans()).isEmpty();
        }
    }

    @Nested
    @DisplayName("getPlan()")
    class GetPlanTests {

        @Test
        @DisplayName("should return plan from cache")
        void shouldReturnPlanFromCache() {
            when(planRepository.findAll()).thenReturn(Collections.singletonList(freePlan));
            when(priceRepository.findAllWithPlans()).thenReturn(Collections.emptyList());
            planCacheService.initializeCache();

            Optional<Plan> result = planCacheService.getPlan("FREE");

            assertThat(result).isPresent();
            assertThat(result.get().getName()).isEqualTo("Free Plan");
        }

        @Test
        @DisplayName("should return empty when plan not in cache")
        void shouldReturnEmptyWhenPlanNotInCache() {
            when(planRepository.findAll()).thenReturn(Collections.emptyList());
            when(priceRepository.findAllWithPlans()).thenReturn(Collections.emptyList());
            planCacheService.initializeCache();

            Optional<Plan> result = planCacheService.getPlan("NONEXISTENT");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getPrice()")
    class GetPriceTests {

        @Test
        @DisplayName("should return price from cache")
        void shouldReturnPriceFromCache() {
            when(planRepository.findAll()).thenReturn(Collections.singletonList(proPlan));
            when(priceRepository.findAllWithPlans()).thenReturn(Collections.singletonList(monthlyPrice));
            planCacheService.initializeCache();

            Optional<Price> result = planCacheService.getPrice("PRO", "monthly");

            assertThat(result).isPresent();
            assertThat(result.get().getProviderPriceId()).isEqualTo("price_pro_monthly");
        }

        @Test
        @DisplayName("should return empty when price not in cache")
        void shouldReturnEmptyWhenPriceNotInCache() {
            when(planRepository.findAll()).thenReturn(Collections.emptyList());
            when(priceRepository.findAllWithPlans()).thenReturn(Collections.emptyList());
            planCacheService.initializeCache();

            Optional<Price> result = planCacheService.getPrice("PRO", "yearly");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getAllPlans()")
    class GetAllPlansTests {

        @Test
        @DisplayName("should return copy of all plans")
        void shouldReturnCopyOfAllPlans() {
            when(planRepository.findAll()).thenReturn(Arrays.asList(freePlan, proPlan));
            when(priceRepository.findAllWithPlans()).thenReturn(Collections.emptyList());
            planCacheService.initializeCache();

            Map<String, Plan> plans = planCacheService.getAllPlans();

            assertThat(plans).hasSize(2);
            assertThat(plans).containsKeys("FREE", "PRO");
        }
    }

    @Nested
    @DisplayName("getAllPrices()")
    class GetAllPricesTests {

        @Test
        @DisplayName("should return copy of all prices")
        void shouldReturnCopyOfAllPrices() {
            when(planRepository.findAll()).thenReturn(Collections.singletonList(proPlan));
            when(priceRepository.findAllWithPlans()).thenReturn(Collections.singletonList(monthlyPrice));
            planCacheService.initializeCache();

            Map<String, Price> prices = planCacheService.getAllPrices();

            assertThat(prices).hasSize(1);
            assertThat(prices).containsKey("PRO_monthly");
        }
    }

    @Nested
    @DisplayName("getPlanOrder()")
    class GetPlanOrderTests {

        @Test
        @DisplayName("should return plan order when found")
        void shouldReturnPlanOrderWhenFound() {
            when(planRepository.findAll()).thenReturn(Collections.singletonList(proPlan));
            when(priceRepository.findAllWithPlans()).thenReturn(Collections.emptyList());
            planCacheService.initializeCache();

            Optional<Long> result = planCacheService.getPlanOrder("PRO");

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(2L);
        }

        @Test
        @DisplayName("should return empty when plan not found")
        void shouldReturnEmptyWhenPlanNotFound() {
            when(planRepository.findAll()).thenReturn(Collections.emptyList());
            when(priceRepository.findAllWithPlans()).thenReturn(Collections.emptyList());
            planCacheService.initializeCache();

            Optional<Long> result = planCacheService.getPlanOrder("NONEXISTENT");

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("forceRefreshCache()")
    class ForceRefreshCacheTests {

        @Test
        @DisplayName("should reload from database")
        void shouldReloadFromDatabase() {
            when(planRepository.findAll()).thenReturn(Collections.emptyList());
            when(priceRepository.findAllWithPlans()).thenReturn(Collections.emptyList());
            planCacheService.initializeCache();

            assertThat(planCacheService.getAllPlans()).isEmpty();

            when(planRepository.findAll()).thenReturn(Collections.singletonList(freePlan));
            planCacheService.forceRefreshCache();

            assertThat(planCacheService.getAllPlans()).hasSize(1);
        }
    }
}
