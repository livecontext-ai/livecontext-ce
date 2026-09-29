package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.service.SharedLinkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Regression for the share-link write gap: create / update / delete / regenerate-token
 * were scoped to the workspace but never checked the ROLE, so a read-only VIEWER could
 * publish a workspace resource to anyone, revoke the owners' links, or re-key them.
 */
@DisplayName("SharedLinkController - writes refuse the workspace VIEWER role")
class SharedLinkControllerViewerGateTest {

    private static final String TENANT = "tenant-s";
    private static final String ORG = "org-s";
    private static final UUID LINK_ID = UUID.fromString("7d3c0f4e-2b1a-4e6f-9c8d-5a4b3c2d1e0f");

    private SharedLinkService sharedLinkService;
    private SharedLinkController controller;

    @BeforeEach
    void setUp() {
        sharedLinkService = mock(SharedLinkService.class);
        controller = new SharedLinkController(sharedLinkService);
    }

    private static void assertForbidden(ResponseEntity<?> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "VIEWER role cannot modify shared links"));
    }

    @Test
    @DisplayName("create, update, delete and regenerate-token by a VIEWER are 403 and touch nothing")
    void viewerWritesRefused() {
        assertForbidden(controller.create(TENANT, ORG, "VIEWER", "PRO",
                Map.of("resourceType", "APPLICATION", "resourceToken", "tok")));
        assertForbidden(controller.update(TENANT, ORG, "viewer", LINK_ID, Map.of("title", "x")));
        assertForbidden(controller.delete(TENANT, ORG, "VIEWER", LINK_ID));
        assertForbidden(controller.regenerateToken(TENANT, ORG, "VIEWER", LINK_ID));
        verifyNoInteractions(sharedLinkService);
    }

    @Test
    @DisplayName("a MEMBER still deletes and re-keys: the gate is a no-op for writers")
    void memberWritesProceed() {
        assertThat(controller.delete(TENANT, ORG, "MEMBER", LINK_ID).getStatusCode().value()).isEqualTo(200);
        controller.regenerateToken(TENANT, ORG, "MEMBER", LINK_ID);

        verify(sharedLinkService).delete(TENANT, ORG, LINK_ID);
        verify(sharedLinkService).regenerateToken(TENANT, ORG, LINK_ID);
    }

    @Test
    @DisplayName("a VIEWER role without a workspace is not a workspace role: delete proceeds")
    void viewerWithoutOrgProceeds() {
        assertThat(controller.delete(TENANT, null, "VIEWER", LINK_ID).getStatusCode().value()).isEqualTo(200);
        verify(sharedLinkService).delete(TENANT, null, LINK_ID);
    }
}
