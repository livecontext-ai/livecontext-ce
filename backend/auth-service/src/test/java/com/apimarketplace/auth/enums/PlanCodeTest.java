package com.apimarketplace.auth.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PlanCode Tests")
class PlanCodeTest {

    @Nested
    @DisplayName("normalize()")
    class NormalizeTests {

        @Test
        @DisplayName("should convert to uppercase")
        void shouldConvertToUppercase() {
            assertThat(PlanCode.normalize("free")).isEqualTo("FREE");
            assertThat(PlanCode.normalize("pro")).isEqualTo("PRO");
            assertThat(PlanCode.normalize("starter")).isEqualTo("STARTER");
        }

        @Test
        @DisplayName("should convert ENTERPRISE to ENTERPRISE_BASIC")
        void shouldConvertEnterpriseToEnterpriseBasic() {
            assertThat(PlanCode.normalize("enterprise")).isEqualTo("ENTERPRISE_BASIC");
            assertThat(PlanCode.normalize("ENTERPRISE")).isEqualTo("ENTERPRISE_BASIC");
        }

        @Test
        @DisplayName("should trim whitespace")
        void shouldTrimWhitespace() {
            assertThat(PlanCode.normalize("  FREE  ")).isEqualTo("FREE");
            assertThat(PlanCode.normalize("  pro  ")).isEqualTo("PRO");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("should return null for null, empty or blank input")
        void shouldReturnNullForNullEmptyOrBlank(String input) {
            assertThat(PlanCode.normalize(input)).isNull();
        }
    }

    @Nested
    @DisplayName("isValid(String planCode, Set<String> availablePlanCodes)")
    class IsValidWithAvailableCodesTests {

        @Test
        @DisplayName("should return true when plan code is in available codes")
        void shouldReturnTrueWhenPlanCodeInAvailableCodes() {
            Set<String> available = Set.of("FREE", "STARTER", "PRO");

            assertThat(PlanCode.isValid("free", available)).isTrue();
            assertThat(PlanCode.isValid("PRO", available)).isTrue();
        }

        @Test
        @DisplayName("should return false when plan code is not in available codes")
        void shouldReturnFalseWhenPlanCodeNotInAvailableCodes() {
            Set<String> available = Set.of("FREE", "STARTER");

            assertThat(PlanCode.isValid("ULTRA", available)).isFalse();
        }

        @Test
        @DisplayName("should return false for null plan code")
        void shouldReturnFalseForNullPlanCode() {
            assertThat(PlanCode.isValid(null, Set.of("FREE"))).isFalse();
        }

        @Test
        @DisplayName("should return false for null available codes")
        void shouldReturnFalseForNullAvailableCodes() {
            assertThat(PlanCode.isValid("FREE", (Set<String>) null)).isFalse();
        }
    }

    @Nested
    @DisplayName("isValid(String planCode)")
    class IsValidSimpleTests {

        @Test
        @DisplayName("should return true for FREE")
        void shouldReturnTrueForFree() {
            assertThat(PlanCode.isValid("free")).isTrue();
            assertThat(PlanCode.isValid("FREE")).isTrue();
        }

        @Test
        @DisplayName("should return false for non-FREE plans")
        void shouldReturnFalseForNonFreePlans() {
            assertThat(PlanCode.isValid("PRO")).isFalse();
            assertThat(PlanCode.isValid("STARTER")).isFalse();
        }

        @Test
        @DisplayName("should return false for null")
        void shouldReturnFalseForNull() {
            assertThat(PlanCode.isValid((String) null)).isFalse();
        }
    }

    @Nested
    @DisplayName("getAllValidCodes()")
    class GetAllValidCodesTests {

        @Test
        @DisplayName("should always include FREE")
        void shouldAlwaysIncludeFree() {
            Set<String> result = PlanCode.getAllValidCodes(null);

            assertThat(result).contains("FREE");
        }

        @Test
        @DisplayName("should include all available codes plus FREE")
        void shouldIncludeAllAvailableCodesPlusFree() {
            Set<String> available = Set.of("STARTER", "PRO");

            Set<String> result = PlanCode.getAllValidCodes(available);

            assertThat(result).containsExactlyInAnyOrder("FREE", "STARTER", "PRO");
        }

        @Test
        @DisplayName("should handle null available codes")
        void shouldHandleNullAvailableCodes() {
            Set<String> result = PlanCode.getAllValidCodes(null);

            assertThat(result).hasSize(1);
            assertThat(result).contains("FREE");
        }
    }
}
