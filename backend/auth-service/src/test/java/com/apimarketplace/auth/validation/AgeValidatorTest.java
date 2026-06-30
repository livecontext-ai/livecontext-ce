package com.apimarketplace.auth.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgeValidator Tests")
class AgeValidatorTest {

    private AgeValidator validator;

    @BeforeEach
    void setUp() {
        validator = new AgeValidator();
    }

    @Nested
    @DisplayName("validate() method")
    class ValidateTests {

        @Test
        @DisplayName("should accept valid birth date for 18+ user")
        void shouldAcceptValidBirthDateFor18Plus() {
            LocalDateTime birthDate = LocalDateTime.now().minusYears(25);

            Optional<String> result = validator.validate(birthDate);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should accept exactly 18 years old user")
        void shouldAcceptExactly18YearsOld() {
            LocalDateTime birthDate = LocalDateTime.now().minusYears(18);

            Optional<String> result = validator.validate(birthDate);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should accept 120 years old user")
        void shouldAccept120YearsOld() {
            LocalDateTime birthDate = LocalDateTime.now().minusYears(120);

            Optional<String> result = validator.validate(birthDate);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should reject null birth date")
        void shouldRejectNullBirthDate() {
            Optional<String> result = validator.validate(null);

            assertThat(result).isPresent();
            assertThat(result.get()).contains("required");
        }

        @Test
        @DisplayName("should reject under 18 user")
        void shouldRejectUnder18() {
            LocalDateTime birthDate = LocalDateTime.now().minusYears(17);

            Optional<String> result = validator.validate(birthDate);

            assertThat(result).isPresent();
            assertThat(result.get()).contains("18 years old");
        }

        @Test
        @DisplayName("should reject almost 18 user (birthday not yet)")
        void shouldRejectAlmost18() {
            LocalDateTime birthDate = LocalDateTime.now()
                    .minusYears(18)
                    .plusDays(1);

            Optional<String> result = validator.validate(birthDate);

            assertThat(result).isPresent();
            assertThat(result.get()).contains("18 years old");
        }

        @Test
        @DisplayName("should reject over 120 years old")
        void shouldRejectOver120() {
            LocalDateTime birthDate = LocalDateTime.now().minusYears(121);

            Optional<String> result = validator.validate(birthDate);

            assertThat(result).isPresent();
            assertThat(result.get()).contains("valid birth date");
        }

        @Test
        @DisplayName("should reject future birth date")
        void shouldRejectFutureBirthDate() {
            LocalDateTime birthDate = LocalDateTime.now().plusYears(1);

            Optional<String> result = validator.validate(birthDate);

            assertThat(result).isPresent();
        }
    }

    @Nested
    @DisplayName("calculateAge() method")
    class CalculateAgeTests {

        @Test
        @DisplayName("should return 0 for null birth date")
        void shouldReturn0ForNullBirthDate() {
            int result = validator.calculateAge(null);

            assertThat(result).isZero();
        }

        @Test
        @DisplayName("should calculate correct age")
        void shouldCalculateCorrectAge() {
            LocalDateTime birthDate = LocalDateTime.now().minusYears(30);

            int result = validator.calculateAge(birthDate);

            assertThat(result).isEqualTo(30);
        }

        @Test
        @DisplayName("should handle birthday not yet this year")
        void shouldHandleBirthdayNotYetThisYear() {
            LocalDateTime birthDate = LocalDateTime.now()
                    .minusYears(30)
                    .plusMonths(1);

            int result = validator.calculateAge(birthDate);

            assertThat(result).isEqualTo(29);
        }

        @Test
        @DisplayName("should handle birthday on same day")
        void shouldHandleBirthdayOnSameDay() {
            LocalDateTime birthDate = LocalDateTime.now().minusYears(25);

            int result = validator.calculateAge(birthDate);

            assertThat(result).isEqualTo(25);
        }

        @Test
        @DisplayName("should handle birthday yesterday")
        void shouldHandleBirthdayYesterday() {
            LocalDateTime birthDate = LocalDateTime.now()
                    .minusYears(25)
                    .minusDays(1);

            int result = validator.calculateAge(birthDate);

            assertThat(result).isEqualTo(25);
        }

        @Test
        @DisplayName("should handle birthday tomorrow (not yet)")
        void shouldHandleBirthdayTomorrow() {
            LocalDateTime birthDate = LocalDateTime.now()
                    .minusYears(25)
                    .plusDays(1);

            int result = validator.calculateAge(birthDate);

            assertThat(result).isEqualTo(24);
        }

        @Test
        @DisplayName("should return negative for future birth date")
        void shouldReturnNegativeForFutureBirthDate() {
            LocalDateTime birthDate = LocalDateTime.now().plusYears(5);

            int result = validator.calculateAge(birthDate);

            assertThat(result).isNegative();
        }
    }

    @Nested
    @DisplayName("isAgeValid() method")
    class IsAgeValidTests {

        @Test
        @DisplayName("should return true for valid age")
        void shouldReturnTrueForValidAge() {
            LocalDateTime birthDate = LocalDateTime.now().minusYears(25);

            boolean result = validator.isAgeValid(birthDate);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return false for null birth date")
        void shouldReturnFalseForNullBirthDate() {
            boolean result = validator.isAgeValid(null);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false for under 18")
        void shouldReturnFalseForUnder18() {
            LocalDateTime birthDate = LocalDateTime.now().minusYears(15);

            boolean result = validator.isAgeValid(birthDate);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return false for over 120")
        void shouldReturnFalseForOver120() {
            LocalDateTime birthDate = LocalDateTime.now().minusYears(150);

            boolean result = validator.isAgeValid(birthDate);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should return true for exactly 18")
        void shouldReturnTrueForExactly18() {
            LocalDateTime birthDate = LocalDateTime.now().minusYears(18);

            boolean result = validator.isAgeValid(birthDate);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should return true for exactly 120")
        void shouldReturnTrueForExactly120() {
            LocalDateTime birthDate = LocalDateTime.now().minusYears(120);

            boolean result = validator.isAgeValid(birthDate);

            assertThat(result).isTrue();
        }
    }
}
