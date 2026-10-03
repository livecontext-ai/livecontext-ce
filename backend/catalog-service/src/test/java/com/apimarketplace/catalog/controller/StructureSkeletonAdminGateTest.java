package com.apimarketplace.catalog.controller;

import com.apimarketplace.catalog.service.StructureSkeletonService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two writing handlers of the structure controller are admin-only (LC-019 sweep,
 * security audit 2026-08-13).
 *
 * <p>A skeleton in {@code catalog.tool_responses} is global, untenanted catalog state: it
 * is what every tenant's mapping picker and every agent's response-schema lookup read.
 * {@code POST /migrate} and {@code POST /{id}/regenerate} rewrote it for anyone who could
 * reach the route. The two GET siblings only read it and stay open, which is what the last
 * test pins.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StructureSkeletonController - admin gate on the write handlers (LC-019)")
class StructureSkeletonAdminGateTest {

    @Mock private StructureSkeletonService service;

    private StructureSkeletonController controller;

    private static final UUID RESPONSE_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @BeforeEach
    void setUp() {
        controller = new StructureSkeletonController(service, new com.apimarketplace.catalog.web.CatalogAdminAccess(""));
    }

    @Test
    @DisplayName("a plain user cannot trigger the skeleton migration batch")
    void plainUserCannotMigrate() {
        ResponseEntity<?> response = controller.triggerMigration(100, "USER", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, never()).runMigrationBatch(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    @DisplayName("an ADMIN still triggers it")
    void adminCanMigrate() {
        when(service.runMigrationBatch(100)).thenReturn(7);

        ResponseEntity<?> response = controller.triggerMigration(100, "USER,ADMIN", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(response.getBody())).contains("7");
    }

    @Test
    @DisplayName("a plain user cannot regenerate a response's skeleton")
    void plainUserCannotRegenerate() {
        ResponseEntity<?> response = controller.regenerateSkeleton(RESPONSE_ID, "USER", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(service, never()).generateAndSaveSkeleton(any());
    }

    @Test
    @DisplayName("an ADMIN still regenerates it")
    void adminCanRegenerate() {
        ResponseEntity<?> response = controller.regenerateSkeleton(RESPONSE_ID, "ADMIN", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).generateAndSaveSkeleton(RESPONSE_ID);
    }

    @Test
    @DisplayName("the read handlers stay open - the gate is on writes only")
    void readsAreUnaffected() {
        // Anti-vacuity: these back the workflow builder's mapping picker for every user.
        when(service.getRootStructure(RESPONSE_ID)).thenReturn(List.of());

        assertThat(controller.getRootStructure(RESPONSE_ID).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
