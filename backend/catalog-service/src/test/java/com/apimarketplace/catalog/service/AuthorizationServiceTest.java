package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.service.exception.AccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthorizationService")
class AuthorizationServiceTest {

    @Mock
    private ApiRepository apiRepository;

    private AuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        authorizationService = new AuthorizationService(apiRepository);
    }

    @Nested
    @DisplayName("verifyApiOwnership")
    class VerifyApiOwnership {

        @Test
        @DisplayName("should allow access when user owns the API")
        void shouldAllowAccess_whenUserOwnsApi() {
            // Given
            UUID apiId = UUID.randomUUID();
            String userId = "user-123";

            ApiEntity api = new ApiEntity();
            api.setId(apiId);
            api.setCreatedBy(userId);

            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));

            // When/Then - no exception thrown
            authorizationService.verifyApiOwnership(userId, apiId);
        }

        @Test
        @DisplayName("should allow access with case-insensitive user ID comparison")
        void shouldAllowAccess_withCaseInsensitiveUserId() {
            // Given
            UUID apiId = UUID.randomUUID();

            ApiEntity api = new ApiEntity();
            api.setId(apiId);
            api.setCreatedBy("User-123");

            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));

            // When/Then - no exception thrown
            authorizationService.verifyApiOwnership("user-123", apiId);
        }

        @Test
        @DisplayName("should throw AccessDeniedException when user does not own API")
        void shouldThrowException_whenUserDoesNotOwnApi() {
            // Given
            UUID apiId = UUID.randomUUID();
            String userId = "user-123";
            String ownerId = "other-user";

            ApiEntity api = new ApiEntity();
            api.setId(apiId);
            api.setCreatedBy(ownerId);

            when(apiRepository.findById(apiId)).thenReturn(Optional.of(api));

            // When/Then
            assertThatThrownBy(() -> authorizationService.verifyApiOwnership(userId, apiId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("user-123")
                .hasMessageContaining(apiId.toString());
        }

        @Test
        @DisplayName("should throw AccessDeniedException when userId is null")
        void shouldThrowException_whenUserIdIsNull() {
            // Given
            UUID apiId = UUID.randomUUID();

            // When/Then
            assertThatThrownBy(() -> authorizationService.verifyApiOwnership(null, apiId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("anonymous");
        }

        @Test
        @DisplayName("should throw AccessDeniedException when userId is blank")
        void shouldThrowException_whenUserIdIsBlank() {
            // Given
            UUID apiId = UUID.randomUUID();

            // When/Then
            assertThatThrownBy(() -> authorizationService.verifyApiOwnership("  ", apiId))
                .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        @DisplayName("should not throw when API not found (let controller handle 404)")
        void shouldNotThrow_whenApiNotFound() {
            // Given
            UUID apiId = UUID.randomUUID();
            String userId = "user-123";

            when(apiRepository.findById(apiId)).thenReturn(Optional.empty());

            // When/Then - no exception thrown
            authorizationService.verifyApiOwnership(userId, apiId);
        }
    }
}
