package com.apimarketplace.auth.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OnboardingRequest displayName @Size validation (3-30)")
class OnboardingRequestValidationTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void initValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    private static Set<ConstraintViolation<OnboardingRequest>> validateDisplayName(String displayName) {
        return validator.validate(new OnboardingRequest(displayName));
    }

    @Test
    @DisplayName("rejects 2-char displayName as below new lower bound")
    void rejectsTwoCharDisplayNameBelowLowerBound() {
        Set<ConstraintViolation<OnboardingRequest>> violations = validateDisplayName("ab");

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("displayName")
                        && v.getMessage().contains("between 3 and 30"));
    }

    @Test
    @DisplayName("accepts 3-char displayName at new lower bound")
    void acceptsThreeCharDisplayNameAtLowerBound() {
        Set<ConstraintViolation<OnboardingRequest>> violations = validateDisplayName("abc");

        assertThat(violations).noneMatch(v -> v.getPropertyPath().toString().equals("displayName"));
    }

    @Test
    @DisplayName("accepts 30-char displayName at new upper bound")
    void acceptsThirtyCharDisplayNameAtUpperBound() {
        Set<ConstraintViolation<OnboardingRequest>> violations = validateDisplayName("a".repeat(30));

        assertThat(violations).noneMatch(v -> v.getPropertyPath().toString().equals("displayName"));
    }

    @Test
    @DisplayName("rejects 31-char displayName above new upper bound")
    void rejectsThirtyOneCharDisplayNameAboveUpperBound() {
        Set<ConstraintViolation<OnboardingRequest>> violations = validateDisplayName("a".repeat(31));

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("displayName")
                        && v.getMessage().contains("between 3 and 30"));
    }

    @Test
    @DisplayName("rejects blank displayName via @NotBlank")
    void rejectsBlankDisplayName() {
        Set<ConstraintViolation<OnboardingRequest>> violations = validateDisplayName("   ");

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("displayName"));
    }

    @Test
    @DisplayName("rejects null displayName via @NotBlank")
    void rejectsNullDisplayName() {
        Set<ConstraintViolation<OnboardingRequest>> violations = validateDisplayName(null);

        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("displayName"));
    }

    @Test
    @DisplayName("setter trims whitespace so a padded valid name validates against the trimmed length")
    void setterTrimsWhitespacePaddedValidName() {
        OnboardingRequest request = new OnboardingRequest();
        request.setDisplayName("  abc  ");

        assertThat(request.getDisplayName()).isEqualTo("abc");
        assertThat(validator.validate(request))
                .noneMatch(v -> v.getPropertyPath().toString().equals("displayName"));
    }

    @Test
    @DisplayName("setter trims whitespace so a padded too-short name still fails @Size on the trimmed value")
    void setterTrimsWhitespacePaddedTooShortName() {
        OnboardingRequest request = new OnboardingRequest();
        request.setDisplayName("  ab  ");

        assertThat(request.getDisplayName()).isEqualTo("ab");
        assertThat(validator.validate(request))
                .anyMatch(v -> v.getPropertyPath().toString().equals("displayName")
                        && v.getMessage().contains("between 3 and 30"));
    }
}
