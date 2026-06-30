package com.apimarketplace.catalog.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SlugUtils utility class.
 *
 * Tests slug generation, uniqueness, validation, and sanitization.
 */
@DisplayName("SlugUtils Tests")
class SlugUtilsTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // generateSlug() TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("generateSlug()")
    class GenerateSlugTests {

        @Test
        @DisplayName("Should generate slug from simple text")
        void shouldGenerateSlugFromSimpleText() {
            assertEquals("hello-world", SlugUtils.generateSlug("Hello World"));
        }

        @Test
        @DisplayName("Should convert to lowercase")
        void shouldConvertToLowercase() {
            assertEquals("my-api-name", SlugUtils.generateSlug("MY API NAME"));
        }

        @Test
        @DisplayName("Should replace spaces with hyphens")
        void shouldReplaceSpacesWithHyphens() {
            assertEquals("hello-world", SlugUtils.generateSlug("hello world"));
        }

        @Test
        @DisplayName("Should replace underscores with hyphens")
        void shouldReplaceUnderscoresWithHyphens() {
            assertEquals("hello-world", SlugUtils.generateSlug("hello_world"));
        }

        @Test
        @DisplayName("Should remove special characters (without replacing with hyphens)")
        void shouldRemoveSpecialCharacters() {
            // Implementation removes special chars without replacing them with hyphens
            // Only spaces and underscores are replaced with hyphens
            assertEquals("helloworld", SlugUtils.generateSlug("hello@world!"));
        }

        @Test
        @DisplayName("Should collapse multiple hyphens")
        void shouldCollapseMultipleHyphens() {
            assertEquals("hello-world", SlugUtils.generateSlug("hello---world"));
        }

        @Test
        @DisplayName("Should remove leading hyphens")
        void shouldRemoveLeadingHyphens() {
            assertEquals("hello", SlugUtils.generateSlug("---hello"));
        }

        @Test
        @DisplayName("Should remove trailing hyphens")
        void shouldRemoveTrailingHyphens() {
            assertEquals("hello", SlugUtils.generateSlug("hello---"));
        }

        @Test
        @DisplayName("Should trim whitespace")
        void shouldTrimWhitespace() {
            assertEquals("hello", SlugUtils.generateSlug("  hello  "));
        }

        @Test
        @DisplayName("Should preserve numbers (dots are removed)")
        void shouldPreserveNumbers() {
            // Dots are special chars and are removed, not replaced with hyphens
            assertEquals("api-v20", SlugUtils.generateSlug("API v2.0"));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("Should return empty string for null or empty input")
        void shouldReturnEmptyForNullOrEmpty(String input) {
            assertEquals("", SlugUtils.generateSlug(input));
        }

        @Test
        @DisplayName("Should return empty for whitespace only")
        void shouldReturnEmptyForWhitespace() {
            assertEquals("", SlugUtils.generateSlug("   "));
        }

        @ParameterizedTest
        @CsvSource({
            "Hello World, hello-world",
            "my_api_name, my-api-name",
            "API v2.0, api-v20",
            "Café Naïve, caf-nave",
            "test@example.com, testexamplecom",
            "123-456, 123-456"
        })
        @DisplayName("Should generate correct slugs for various inputs")
        void shouldGenerateCorrectSlugs(String input, String expected) {
            assertEquals(expected, SlugUtils.generateSlug(input));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // generateUniqueSlug() TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("generateUniqueSlug()")
    class GenerateUniqueSlugTests {

        @Test
        @DisplayName("Should return base slug when no existing slugs")
        void shouldReturnBaseSlugWhenNoExisting() {
            assertEquals("my-slug", SlugUtils.generateUniqueSlug("my-slug", Collections.emptyList()));
        }

        @Test
        @DisplayName("Should return base slug when null existing list")
        void shouldReturnBaseSlugWhenNullList() {
            assertEquals("my-slug", SlugUtils.generateUniqueSlug("my-slug", null));
        }

        @Test
        @DisplayName("Should return base slug when not in existing list")
        void shouldReturnBaseSlugWhenNotInList() {
            List<String> existing = Arrays.asList("other-slug", "another-slug");
            assertEquals("my-slug", SlugUtils.generateUniqueSlug("my-slug", existing));
        }

        @Test
        @DisplayName("Should append -1 when base slug exists")
        void shouldAppendOneWhenExists() {
            List<String> existing = Arrays.asList("my-slug");
            assertEquals("my-slug-1", SlugUtils.generateUniqueSlug("my-slug", existing));
        }

        @Test
        @DisplayName("Should append -2 when -1 also exists")
        void shouldAppendTwoWhenOneExists() {
            List<String> existing = Arrays.asList("my-slug", "my-slug-1");
            assertEquals("my-slug-2", SlugUtils.generateUniqueSlug("my-slug", existing));
        }

        @Test
        @DisplayName("Should find next available number")
        void shouldFindNextAvailableNumber() {
            List<String> existing = Arrays.asList("my-slug", "my-slug-1", "my-slug-2", "my-slug-3");
            assertEquals("my-slug-4", SlugUtils.generateUniqueSlug("my-slug", existing));
        }

        @Test
        @DisplayName("Should handle gaps in existing numbers")
        void shouldHandleGapsInNumbers() {
            // Note: current implementation doesn't fill gaps, it just increments
            List<String> existing = Arrays.asList("my-slug", "my-slug-1", "my-slug-3");
            // It will try 1 (exists), then 2 (doesn't exist), so returns 2
            assertEquals("my-slug-2", SlugUtils.generateUniqueSlug("my-slug", existing));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // isValidSlug() TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("isValidSlug()")
    class IsValidSlugTests {

        @ParameterizedTest
        @ValueSource(strings = {
            "my-slug",
            "hello-world",
            "api-v2",
            "a",
            "123",
            "test123",
            "my-api-name"
        })
        @DisplayName("Should return true for valid slugs")
        void shouldReturnTrueForValidSlugs(String slug) {
            assertTrue(SlugUtils.isValidSlug(slug));
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "My-Slug",           // uppercase
            "my_slug",           // underscore
            "my slug",           // space
            "-my-slug",          // leading hyphen
            "my-slug-",          // trailing hyphen
            "my--slug",          // double hyphen
            "my@slug",           // special char
            ""                   // empty
        })
        @DisplayName("Should return false for invalid slugs")
        void shouldReturnFalseForInvalidSlugs(String slug) {
            assertFalse(SlugUtils.isValidSlug(slug));
        }

        @Test
        @DisplayName("Should return false for null")
        void shouldReturnFalseForNull() {
            assertFalse(SlugUtils.isValidSlug(null));
        }

        @Test
        @DisplayName("Should return false for whitespace only")
        void shouldReturnFalseForWhitespace() {
            assertFalse(SlugUtils.isValidSlug("   "));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // sanitizeSlug() TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("sanitizeSlug()")
    class SanitizeSlugTests {

        @Test
        @DisplayName("Should sanitize invalid slug")
        void shouldSanitizeInvalidSlug() {
            assertEquals("my-slug", SlugUtils.sanitizeSlug("My_Slug"));
        }

        @Test
        @DisplayName("Should return empty for null")
        void shouldReturnEmptyForNull() {
            assertEquals("", SlugUtils.sanitizeSlug(null));
        }

        @Test
        @DisplayName("Should pass through valid slug")
        void shouldPassThroughValidSlug() {
            assertEquals("my-slug", SlugUtils.sanitizeSlug("my-slug"));
        }

        @Test
        @DisplayName("Should fix uppercase")
        void shouldFixUppercase() {
            assertEquals("my-slug", SlugUtils.sanitizeSlug("MY-SLUG"));
        }

        @Test
        @DisplayName("Should remove special characters (without hyphens)")
        void shouldRemoveSpecialChars() {
            // sanitizeSlug uses generateSlug which removes special chars
            assertEquals("myslug", SlugUtils.sanitizeSlug("my@slug!"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // appendRandomSuffix() TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("appendRandomSuffix()")
    class AppendRandomSuffixTests {

        @Test
        @DisplayName("Should append 6 character suffix")
        void shouldAppendSixCharSuffix() {
            String result = SlugUtils.appendRandomSuffix("my-slug");

            assertTrue(result.startsWith("my-slug-"));
            assertEquals(14, result.length()); // "my-slug-" (8) + 6 chars
        }

        @Test
        @DisplayName("Should generate different suffixes on each call")
        void shouldGenerateDifferentSuffixes() {
            String result1 = SlugUtils.appendRandomSuffix("my-slug");
            String result2 = SlugUtils.appendRandomSuffix("my-slug");

            assertNotEquals(result1, result2);
        }

        @Test
        @DisplayName("Should preserve base slug")
        void shouldPreserveBaseSlug() {
            String result = SlugUtils.appendRandomSuffix("test-api");
            assertTrue(result.startsWith("test-api-"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // generateToolCategorySlug() TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("generateToolCategorySlug()")
    class GenerateToolCategorySlugTests {

        @Test
        @DisplayName("Should generate category slug")
        void shouldGenerateCategorySlug() {
            assertEquals("data-processing",
                SlugUtils.generateToolCategorySlug("Data Processing", Collections.emptyList()));
        }

        @Test
        @DisplayName("Should make slug unique if exists")
        void shouldMakeUniqueIfExists() {
            List<String> existing = Arrays.asList("data-processing");
            assertEquals("data-processing-1",
                SlugUtils.generateToolCategorySlug("Data Processing", existing));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // generateToolNameSlug() TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("generateToolNameSlug()")
    class GenerateToolNameSlugTests {

        @Test
        @DisplayName("Should generate tool name slug")
        void shouldGenerateToolNameSlug() {
            assertEquals("get-users",
                SlugUtils.generateToolNameSlug("Get Users", Collections.emptyList()));
        }

        @Test
        @DisplayName("Should make slug unique if exists")
        void shouldMakeUniqueIfExists() {
            List<String> existing = Arrays.asList("get-users");
            assertEquals("get-users-1",
                SlugUtils.generateToolNameSlug("Get Users", existing));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // generateApiCategorySlug() TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("generateApiCategorySlug()")
    class GenerateApiCategorySlugTests {

        @Test
        @DisplayName("Should generate API category slug")
        void shouldGenerateApiCategorySlug() {
            assertEquals("finance",
                SlugUtils.generateApiCategorySlug("Finance", Collections.emptyList()));
        }

        @Test
        @DisplayName("Should make slug unique if exists")
        void shouldMakeUniqueIfExists() {
            List<String> existing = Arrays.asList("finance");
            assertEquals("finance-1",
                SlugUtils.generateApiCategorySlug("Finance", existing));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // generateApiSubcategorySlug() TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("generateApiSubcategorySlug()")
    class GenerateApiSubcategorySlugTests {

        @Test
        @DisplayName("Should generate subcategory slug")
        void shouldGenerateSubcategorySlug() {
            assertEquals("payment-processing",
                SlugUtils.generateApiSubcategorySlug("Payment Processing", Collections.emptyList()));
        }

        @Test
        @DisplayName("Should make slug unique if exists")
        void shouldMakeUniqueIfExists() {
            List<String> existing = Arrays.asList("payment-processing");
            assertEquals("payment-processing-1",
                SlugUtils.generateApiSubcategorySlug("Payment Processing", existing));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // generateApiSubcategorySlugWithPrefix() TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("generateApiSubcategorySlugWithPrefix()")
    class GenerateApiSubcategorySlugWithPrefixTests {

        @Test
        @DisplayName("Should generate prefixed subcategory slug")
        void shouldGeneratePrefixedSlug() {
            assertEquals("finance-payment-processing",
                SlugUtils.generateApiSubcategorySlugWithPrefix("finance", "Payment Processing", Collections.emptyList()));
        }

        @Test
        @DisplayName("Should make prefixed slug unique if exists")
        void shouldMakePrefixedUniqueIfExists() {
            List<String> existing = Arrays.asList("finance-payment-processing");
            assertEquals("finance-payment-processing-1",
                SlugUtils.generateApiSubcategorySlugWithPrefix("finance", "Payment Processing", existing));
        }

        @Test
        @DisplayName("Should handle complex category and name")
        void shouldHandleComplexInputs() {
            assertEquals("data-services-real-time-analytics",
                SlugUtils.generateApiSubcategorySlugWithPrefix("Data Services", "Real-Time Analytics", Collections.emptyList()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE CASES
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCases {

        @Test
        @DisplayName("Should handle very long input text")
        void shouldHandleLongInput() {
            String longText = "a".repeat(1000);
            String result = SlugUtils.generateSlug(longText);

            assertNotNull(result);
            assertEquals(1000, result.length());
        }

        @Test
        @DisplayName("Should handle input with only numbers")
        void shouldHandleOnlyNumbers() {
            assertEquals("123456", SlugUtils.generateSlug("123456"));
        }

        @Test
        @DisplayName("Should handle input with only special characters")
        void shouldHandleOnlySpecialChars() {
            assertEquals("", SlugUtils.generateSlug("@#$%^&*"));
        }

        @Test
        @DisplayName("Should handle mixed valid and invalid characters")
        void shouldHandleMixedChars() {
            assertEquals("hello123world", SlugUtils.generateSlug("hello@123#world"));
        }

        @Test
        @DisplayName("isValidSlug should accept slug with numbers only")
        void shouldAcceptNumbersOnlySlug() {
            assertTrue(SlugUtils.isValidSlug("123"));
        }

        @Test
        @DisplayName("generateUniqueSlug should handle large existing list")
        void shouldHandleLargeExistingList() {
            List<String> existing = new ArrayList<>();
            existing.add("my-slug");
            for (int i = 1; i <= 100; i++) {
                existing.add("my-slug-" + i);
            }

            assertEquals("my-slug-101", SlugUtils.generateUniqueSlug("my-slug", existing));
        }
    }
}
