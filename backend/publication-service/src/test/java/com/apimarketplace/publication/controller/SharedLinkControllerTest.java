package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.domain.SharedLinkEntity;
import com.apimarketplace.publication.domain.SharedLinkEntity.ResourceType;
import com.apimarketplace.publication.dto.SharedLinkCheckResponse;
import com.apimarketplace.publication.dto.SharedLinkConfigResponse;
import com.apimarketplace.publication.dto.SharedLinkResponse;
import com.apimarketplace.publication.service.SharedLinkService;
import com.apimarketplace.publication.service.SharedLinkService.SharedLinkLimitException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SharedLinkController")
class SharedLinkControllerTest {

    @Mock
    private SharedLinkService sharedLinkService;

    private SharedLinkController controller;

    private static final String TENANT_ID = "tenant|001";

    @BeforeEach
    void setUp() {
        controller = new SharedLinkController(sharedLinkService);
    }

    // ──────────────── getConfig ────────────────

    @Nested
    @DisplayName("getConfig")
    class GetConfig {

        @Test
        @DisplayName("returns config with max and current count")
        void returnsConfig() {
            when(sharedLinkService.getConfig(TENANT_ID, null, "PRO", null))
                    .thenReturn(new SharedLinkConfigResponse(50, 3));

            ResponseEntity<SharedLinkConfigResponse> response = controller.getConfig(TENANT_ID, null, "PRO", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().maxPerUser()).isEqualTo(50);
            assertThat(response.getBody().currentCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("handles null plan header")
        void handlesNullPlan() {
            when(sharedLinkService.getConfig(TENANT_ID, null, null, null))
                    .thenReturn(new SharedLinkConfigResponse(10, 0));

            ResponseEntity<SharedLinkConfigResponse> response = controller.getConfig(TENANT_ID, null, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().maxPerUser()).isEqualTo(10);
        }
    }

    // ──────────────── check ────────────────

    @Nested
    @DisplayName("check")
    class Check {

        @Test
        @DisplayName("returns existing link + config in single call")
        void returnsExistingLink() {
            SharedLinkEntity entity = buildEntity("ch_1", ResourceType.CHAT);
            SharedLinkCheckResponse checkResult = new SharedLinkCheckResponse(
                    SharedLinkResponse.from(entity),
                    new SharedLinkConfigResponse(50, 3));
            when(sharedLinkService.checkLink(TENANT_ID, null, "ch_1", null, "PRO"))
                    .thenReturn(checkResult);

            ResponseEntity<SharedLinkCheckResponse> response = controller.check(TENANT_ID, null, "PRO", "ch_1", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().link()).isNotNull();
            assertThat(response.getBody().link().resourceToken()).isEqualTo("ch_1");
            assertThat(response.getBody().config().maxPerUser()).isEqualTo(50);
        }

        @Test
        @DisplayName("returns null link + config when no existing link")
        void returnsNullLinkWhenNone() {
            SharedLinkCheckResponse checkResult = new SharedLinkCheckResponse(
                    null, new SharedLinkConfigResponse(10, 0));
            when(sharedLinkService.checkLink(TENANT_ID, null, "ch_new", null, "FREE"))
                    .thenReturn(checkResult);

            ResponseEntity<SharedLinkCheckResponse> response = controller.check(TENANT_ID, null, "FREE", "ch_new", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().link()).isNull();
            assertThat(response.getBody().config().currentCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("passes resourceId for conversation fallback lookup")
        void passesResourceIdForConversation() {
            UUID convId = UUID.randomUUID();
            SharedLinkCheckResponse checkResult = new SharedLinkCheckResponse(
                    null, new SharedLinkConfigResponse(50, 2));
            when(sharedLinkService.checkLink(TENANT_ID, null, "conv-token", convId, "PRO"))
                    .thenReturn(checkResult);

            ResponseEntity<SharedLinkCheckResponse> response = controller.check(TENANT_ID, null, "PRO", "conv-token", convId.toString());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(sharedLinkService).checkLink(TENANT_ID, null, "conv-token", convId, "PRO");
        }
    }

    // ──────────────── listAll ────────────────

    @Nested
    @DisplayName("listAll")
    class ListAll {

        @Test
        @DisplayName("returns DTO list for tenant when no type filter")
        void returnsAllLinks() {
            List<SharedLinkEntity> links = List.of(
                    buildEntity("ch_1", ResourceType.CHAT),
                    buildEntity("fm_1", ResourceType.FORM));
            when(sharedLinkService.getByScope(TENANT_ID, null)).thenReturn(links);

            ResponseEntity<List<SharedLinkResponse>> response = controller.listAll(TENANT_ID, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(2);
            // Verify response is DTO (has no tenantId field)
            SharedLinkResponse first = response.getBody().get(0);
            assertThat(first.resourceType()).isEqualTo("CHAT");
            verify(sharedLinkService).getByScope(TENANT_ID, null);
            verify(sharedLinkService, never()).getByScopeAndType(any(), any(), any());
        }

        @Test
        @DisplayName("filters by resource type when provided")
        void filtersByType() {
            List<SharedLinkEntity> links = List.of(buildEntity("ch_1", ResourceType.CHAT));
            when(sharedLinkService.getByScopeAndType(TENANT_ID, null, ResourceType.CHAT)).thenReturn(links);

            ResponseEntity<List<SharedLinkResponse>> response = controller.listAll(TENANT_ID, null, "CHAT");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).hasSize(1);
            verify(sharedLinkService).getByScopeAndType(TENANT_ID, null, ResourceType.CHAT);
        }

        @Test
        @DisplayName("handles case-insensitive type filter")
        void caseInsensitiveFilter() {
            when(sharedLinkService.getByScopeAndType(TENANT_ID, null, ResourceType.FORM)).thenReturn(List.of());

            ResponseEntity<List<SharedLinkResponse>> response = controller.listAll(TENANT_ID, null, "form");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(sharedLinkService).getByScopeAndType(TENANT_ID, null, ResourceType.FORM);
        }

        @Test
        @DisplayName("returns all links when type filter is blank")
        void returnsAllWhenBlankFilter() {
            when(sharedLinkService.getByScope(TENANT_ID, null)).thenReturn(List.of());

            controller.listAll(TENANT_ID, null, "  ");

            verify(sharedLinkService).getByScope(TENANT_ID, null);
        }

        @Test
        @DisplayName("returns 400 for invalid resource type")
        void returns400ForInvalidResourceType() {
            ResponseEntity<List<SharedLinkResponse>> response = controller.listAll(TENANT_ID, null, "INVALID_TYPE");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(sharedLinkService, never()).getByScopeAndType(any(), any(), any());
            verify(sharedLinkService, never()).getByScope(any(), any());
        }
    }

    // ──────────────── getById ────────────────

    @Nested
    @DisplayName("getById")
    class GetById {

        @Test
        @DisplayName("returns DTO when found and owned by tenant")
        void returnsLinkWhenFound() {
            UUID linkId = UUID.randomUUID();
            SharedLinkEntity entity = buildEntity("ch_1", ResourceType.CHAT);
            entity.setId(linkId);
            when(sharedLinkService.getByIdAndScope(linkId, TENANT_ID, null)).thenReturn(Optional.of(entity));

            ResponseEntity<?> response = controller.getById(TENANT_ID, null, linkId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isInstanceOf(SharedLinkResponse.class);
        }

        @Test
        @DisplayName("returns 404 when link not found")
        void returns404WhenNotFound() {
            UUID linkId = UUID.randomUUID();
            when(sharedLinkService.getByIdAndScope(linkId, TENANT_ID, null)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.getById(TENANT_ID, null, linkId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("returns 500 on service exception")
        void returns500OnException() {
            UUID linkId = UUID.randomUUID();
            when(sharedLinkService.getByIdAndScope(linkId, TENANT_ID, null))
                    .thenThrow(new RuntimeException("DB error"));

            ResponseEntity<?> response = controller.getById(TENANT_ID, null, linkId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ──────────────── create ────────────────

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("creates new shared link successfully and returns DTO")
        void createsSuccessfully() {
            SharedLinkEntity entity = buildEntity("ch_1", ResourceType.CHAT);
            when(sharedLinkService.register(eq(TENANT_ID), eq((String) null), eq("PRO"), eq("CHAT"), eq("ch_1"),
                    any(), eq("My Chat"), eq("Description")))
                    .thenReturn(entity);

            Map<String, Object> body = Map.of(
                    "resourceType", "CHAT",
                    "resourceToken", "ch_1",
                    "title", "My Chat",
                    "description", "Description");

            ResponseEntity<?> response = controller.create(TENANT_ID, null, "PRO", body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isInstanceOf(SharedLinkResponse.class);
        }

        @Test
        @DisplayName("returns 403 when at shared link limit")
        void returns403AtLimit() {
            when(sharedLinkService.register(eq(TENANT_ID), eq((String) null), eq("FREE"), eq("CHAT"), eq("ch_1"),
                    any(), any(), any()))
                    .thenThrow(new SharedLinkLimitException(5, 5));

            Map<String, Object> body = Map.of(
                    "resourceType", "CHAT",
                    "resourceToken", "ch_1");

            ResponseEntity<?> response = controller.create(TENANT_ID, null, "FREE", body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            @SuppressWarnings("unchecked")
            Map<String, String> responseBody = (Map<String, String>) response.getBody();
            // Error message should be generic, not leak count/max
            assertThat(responseBody.get("error")).isEqualTo("Shared link limit reached");
        }

        @Test
        @DisplayName("returns 400 when resourceType is missing")
        void returns400WhenMissingResourceType() {
            Map<String, Object> body = Map.of("resourceToken", "ch_1");

            ResponseEntity<?> response = controller.create(TENANT_ID, null, "PRO", body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(sharedLinkService, never()).register(any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("returns 400 when resourceToken is missing")
        void returns400WhenMissingResourceToken() {
            Map<String, Object> body = Map.of("resourceType", "CHAT");

            ResponseEntity<?> response = controller.create(TENANT_ID, null, "PRO", body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(sharedLinkService, never()).register(any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("returns 400 when title exceeds max length")
        void returns400WhenTitleTooLong() {
            Map<String, Object> body = Map.of(
                    "resourceType", "CHAT",
                    "resourceToken", "ch_1",
                    "title", "x".repeat(257));

            ResponseEntity<?> response = controller.create(TENANT_ID, null, "PRO", body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(sharedLinkService, never()).register(any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("returns 500 on service exception")
        void returns500OnException() {
            when(sharedLinkService.register(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("DB error"));

            Map<String, Object> body = Map.of(
                    "resourceType", "CHAT",
                    "resourceToken", "ch_1");

            ResponseEntity<?> response = controller.create(TENANT_ID, null, "PRO", body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ──────────────── update ────────────────

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("updates link successfully and returns DTO")
        void updatesSuccessfully() {
            UUID linkId = UUID.randomUUID();
            SharedLinkEntity updated = buildEntity("ch_1", ResourceType.CHAT);
            updated.setTitle("Updated Title");
            when(sharedLinkService.update(eq(TENANT_ID), eq((String) null), eq(linkId), eq("Updated Title"),
                    eq("New Desc"), any(), eq(true))).thenReturn(updated);

            Map<String, Object> body = Map.of(
                    "title", "Updated Title",
                    "description", "New Desc",
                    "isActive", true);

            ResponseEntity<?> response = controller.update(TENANT_ID, null, linkId, body);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isInstanceOf(SharedLinkResponse.class);
        }

        @Test
        @DisplayName("returns 404 on ownership violation (no existence leak)")
        void returns404OnOwnershipViolation() {
            UUID linkId = UUID.randomUUID();
            when(sharedLinkService.update(eq(TENANT_ID), eq((String) null), eq(linkId), any(), any(), any(), any()))
                    .thenThrow(new IllegalArgumentException("Not authorized"));

            ResponseEntity<?> response = controller.update(TENANT_ID, null, linkId, Map.of("title", "x"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("returns 500 on unexpected exception")
        void returns500OnException() {
            UUID linkId = UUID.randomUUID();
            when(sharedLinkService.update(eq(TENANT_ID), eq((String) null), eq(linkId), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("DB error"));

            ResponseEntity<?> response = controller.update(TENANT_ID, null, linkId, Map.of("title", "x"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ──────────────── delete ────────────────

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("deletes link successfully")
        void deletesSuccessfully() {
            UUID linkId = UUID.randomUUID();
            doNothing().when(sharedLinkService).delete(TENANT_ID, null, linkId);

            ResponseEntity<?> response = controller.delete(TENANT_ID, null, linkId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(sharedLinkService).delete(TENANT_ID, null, linkId);
        }

        @Test
        @DisplayName("returns 404 on ownership violation (no existence leak)")
        void returns404OnOwnershipViolation() {
            UUID linkId = UUID.randomUUID();
            doThrow(new IllegalArgumentException("Not authorized"))
                    .when(sharedLinkService).delete(TENANT_ID, null, linkId);

            ResponseEntity<?> response = controller.delete(TENANT_ID, null, linkId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("returns 500 on unexpected exception")
        void returns500OnException() {
            UUID linkId = UUID.randomUUID();
            doThrow(new RuntimeException("DB error"))
                    .when(sharedLinkService).delete(TENANT_ID, null, linkId);

            ResponseEntity<?> response = controller.delete(TENANT_ID, null, linkId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    // ──────────────── regenerateToken ────────────────

    @Nested
    @DisplayName("regenerateToken")
    class RegenerateToken {

        @Test
        @DisplayName("regenerates token successfully and returns DTO")
        void regeneratesSuccessfully() {
            UUID linkId = UUID.randomUUID();
            SharedLinkEntity updated = buildEntity("ch_1", ResourceType.CHAT);
            when(sharedLinkService.regenerateToken(TENANT_ID, null, linkId)).thenReturn(updated);

            ResponseEntity<?> response = controller.regenerateToken(TENANT_ID, null, linkId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isInstanceOf(SharedLinkResponse.class);
        }

        @Test
        @DisplayName("returns 404 on ownership violation (no existence leak)")
        void returns404OnOwnershipViolation() {
            UUID linkId = UUID.randomUUID();
            when(sharedLinkService.regenerateToken(TENANT_ID, null, linkId))
                    .thenThrow(new IllegalArgumentException("Not authorized"));

            ResponseEntity<?> response = controller.regenerateToken(TENANT_ID, null, linkId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("returns 500 on unexpected exception")
        void returns500OnException() {
            UUID linkId = UUID.randomUUID();
            when(sharedLinkService.regenerateToken(TENANT_ID, null, linkId))
                    .thenThrow(new RuntimeException("DB error"));

            ResponseEntity<?> response = controller.regenerateToken(TENANT_ID, null, linkId);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
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
        return entity;
    }
}
