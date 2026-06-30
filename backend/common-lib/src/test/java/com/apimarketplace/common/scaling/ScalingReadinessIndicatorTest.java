package com.apimarketplace.common.scaling;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScalingReadinessIndicator Tests")
class ScalingReadinessIndicatorTest {

    @Mock
    private RedisConnectionFactory connectionFactory;

    @Mock
    private RedisConnection redisConnection;

    private ScalingReadinessIndicator indicator;

    @BeforeEach
    void setUp() {
        indicator = new ScalingReadinessIndicator(connectionFactory);
    }

    @Nested
    @DisplayName("doHealthCheck()")
    class DoHealthCheckTests {

        @Test
        @DisplayName("should report UP when Redis responds to PING")
        void shouldReportUpWhenRedisAvailable() {
            when(connectionFactory.getConnection()).thenReturn(redisConnection);
            when(redisConnection.ping()).thenReturn("PONG");

            Health.Builder builder = new Health.Builder();
            indicator.doHealthCheck(builder);
            Health health = builder.build();

            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("status", "connected");
            assertThat(health.getDetails()).containsEntry("response", "PONG");
        }

        @Test
        @DisplayName("should report DOWN when Redis connection fails")
        void shouldReportDownWhenRedisUnavailable() {
            when(connectionFactory.getConnection())
                    .thenThrow(new RuntimeException("Connection refused"));

            Health.Builder builder = new Health.Builder();
            indicator.doHealthCheck(builder);
            Health health = builder.build();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("status", "disconnected");
            assertThat(health.getDetails()).containsEntry("error", "Connection refused");
        }

        @Test
        @DisplayName("should report DOWN when PING throws exception")
        void shouldReportDownWhenPingFails() {
            when(connectionFactory.getConnection()).thenReturn(redisConnection);
            when(redisConnection.ping()).thenThrow(new RuntimeException("Redis timeout"));

            Health.Builder builder = new Health.Builder();
            indicator.doHealthCheck(builder);
            Health health = builder.build();

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("status", "disconnected");
            assertThat(health.getDetails()).containsEntry("error", "Redis timeout");
        }
    }
}
