package com.apimarketplace.auth.config;

import com.stripe.StripeClient;
import com.stripe.exception.AuthenticationException;
import com.stripe.model.Balance;
import com.stripe.model.Balance.Available;
import com.stripe.service.BalanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StripeHealthIndicator Tests")
class StripeHealthIndicatorTest {

    @Mock
    private StripeClient stripeClient;

    @Mock
    private BalanceService balanceService;

    private StripeHealthIndicator healthIndicator;

    @BeforeEach
    void setUp() {
        healthIndicator = new StripeHealthIndicator(stripeClient);
    }

    @Nested
    @DisplayName("doHealthCheck()")
    class DoHealthCheckTests {

        @Test
        @DisplayName("should report UP when Stripe connection is healthy")
        void shouldReportUpWhenHealthy() throws Exception {
            Balance balance = mock(Balance.class);
            when(balance.getLivemode()).thenReturn(false);
            Available available = mock(Available.class);
            when(balance.getAvailable()).thenReturn(List.of(available));
            when(stripeClient.balance()).thenReturn(balanceService);
            when(balanceService.retrieve()).thenReturn(balance);

            Health.Builder builder = new Health.Builder();
            healthIndicator.doHealthCheck(builder);
            Health health = builder.build();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("status", "connected");
            assertThat(health.getDetails()).containsEntry("livemode", false);
            assertThat(health.getDetails()).containsEntry("availableCount", 1);
        }

        @Test
        @DisplayName("should report DOWN when Stripe throws AuthenticationException")
        void shouldReportDownOnStripeException() throws Exception {
            when(stripeClient.balance()).thenReturn(balanceService);
            AuthenticationException authEx = new AuthenticationException("Invalid API key", "req_123", "auth_error", 401);
            when(balanceService.retrieve()).thenThrow(authEx);

            Health.Builder builder = new Health.Builder();
            healthIndicator.doHealthCheck(builder);
            Health health = builder.build();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("status", "disconnected");
        }

        @Test
        @DisplayName("should report DOWN on generic exception")
        void shouldReportDownOnGenericException() throws Exception {
            when(stripeClient.balance()).thenThrow(new RuntimeException("Connection failed"));

            Health.Builder builder = new Health.Builder();
            healthIndicator.doHealthCheck(builder);
            Health health = builder.build();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("status", "error");
            assertThat(health.getDetails()).containsEntry("error", "Connection failed");
        }

        @Test
        @DisplayName("should handle null available balance list")
        void shouldHandleNullAvailableBalanceList() throws Exception {
            Balance balance = mock(Balance.class);
            when(balance.getLivemode()).thenReturn(true);
            when(balance.getAvailable()).thenReturn(null);
            when(stripeClient.balance()).thenReturn(balanceService);
            when(balanceService.retrieve()).thenReturn(balance);

            Health.Builder builder = new Health.Builder();
            healthIndicator.doHealthCheck(builder);
            Health health = builder.build();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("availableCount", 0);
        }
    }
}
