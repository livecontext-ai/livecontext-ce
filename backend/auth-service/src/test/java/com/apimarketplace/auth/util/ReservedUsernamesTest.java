package com.apimarketplace.auth.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReservedUsernames Tests")
class ReservedUsernamesTest {

    @Nested
    @DisplayName("isReserved()")
    class IsReservedTests {

        @ParameterizedTest
        @ValueSource(strings = {"admin", "root", "system", "support", "help", "moderator", "test", "dev", "prod"})
        @DisplayName("should return true for reserved usernames")
        void shouldReturnTrueForReservedUsernames(String username) {
            assertThat(ReservedUsernames.isReserved(username)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"ADMIN", "Admin", "ROOT", "Root", "SYSTEM", "System"})
        @DisplayName("should be case insensitive")
        void shouldBeCaseInsensitive(String username) {
            assertThat(ReservedUsernames.isReserved(username)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"john", "myproject", "cooluser123", "uniquename"})
        @DisplayName("should return false for non-reserved usernames")
        void shouldReturnFalseForNonReservedUsernames(String username) {
            assertThat(ReservedUsernames.isReserved(username)).isFalse();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("should return false for null, empty or blank")
        void shouldReturnFalseForNullEmptyOrBlank(String username) {
            assertThat(ReservedUsernames.isReserved(username)).isFalse();
        }

        @Test
        @DisplayName("should handle username with leading/trailing spaces")
        void shouldHandleUsernameWithSpaces() {
            assertThat(ReservedUsernames.isReserved("  admin  ")).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"dashboard", "profile", "settings", "login", "logout", "register", "signup"})
        @DisplayName("should reserve page/route names")
        void shouldReservePageRouteNames(String username) {
            assertThat(ReservedUsernames.isReserved(username)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"billing", "payment", "checkout", "shop", "store", "marketplace"})
        @DisplayName("should reserve commerce-related names")
        void shouldReserveCommerceRelatedNames(String username) {
            assertThat(ReservedUsernames.isReserved(username)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"security", "auth", "token", "password", "session"})
        @DisplayName("should reserve security-related names")
        void shouldReserveSecurityRelatedNames(String username) {
            assertThat(ReservedUsernames.isReserved(username)).isTrue();
        }
    }

    @Nested
    @DisplayName("getReservedUsernames()")
    class GetReservedUsernamesTests {

        @Test
        @DisplayName("should return non-empty set")
        void shouldReturnNonEmptySet() {
            Set<String> reserved = ReservedUsernames.getReservedUsernames();

            assertThat(reserved).isNotEmpty();
            assertThat(reserved).contains("admin", "root", "system");
        }

        @Test
        @DisplayName("should return a copy (not the original)")
        void shouldReturnCopy() {
            Set<String> reserved = ReservedUsernames.getReservedUsernames();
            int originalSize = reserved.size();

            reserved.add("newusername");

            assertThat(ReservedUsernames.getReservedUsernames()).hasSize(originalSize);
        }
    }

    @Nested
    @DisplayName("getReservedUsernamesCount()")
    class GetReservedUsernamesCountTests {

        @Test
        @DisplayName("should return positive count")
        void shouldReturnPositiveCount() {
            int count = ReservedUsernames.getReservedUsernamesCount();

            assertThat(count).isPositive();
        }

        @Test
        @DisplayName("should match set size")
        void shouldMatchSetSize() {
            int count = ReservedUsernames.getReservedUsernamesCount();
            int setSize = ReservedUsernames.getReservedUsernames().size();

            assertThat(count).isEqualTo(setSize);
        }
    }
}
