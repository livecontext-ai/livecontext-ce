package com.apimarketplace.common.storage.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("QuotaExceededException Tests")
class QuotaExceededExceptionTest {

    @Nested
    @DisplayName("Single Argument Constructor")
    class SingleArgConstructorTests {

        @Test
        @DisplayName("should create exception with message")
        void shouldCreateWithMessage() {
            QuotaExceededException exception = new QuotaExceededException("Quota exceeded");

            assertThat(exception.getMessage()).isEqualTo("Quota exceeded");
        }

        @Test
        @DisplayName("should have null tenantId")
        void shouldHaveNullTenantId() {
            QuotaExceededException exception = new QuotaExceededException("Quota exceeded");

            assertThat(exception.getTenantId()).isNull();
        }

        @Test
        @DisplayName("should be a RuntimeException")
        void shouldBeRuntimeException() {
            QuotaExceededException exception = new QuotaExceededException("Quota exceeded");

            assertThat(exception).isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("Two Argument Constructor")
    class TwoArgConstructorTests {

        @Test
        @DisplayName("should create exception with message and tenantId")
        void shouldCreateWithMessageAndTenantId() {
            QuotaExceededException exception = new QuotaExceededException("Hard limit reached", "tenant-123");

            assertThat(exception.getMessage()).isEqualTo("Hard limit reached");
            assertThat(exception.getTenantId()).isEqualTo("tenant-123");
        }

        @Test
        @DisplayName("should preserve tenantId")
        void shouldPreserveTenantId() {
            String tenantId = "org-456";
            QuotaExceededException exception = new QuotaExceededException("msg", tenantId);

            assertThat(exception.getTenantId()).isEqualTo(tenantId);
        }
    }
}
