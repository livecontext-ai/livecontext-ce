package com.apimarketplace.common.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TenantResolver Tests")
class TenantResolverTest {

    private TenantResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new TenantResolver();
    }

    @Nested
    @DisplayName("resolve()")
    class ResolveTests {

        @Test
        @DisplayName("should return tenant ID from X-User-ID header")
        void shouldReturnTenantId() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-User-ID", "tenant-123");

            assertThat(resolver.resolve(request)).isEqualTo("tenant-123");
        }

        @Test
        @DisplayName("should throw TenantRequiredException when header is missing")
        void shouldThrowWhenMissing() {
            MockHttpServletRequest request = new MockHttpServletRequest();

            assertThatThrownBy(() -> resolver.resolve(request))
                    .isInstanceOf(TenantRequiredException.class)
                    .hasMessageContaining("X-User-ID");
        }

        @Test
        @DisplayName("should throw TenantRequiredException when header is blank")
        void shouldThrowWhenBlank() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-User-ID", "   ");

            assertThatThrownBy(() -> resolver.resolve(request))
                    .isInstanceOf(TenantRequiredException.class);
        }
    }

    @Nested
    @DisplayName("resolveOptional()")
    class ResolveOptionalTests {

        @Test
        @DisplayName("should return Optional with tenant ID when present")
        void shouldReturnOptionalWithValue() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-User-ID", "tenant-456");

            assertThat(resolver.resolveOptional(request)).isEqualTo(Optional.of("tenant-456"));
        }

        @Test
        @DisplayName("should return empty Optional when header missing")
        void shouldReturnEmptyOptional() {
            MockHttpServletRequest request = new MockHttpServletRequest();

            assertThat(resolver.resolveOptional(request)).isEmpty();
        }

        @Test
        @DisplayName("should return empty Optional when header is blank")
        void shouldReturnEmptyForBlank() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-User-ID", "  ");

            assertThat(resolver.resolveOptional(request)).isEmpty();
        }
    }

    @Nested
    @DisplayName("resolveOrNull()")
    class ResolveOrNullTests {

        @Test
        @DisplayName("should return header value when present")
        void shouldPreferHeader() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-User-ID", "tenant-789");

            assertThat(resolver.resolveOrNull(request)).isEqualTo("tenant-789");
        }

        @Test
        @DisplayName("should return null when header missing (round-4 Bug-#4: fallback param ignored)")
        void shouldUseFallback() {
            MockHttpServletRequest request = new MockHttpServletRequest();

            // Round-4 audit fix: client-supplied fallback was a Bug-#4 surface.
            // Header is now the only source of truth; absent header → null
            // (callers fail closed via TenantRequiredException upstream).
            assertThat(resolver.resolveOrNull(request)).isNull();
        }
    }

    @Nested
    @DisplayName("validate()")
    class ValidateTests {

        @Test
        @DisplayName("should pass for valid tenant ID")
        void shouldPassForValid() {
            resolver.validate("tenant-123"); // no exception
        }

        @Test
        @DisplayName("should throw for null tenant ID")
        void shouldThrowForNull() {
            assertThatThrownBy(() -> resolver.validate(null))
                    .isInstanceOf(TenantRequiredException.class);
        }

        @Test
        @DisplayName("should throw for blank tenant ID")
        void shouldThrowForBlank() {
            assertThatThrownBy(() -> resolver.validate("  "))
                    .isInstanceOf(TenantRequiredException.class);
        }
    }

    @Nested
    @DisplayName("Organization methods")
    class OrganizationTests {

        @Test
        @DisplayName("resolveOrganizationId should return Optional with org ID")
        void shouldReturnOrgId() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Organization-ID", "org-abc");

            assertThat(resolver.resolveOrganizationId(request)).isEqualTo(Optional.of("org-abc"));
        }

        @Test
        @DisplayName("resolveOrganizationId should return empty when missing")
        void shouldReturnEmptyOrgId() {
            MockHttpServletRequest request = new MockHttpServletRequest();

            assertThat(resolver.resolveOrganizationId(request)).isEmpty();
        }

        @Test
        @DisplayName("resolveOrganizationRole should return Optional with role")
        void shouldReturnOrgRole() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Organization-Role", "ADMIN");

            assertThat(resolver.resolveOrganizationRole(request)).isEqualTo(Optional.of("ADMIN"));
        }

        @Test
        @DisplayName("resolveOrgId alias should return nullable string")
        void shouldReturnNullableOrgId() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Organization-ID", "org-xyz");

            assertThat(resolver.resolveOrgId(request)).isEqualTo("org-xyz");
        }

        @Test
        @DisplayName("resolveOrgId alias should return null when missing")
        void shouldReturnNullOrgId() {
            MockHttpServletRequest request = new MockHttpServletRequest();

            assertThat(resolver.resolveOrgId(request)).isNull();
        }

        @Test
        @DisplayName("resolveOrgRole alias should return nullable string")
        void shouldReturnNullableOrgRole() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Organization-Role", "MEMBER");

            assertThat(resolver.resolveOrgRole(request)).isEqualTo("MEMBER");
        }

        @Test
        @DisplayName("resolveOrgRole alias should return null when missing")
        void shouldReturnNullOrgRole() {
            MockHttpServletRequest request = new MockHttpServletRequest();

            assertThat(resolver.resolveOrgRole(request)).isNull();
        }
    }
}
