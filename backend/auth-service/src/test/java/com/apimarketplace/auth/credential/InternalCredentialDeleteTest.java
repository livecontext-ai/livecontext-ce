package com.apimarketplace.auth.credential;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.CreatePlatformCredentialRequest;
import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredentialResponse;
import com.apimarketplace.auth.credential.repository.CredentialRepository;
import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.web.InternalCredentialController;
import com.apimarketplace.auth.credential.service.InternalCredentialService;
import com.apimarketplace.auth.credential.service.PlatformCredentialPricingService;
import com.apimarketplace.auth.credential.service.PlatformCredentialService;
import com.apimarketplace.auth.credential.service.PricingVersionService;
import com.apimarketplace.common.security.CredentialEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Internal Credential Delete by Integration Tests")
class InternalCredentialDeleteTest {

    @Mock
    private CredentialRepository credentialRepository;

    @Mock
    private InternalCredentialService internalCredentialService;

    @Mock
    private CredentialService credentialService;

    @Mock
    private PlatformCredentialService platformCredentialService;

    @Mock
    private PlatformCredentialPricingService platformCredentialPricingService;

    @Mock
    private PricingVersionService pricingVersionService;

    @Mock
    private CredentialEncryptionService encryptionService;

    private InternalCredentialController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalCredentialController(
                internalCredentialService,
                credentialService,
                platformCredentialService,
                platformCredentialPricingService,
                pricingVersionService,
                encryptionService
        );
    }

    // ========== Controller Tests ==========

    @Nested
    @DisplayName("DELETE /api/internal/credentials/by-integration/{integrationName}")
    class ControllerDeleteByIntegrationTests {

        @Test
        @DisplayName("should return correct count when credentials exist")
        void deleteByIntegration_returnsCorrectCount() {
            when(credentialService.deleteByIntegration("gmail")).thenReturn(3);

            ResponseEntity<Map<String, Object>> response = controller.deleteByIntegration("gmail");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("deleted", 3);
            assertThat(response.getBody()).containsEntry("integration", "gmail");
            verify(credentialService).deleteByIntegration("gmail");
        }

        @Test
        @DisplayName("should return 0 when no credentials match the integration")
        void deleteByIntegration_returnsZeroWhenNoMatches() {
            when(credentialService.deleteByIntegration("nonexistent")).thenReturn(0);

            ResponseEntity<Map<String, Object>> response = controller.deleteByIntegration("nonexistent");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("deleted", 0);
            assertThat(response.getBody()).containsEntry("integration", "nonexistent");
        }

        @Test
        @DisplayName("should return 400 for blank integration name")
        void deleteByIntegration_returnsBadRequestForBlank() {
            ResponseEntity<Map<String, Object>> response = controller.deleteByIntegration("   ");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsKey("error");
            verifyNoInteractions(credentialService);
        }
    }

    // ========== Tenant Platform Credential Tests ==========

    @Nested
    @DisplayName("POST /api/internal/credentials/platform-tenant")
    class TenantPlatformCredentialTests {

        @Test
        @DisplayName("should save tenant-scoped platform credential")
        void saveTenantCredential_succeeds() {
            var request = new CreatePlatformCredentialRequest(
                    "myapi", "My API", "oauth2",
                    "client123", "secret456", null,
                    null, null, "https://auth.example.com/authorize",
                    "https://auth.example.com/token", "read write",
                    null, null, null, null,
                    null, null
            );
            var mockResponse = new PlatformCredentialResponse(
                    1L, "myapi", "My API", "oauth2",
                    "clie****t123", true, false, false, false,
                    "https://auth.example.com/authorize", "https://auth.example.com/token",
                    "read write", null, null, null, true,
                    java.math.BigDecimal.ZERO, 0,
                    List.of(), Instant.now(), Instant.now(), "tenant-1"
            );
            when(platformCredentialService.saveCredential(request, "tenant-1", null)).thenReturn(mockResponse);

            ResponseEntity<?> response = controller.saveTenantPlatformCredential("tenant-1", null, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("success", true);
            assertThat(body).containsEntry("credentialId", 1L);
            verify(platformCredentialService).saveCredential(request, "tenant-1", null);
        }

        @Test
        @DisplayName("should reject blank tenant ID")
        void saveTenantCredential_rejectsBlankTenantId() {
            var request = new CreatePlatformCredentialRequest(
                    "myapi", "My API", "oauth2",
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null
            );

            ResponseEntity<?> response = controller.saveTenantPlatformCredential("", null, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("should reject blank integration name")
        void saveTenantCredential_rejectsBlankIntegrationName() {
            var request = new CreatePlatformCredentialRequest(
                    "", "My API", "oauth2",
                    null, null, null, null, null, null, null, null, null, null, null, null, null, null
            );

            ResponseEntity<?> response = controller.saveTenantPlatformCredential("tenant-1", null, request);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("DELETE /api/internal/credentials/platform-tenant/{name}")
    class DeleteTenantPlatformCredentialTests {

        @Test
        @DisplayName("should delete tenant-scoped credential")
        void deleteTenantCredential_succeeds() {
            when(platformCredentialService.deleteCredential("myapi", "tenant-1", null)).thenReturn(true);

            ResponseEntity<?> response = controller.deleteTenantPlatformCredential("tenant-1", null, "myapi");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(platformCredentialService).deleteCredential("myapi", "tenant-1", null);
        }

        @Test
        @DisplayName("should return 404 when credential not found")
        void deleteTenantCredential_notFound() {
            when(platformCredentialService.deleteCredential("nonexistent", "tenant-1", null)).thenReturn(false);

            ResponseEntity<?> response = controller.deleteTenantPlatformCredential("tenant-1", null, "nonexistent");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ========== Service Tests ==========

    @Nested
    @DisplayName("CredentialService.deleteByIntegration")
    class ServiceDeleteByIntegrationTests {

        private CredentialService service;

        @BeforeEach
        void setUp() {
            service = new CredentialService(
                    credentialRepository,
                    mock(org.springframework.data.redis.core.StringRedisTemplate.class));
        }

        @Test
        @DisplayName("should delegate to repository and return count")
        void deleteByIntegration_delegatesToRepo() {
            when(credentialRepository.deleteByIntegration("gmail")).thenReturn(5);

            int result = service.deleteByIntegration("gmail");

            assertThat(result).isEqualTo(5);
            verify(credentialRepository).deleteByIntegration("gmail");
        }

        @Test
        @DisplayName("should return 0 when no matches")
        void deleteByIntegration_returnsZero() {
            when(credentialRepository.deleteByIntegration("nonexistent")).thenReturn(0);

            int result = service.deleteByIntegration("nonexistent");

            assertThat(result).isEqualTo(0);
        }

        @Test
        @DisplayName("should throw for null integration")
        void deleteByIntegration_throwsForNull() {
            assertThatThrownBy(() -> service.deleteByIntegration(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("integration");
        }

        @Test
        @DisplayName("should throw for blank integration")
        void deleteByIntegration_throwsForBlank() {
            assertThatThrownBy(() -> service.deleteByIntegration("   "))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("integration");
        }

        @Test
        @DisplayName("should trim integration name before delegating")
        void deleteByIntegration_trimsInput() {
            when(credentialRepository.deleteByIntegration("gmail")).thenReturn(2);

            int result = service.deleteByIntegration("  gmail  ");

            assertThat(result).isEqualTo(2);
            verify(credentialRepository).deleteByIntegration("gmail");
        }
    }
}
