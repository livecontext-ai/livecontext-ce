package com.apimarketplace.orchestrator.controllers.storage;

import com.apimarketplace.common.storage.domain.QuotaStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for StorageQuotaDto record.
 */
@DisplayName("StorageQuotaDto")
class StorageQuotaDtoTest {

    @Nested
    @DisplayName("record fields")
    class RecordFieldTests {

        @Test
        @DisplayName("Should store all fields correctly")
        void shouldStoreAllFields() {
            StorageQuotaDto dto = new StorageQuotaDto(
                "tenant-1",
                5_000_000L,
                10_000_000L,
                8_000_000L,
                10_000_000L,
                5_000_000L,
                50.0,
                QuotaStatus.OK
            );

            assertThat(dto.tenantId()).isEqualTo("tenant-1");
            assertThat(dto.usedBytes()).isEqualTo(5_000_000L);
            assertThat(dto.maxBytes()).isEqualTo(10_000_000L);
            assertThat(dto.softLimitBytes()).isEqualTo(8_000_000L);
            assertThat(dto.hardLimitBytes()).isEqualTo(10_000_000L);
            assertThat(dto.availableBytes()).isEqualTo(5_000_000L);
            assertThat(dto.usagePercentage()).isEqualTo(50.0);
            assertThat(dto.status()).isEqualTo(QuotaStatus.OK);
        }
    }

    @Nested
    @DisplayName("formatBytes")
    class FormatBytesTests {

        @Test
        @DisplayName("Should format bytes (less than 1KB)")
        void shouldFormatBytes() {
            StorageQuotaDto dto = new StorageQuotaDto("t", 500L, 1000L, 800L, 1000L, 500L, 50.0, QuotaStatus.OK);
            assertThat(dto.getUsedFormatted()).isEqualTo("500 B");
        }

        @Test
        @DisplayName("Should format kilobytes")
        void shouldFormatKilobytes() {
            StorageQuotaDto dto = new StorageQuotaDto("t", 2048L, 4096L, 3072L, 4096L, 2048L, 50.0, QuotaStatus.OK);
            assertThat(dto.getUsedFormatted()).matches("2[.,]0 KB");
            assertThat(dto.getMaxFormatted()).matches("4[.,]0 KB");
            assertThat(dto.getAvailableFormatted()).matches("2[.,]0 KB");
        }

        @Test
        @DisplayName("Should format megabytes")
        void shouldFormatMegabytes() {
            long fiveMB = 5L * 1024 * 1024;
            long tenMB = 10L * 1024 * 1024;
            StorageQuotaDto dto = new StorageQuotaDto("t", fiveMB, tenMB, 8L * 1024 * 1024, tenMB, fiveMB, 50.0, QuotaStatus.OK);
            assertThat(dto.getUsedFormatted()).matches("5[.,]0 MB");
            assertThat(dto.getMaxFormatted()).matches("10[.,]0 MB");
        }

        @Test
        @DisplayName("Should format gigabytes")
        void shouldFormatGigabytes() {
            long twoGB = 2L * 1024 * 1024 * 1024;
            StorageQuotaDto dto = new StorageQuotaDto("t", twoGB, twoGB, twoGB, twoGB, 0L, 100.0, QuotaStatus.OK);
            assertThat(dto.getUsedFormatted()).matches("2[.,]00 GB");
        }

        @Test
        @DisplayName("Should format zero bytes")
        void shouldFormatZeroBytes() {
            StorageQuotaDto dto = new StorageQuotaDto("t", 0L, 1000L, 800L, 1000L, 1000L, 0.0, QuotaStatus.OK);
            assertThat(dto.getUsedFormatted()).isEqualTo("0 B");
        }
    }
}
