package com.apimarketplace.datasource.controllers.datasource;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Tests for TenantIdResolver.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantIdResolver")
class TenantIdResolverTest {

    @Mock
    private HttpServletRequest request;

    private TenantIdResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new TenantIdResolver();
    }

    @Nested
    @DisplayName("resolveTenantId")
    class ResolveTenantIdTests {

        @Test
        @DisplayName("Should use X-User-ID header when present")
        void shouldUseHeaderWhenPresent() {
            when(request.getHeader("X-User-ID")).thenReturn("header-tenant-id");

            String result = resolver.resolveTenantId(request, "param-tenant-id");

            assertThat(result).isEqualTo("header-tenant-id");
        }

        @Test
        @DisplayName("Should ignore query parameter when header is null")
        void shouldIgnoreParamWhenHeaderIsNull() {
            when(request.getHeader("X-User-ID")).thenReturn(null);

            String result = resolver.resolveTenantId(request, "param-tenant-id");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Should ignore query parameter when header is blank")
        void shouldIgnoreParamWhenHeaderIsBlank() {
            when(request.getHeader("X-User-ID")).thenReturn("   ");

            String result = resolver.resolveTenantId(request, "param-tenant-id");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Should ignore query parameter when header is empty")
        void shouldIgnoreParamWhenHeaderIsEmpty() {
            when(request.getHeader("X-User-ID")).thenReturn("");

            String result = resolver.resolveTenantId(request, "param-tenant-id");

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Should return null when both header and param are null")
        void shouldReturnNullWhenBothAreNull() {
            when(request.getHeader("X-User-ID")).thenReturn(null);

            String result = resolver.resolveTenantId(request, null);

            assertThat(result).isNull();
        }
    }
}
