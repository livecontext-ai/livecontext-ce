package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.service.SharedLinkService;
import com.apimarketplace.publication.service.SharedLinkService.SharedLinkResolution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicShareResolverController")
class PublicShareResolverControllerTest {

    @Mock
    private SharedLinkService sharedLinkService;

    private PublicShareResolverController controller;

    /** Generate a valid sl_ token (matches ^sl_[a-f0-9]{32}$) */
    private static String validToken() {
        return "sl_" + UUID.randomUUID().toString().replace("-", "");
    }

    @BeforeEach
    void setUp() {
        controller = new PublicShareResolverController(sharedLinkService);
    }

    @Nested
    @DisplayName("resolve")
    class Resolve {

        @Test
        @DisplayName("returns 200 with resolution for valid active token")
        void returnsResolutionForValidToken() {
            String token = validToken();
            SharedLinkResolution resolution = new SharedLinkResolution(
                    token, "CHAT", "ch_xyz789", "My Chat", "Description",
                    true, false, null);
            when(sharedLinkService.resolve(token)).thenReturn(Optional.of(resolution));

            ResponseEntity<?> response = controller.resolve(token);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isInstanceOf(SharedLinkResolution.class);
            SharedLinkResolution body = (SharedLinkResolution) response.getBody();
            assertThat(body.token()).isEqualTo(token);
            assertThat(body.resourceType()).isEqualTo("CHAT");
            assertThat(body.resourceToken()).isEqualTo("ch_xyz789");
            assertThat(body.title()).isEqualTo("My Chat");
            assertThat(body.isActive()).isTrue();
            assertThat(body.hasPassword()).isFalse();

            verify(sharedLinkService).resolve(token);
        }

        @Test
        @DisplayName("returns 404 for token with invalid format (no DB query)")
        void returns404ForInvalidFormat() {
            ResponseEntity<?> response = controller.resolve("unknown");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNull();
            verify(sharedLinkService, never()).resolve(any());
        }

        @Test
        @DisplayName("returns 404 for inactive/expired token (service returns empty)")
        void returns404ForInactiveToken() {
            String token = validToken();
            when(sharedLinkService.resolve(token)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.resolve(token);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("returns 500 on service exception")
        void returns500OnException() {
            String token = validToken();
            when(sharedLinkService.resolve(token))
                    .thenThrow(new RuntimeException("DB connection failed"));

            ResponseEntity<?> response = controller.resolve(token);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("error", "Failed to resolve share token");
        }

        @Test
        @DisplayName("resolves FORM type token")
        void resolvesFormToken() {
            String token = validToken();
            SharedLinkResolution resolution = new SharedLinkResolution(
                    token, "FORM", "fm_abc", "My Form", null,
                    true, false, null);
            when(sharedLinkService.resolve(token)).thenReturn(Optional.of(resolution));

            ResponseEntity<?> response = controller.resolve(token);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            SharedLinkResolution body = (SharedLinkResolution) response.getBody();
            assertThat(body.resourceType()).isEqualTo("FORM");
            assertThat(body.resourceToken()).isEqualTo("fm_abc");
        }

        @Test
        @DisplayName("resolves password-protected link")
        void resolvesPasswordProtectedLink() {
            String token = validToken();
            SharedLinkResolution resolution = new SharedLinkResolution(
                    token, "CHAT", "ch_secret", "Protected Chat", null,
                    true, true, null);
            when(sharedLinkService.resolve(token)).thenReturn(Optional.of(resolution));

            ResponseEntity<?> response = controller.resolve(token);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            SharedLinkResolution body = (SharedLinkResolution) response.getBody();
            assertThat(body.hasPassword()).isTrue();
            assertThat(body.metadata()).isNull(); // Metadata never exposed publicly
        }

        @Test
        @DisplayName("rejects tokens with SQL injection attempts")
        void rejectsSqlInjection() {
            ResponseEntity<?> response = controller.resolve("sl_'; DROP TABLE shared_links;--");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(sharedLinkService, never()).resolve(any());
        }

        @Test
        @DisplayName("rejects tokens with wrong prefix")
        void rejectsWrongPrefix() {
            ResponseEntity<?> response = controller.resolve("xx_" + "a".repeat(32));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            verify(sharedLinkService, never()).resolve(any());
        }
    }
}
