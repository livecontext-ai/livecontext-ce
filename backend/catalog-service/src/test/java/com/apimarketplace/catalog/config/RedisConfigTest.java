package com.apimarketplace.catalog.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RedisConfig")
class RedisConfigTest {

    @Nested
    @DisplayName("default TTL values")
    class DefaultTtlTests {

        @Test
        @DisplayName("should have default response cache TTL of 5 minutes")
        void hasDefaultResponseCacheTtl() {
            RedisConfig config = new RedisConfig();

            assertThat(config.getResponseCacheTtl()).isEqualTo(Duration.ofMinutes(5));
        }

        @Test
        @DisplayName("should have default tool schema TTL of 60 minutes")
        void hasDefaultToolSchemaTtl() {
            RedisConfig config = new RedisConfig();

            assertThat(config.getToolSchemaTtl()).isEqualTo(Duration.ofMinutes(60));
        }

        @Test
        @DisplayName("should have default MCP server TTL of 5 minutes")
        void hasDefaultMcpServerTtl() {
            RedisConfig config = new RedisConfig();

            assertThat(config.getMcpServerTtl()).isEqualTo(Duration.ofMinutes(5));
        }
    }

    @Nested
    @DisplayName("setters")
    class SetterTests {

        @Test
        @DisplayName("should allow setting response cache TTL")
        void allowsSettingResponseCacheTtl() {
            RedisConfig config = new RedisConfig();
            config.setResponseCacheTtl(Duration.ofMinutes(10));

            assertThat(config.getResponseCacheTtl()).isEqualTo(Duration.ofMinutes(10));
        }

        @Test
        @DisplayName("should allow setting tool schema TTL")
        void allowsSettingToolSchemaTtl() {
            RedisConfig config = new RedisConfig();
            config.setToolSchemaTtl(Duration.ofHours(2));

            assertThat(config.getToolSchemaTtl()).isEqualTo(Duration.ofHours(2));
        }

        @Test
        @DisplayName("should allow setting MCP server TTL")
        void allowsSettingMcpServerTtl() {
            RedisConfig config = new RedisConfig();
            config.setMcpServerTtl(Duration.ofMinutes(15));

            assertThat(config.getMcpServerTtl()).isEqualTo(Duration.ofMinutes(15));
        }
    }
}
