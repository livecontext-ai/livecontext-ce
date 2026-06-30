package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.domain.SharedLinkEntity;
import com.apimarketplace.publication.domain.SharedLinkEntity.ResourceType;
import com.apimarketplace.publication.service.SharedLinkService;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalSharedLinkController")
class InternalSharedLinkControllerTest {

    @Mock
    private SharedLinkService sharedLinkService;

    private InternalSharedLinkController controller;

    private static final String TENANT_ID = "tenant|001";
    private static final String ORG_ID = "org-001";
    private static final String RESOURCE_TOKEN = "ch_abc123";
    private static final UUID RESOURCE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new InternalSharedLinkController(sharedLinkService);
    }

    // ──────────────── register ────────────────

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("registers new shared link and returns token info")
        void registersNewLink() {
            SharedLinkEntity entity = buildEntity(RESOURCE_TOKEN, ResourceType.CHAT);
            when(sharedLinkService.register(eq(TENANT_ID), isNull(), isNull(), eq("CHAT"), eq(RESOURCE_TOKEN),
                    eq(RESOURCE_ID), eq("My Chat"), eq("Description")))
                    .thenReturn(entity);

            Map<String, Object> body = new HashMap<>();
            body.put("tenantId", TENANT_ID);
            body.put("resourceType", "CHAT");
            body.put("resourceToken", RESOURCE_TOKEN);
            body.put("resourceId", RESOURCE_ID.toString());
            body.put("title", "My Chat");
            body.put("description", "Description");

            ResponseEntity<?> response = controller.register(null, body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getBody();
            assertThat(result).containsKey("id");
            assertThat(result).containsKey("token");
            assertThat(result).containsEntry("resourceType", "CHAT");
            assertThat(result).containsEntry("resourceToken", RESOURCE_TOKEN);
        }

        @Test
        @DisplayName("registers link without optional resourceId")
        void registersWithoutResourceId() {
            SharedLinkEntity entity = buildEntity(RESOURCE_TOKEN, ResourceType.FORM);
            when(sharedLinkService.register(eq(TENANT_ID), isNull(), isNull(), eq("FORM"), eq(RESOURCE_TOKEN),
                    isNull(), eq("My Form"), isNull()))
                    .thenReturn(entity);

            Map<String, Object> body = new HashMap<>();
            body.put("tenantId", TENANT_ID);
            body.put("resourceType", "FORM");
            body.put("resourceToken", RESOURCE_TOKEN);
            body.put("title", "My Form");

            ResponseEntity<?> response = controller.register(null, body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("returns 400 when required fields are missing")
        void returns400WhenMissingFields() {
            Map<String, Object> body = new HashMap<>();
            body.put("tenantId", TENANT_ID);
            // Missing resourceType and resourceToken

            ResponseEntity<?> response = controller.register(null, body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(sharedLinkService, never()).register(any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("returns 400 when tenantId is missing")
        void returns400WhenTenantIdMissing() {
            Map<String, Object> body = new HashMap<>();
            body.put("resourceType", "CHAT");
            body.put("resourceToken", RESOURCE_TOKEN);

            ResponseEntity<?> response = controller.register(null, body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("returns 500 on service exception")
        void returns500OnException() {
            when(sharedLinkService.register(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("DB error"));

            Map<String, Object> body = new HashMap<>();
            body.put("tenantId", TENANT_ID);
            body.put("resourceType", "CHAT");
            body.put("resourceToken", RESOURCE_TOKEN);

            ResponseEntity<?> response = controller.register(null, body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }

        @Test
        @DisplayName("handles idempotent registration (returns existing)")
        void handlesIdempotentRegistration() {
            SharedLinkEntity existing = buildEntity(RESOURCE_TOKEN, ResourceType.CHAT);
            // Service returns existing entity on duplicate registration
            when(sharedLinkService.register(any(), any(), any(), any(), eq(RESOURCE_TOKEN), any(), any(), any()))
                    .thenReturn(existing);

            Map<String, Object> body = new HashMap<>();
            body.put("tenantId", TENANT_ID);
            body.put("resourceType", "CHAT");
            body.put("resourceToken", RESOURCE_TOKEN);

            ResponseEntity<?> response = controller.register(null, body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getBody();
            assertThat(result.get("token")).isEqualTo(existing.getToken());
        }
    }

    // ──────────────── unregister ────────────────

    @Nested
    @DisplayName("unregister")
    class Unregister {

        @Test
        @DisplayName("unregisters shared link by resource token")
        void unregistersSuccessfully() {
            ResponseEntity<Void> response = controller.unregister(Map.of("resourceToken", RESOURCE_TOKEN));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(sharedLinkService).unregister(RESOURCE_TOKEN);
        }

        @Test
        @DisplayName("returns 400 when resourceToken is missing")
        void returns400WhenMissing() {
            ResponseEntity<Void> response = controller.unregister(Map.of());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(sharedLinkService, never()).unregister(any());
        }

        @Test
        @DisplayName("returns 400 when resourceToken is blank")
        void returns400WhenBlank() {
            ResponseEntity<Void> response = controller.unregister(Map.of("resourceToken", "  "));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(sharedLinkService, never()).unregister(any());
        }
    }

    // ──────────────── getByResourceToken ────────────────

    @Nested
    @DisplayName("validate")
    class Validate {

        @Test
        @DisplayName("returns owner and resource scope for gateway ShareToken forwarding")
        void returnsOwnerAndResourceScope() {
            SharedLinkEntity entity = buildEntity("publication-123", ResourceType.APPLICATION);
            entity.setOrganizationId(ORG_ID);
            entity.setResourceId(RESOURCE_ID);
            when(sharedLinkService.getByToken(entity.getToken())).thenReturn(Optional.of(entity));

            ResponseEntity<?> response = controller.validate(entity.getToken());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getBody();
            assertThat(result).containsEntry("userId", TENANT_ID);
            assertThat(result).containsEntry("organizationId", ORG_ID);
            assertThat(result).containsEntry("resourceType", "APPLICATION");
            assertThat(result).containsEntry("resourceToken", "publication-123");
            assertThat(result).containsEntry("resourceId", RESOURCE_ID.toString());
        }

        @Test
        @DisplayName("returns 404 when share token is unknown")
        void returns404WhenUnknown() {
            when(sharedLinkService.getByToken("sl_unknown")).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.validate("sl_unknown");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("getByResourceToken")
    class GetByResourceToken {

        @Test
        @DisplayName("returns link info when found")
        void returnsLinkWhenFound() {
            SharedLinkEntity entity = buildEntity(RESOURCE_TOKEN, ResourceType.CHAT);
            entity.setTitle("My Chat");
            entity.setCreatedAt(Instant.now());
            when(sharedLinkService.getByResourceToken(RESOURCE_TOKEN))
                    .thenReturn(Optional.of(entity));

            ResponseEntity<?> response = controller.getByResourceToken(RESOURCE_TOKEN);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getBody();
            assertThat(result).containsEntry("resourceType", "CHAT");
            assertThat(result).containsEntry("resourceToken", RESOURCE_TOKEN);
            assertThat(result).containsEntry("title", "My Chat");
            assertThat(result).containsEntry("isActive", true);
            assertThat(result).containsKey("accessCount");
            assertThat(result).containsKey("createdAt");
        }

        @Test
        @DisplayName("returns 404 when not found")
        void returns404WhenNotFound() {
            when(sharedLinkService.getByResourceToken("unknown"))
                    .thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.getByResourceToken("unknown");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("returns link info for FORM type")
        void returnsFormLink() {
            SharedLinkEntity entity = buildEntity("fm_xyz", ResourceType.FORM);
            entity.setTitle("My Form");
            entity.setCreatedAt(Instant.now());
            when(sharedLinkService.getByResourceToken("fm_xyz"))
                    .thenReturn(Optional.of(entity));

            ResponseEntity<?> response = controller.getByResourceToken("fm_xyz");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) response.getBody();
            assertThat(result).containsEntry("resourceType", "FORM");
        }
    }

    // ──────────────── helpers ────────────────

    private SharedLinkEntity buildEntity(String resourceToken, ResourceType type) {
        SharedLinkEntity entity = new SharedLinkEntity();
        entity.setId(UUID.randomUUID());
        entity.setToken("sl_" + UUID.randomUUID().toString().replace("-", ""));
        entity.setResourceType(type);
        entity.setResourceToken(resourceToken);
        entity.setTenantId(TENANT_ID);
        entity.setActive(true);
        entity.setAccessCount(0L);
        entity.setCreatedAt(Instant.now());
        return entity;
    }
}
