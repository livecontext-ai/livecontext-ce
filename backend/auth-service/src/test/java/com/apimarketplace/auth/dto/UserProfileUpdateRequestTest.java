package com.apimarketplace.auth.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("UserProfileUpdateRequest Tests")
class UserProfileUpdateRequestTest {

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

    private static Set<ConstraintViolation<UserProfileUpdateRequest>> validateDisplayName(String displayName) {
        UserProfileUpdateRequest request = new UserProfileUpdateRequest();
        request.setDisplayName(displayName);
        return validator.validate(request);
    }

    @Nested
    @DisplayName("Default Constructor")
    class DefaultConstructorTests {

        @Test
        @DisplayName("should create with null fields")
        void shouldCreateWithNullFields() {
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();

            assertThat(request.getFirstName()).isNull();
            assertThat(request.getLastName()).isNull();
            assertThat(request.getAvatarUrl()).isNull();
            assertThat(request.getUsername()).isNull();
            assertThat(request.getAge()).isNull();
            assertThat(request.getEmail()).isNull();
            assertThat(request.getLocale()).isNull();
            assertThat(request.getTimezone()).isNull();
            assertThat(request.getPicture()).isNull();
            assertThat(request.getNickname()).isNull();
            assertThat(request.getGivenName()).isNull();
            assertThat(request.getFamilyName()).isNull();
            assertThat(request.getEmailVerified()).isNull();
        }
    }

    @Nested
    @DisplayName("Parameterized Constructor")
    class ParameterizedConstructorTests {

        @Test
        @DisplayName("should create with main fields")
        void shouldCreateWithMainFields() {
            LocalDateTime age = LocalDateTime.of(1990, 5, 20, 0, 0);

            UserProfileUpdateRequest request = new UserProfileUpdateRequest(
                    "John", "Doe", "https://avatar.url", "johndoe", age);

            assertThat(request.getFirstName()).isEqualTo("John");
            assertThat(request.getLastName()).isEqualTo("Doe");
            assertThat(request.getAvatarUrl()).isEqualTo("https://avatar.url");
            assertThat(request.getUsername()).isEqualTo("johndoe");
            assertThat(request.getAge()).isEqualTo(age);
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should set and get main fields")
        void shouldSetAndGetMainFields() {
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            LocalDateTime age = LocalDateTime.of(1995, 3, 10, 0, 0);

            request.setFirstName("Jane");
            request.setLastName("Smith");
            request.setAvatarUrl("https://example.com/avatar.jpg");
            request.setUsername("janesmith");
            request.setAge(age);

            assertThat(request.getFirstName()).isEqualTo("Jane");
            assertThat(request.getLastName()).isEqualTo("Smith");
            assertThat(request.getAvatarUrl()).isEqualTo("https://example.com/avatar.jpg");
            assertThat(request.getUsername()).isEqualTo("janesmith");
            assertThat(request.getAge()).isEqualTo(age);
        }

        @Test
        @DisplayName("should set and get OIDC fields")
        void shouldSetAndGetOidcFields() {
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();

            request.setEmail("test@keycloak.dev");
            request.setLocale("en-US");
            request.setTimezone("America/New_York");
            request.setPicture("https://example.com/pic.jpg");
            request.setNickname("tester");
            request.setGivenName("Given");
            request.setFamilyName("Family");
            request.setEmailVerified(true);

            assertThat(request.getEmail()).isEqualTo("test@keycloak.dev");
            assertThat(request.getLocale()).isEqualTo("en-US");
            assertThat(request.getTimezone()).isEqualTo("America/New_York");
            assertThat(request.getPicture()).isEqualTo("https://example.com/pic.jpg");
            assertThat(request.getNickname()).isEqualTo("tester");
            assertThat(request.getGivenName()).isEqualTo("Given");
            assertThat(request.getFamilyName()).isEqualTo("Family");
            assertThat(request.getEmailVerified()).isTrue();
        }
    }

    @Nested
    @DisplayName("displayName @Size validation (3-30)")
    class DisplayNameSizeValidationTests {

        @Test
        @DisplayName("rejects 2-char displayName as below new lower bound")
        void rejectsTwoCharDisplayNameBelowLowerBound() {
            Set<ConstraintViolation<UserProfileUpdateRequest>> violations = validateDisplayName("ab");

            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("displayName")
                            && v.getMessage().contains("between 3 and 30"));
        }

        @Test
        @DisplayName("accepts 3-char displayName at new lower bound")
        void acceptsThreeCharDisplayNameAtLowerBound() {
            Set<ConstraintViolation<UserProfileUpdateRequest>> violations = validateDisplayName("abc");

            assertThat(violations).noneMatch(v -> v.getPropertyPath().toString().equals("displayName"));
        }

        @Test
        @DisplayName("accepts 30-char displayName at new upper bound")
        void acceptsThirtyCharDisplayNameAtUpperBound() {
            Set<ConstraintViolation<UserProfileUpdateRequest>> violations = validateDisplayName("a".repeat(30));

            assertThat(violations).noneMatch(v -> v.getPropertyPath().toString().equals("displayName"));
        }

        @Test
        @DisplayName("rejects 31-char displayName above new upper bound")
        void rejectsThirtyOneCharDisplayNameAboveUpperBound() {
            Set<ConstraintViolation<UserProfileUpdateRequest>> violations = validateDisplayName("a".repeat(31));

            assertThat(violations)
                    .anyMatch(v -> v.getPropertyPath().toString().equals("displayName")
                            && v.getMessage().contains("between 3 and 30"));
        }

        @Test
        @DisplayName("accepts null displayName since field is optional on profile update")
        void acceptsNullDisplayNameAsOptional() {
            Set<ConstraintViolation<UserProfileUpdateRequest>> violations = validateDisplayName(null);

            assertThat(violations).noneMatch(v -> v.getPropertyPath().toString().equals("displayName"));
        }

        @Test
        @DisplayName("setter trims whitespace so a padded valid name validates against the trimmed length")
        void setterTrimsWhitespacePaddedValidName() {
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setDisplayName("  abc  ");

            assertThat(request.getDisplayName()).isEqualTo("abc");
            assertThat(validator.validate(request))
                    .noneMatch(v -> v.getPropertyPath().toString().equals("displayName"));
        }

        @Test
        @DisplayName("setter trims whitespace so a padded too-short name still fails @Size on the trimmed value")
        void setterTrimsWhitespacePaddedTooShortName() {
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setDisplayName("  ab  ");

            assertThat(request.getDisplayName()).isEqualTo("ab");
            assertThat(validator.validate(request))
                    .anyMatch(v -> v.getPropertyPath().toString().equals("displayName")
                            && v.getMessage().contains("between 3 and 30"));
        }
    }

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("should include key fields in toString")
        void shouldIncludeKeyFieldsInToString() {
            UserProfileUpdateRequest request = new UserProfileUpdateRequest();
            request.setFirstName("John");
            request.setLastName("Doe");
            request.setUsername("johndoe");
            request.setEmail("john@example.com");

            String result = request.toString();

            assertThat(result).contains("firstName='John'");
            assertThat(result).contains("lastName='Doe'");
            assertThat(result).contains("username='johndoe'");
            assertThat(result).contains("email='john@example.com'");
        }
    }
}
