package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.service.ModelCatalogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ModelConfigController#getEffectiveModels} contract: it forwards the
 * caller's {@code X-User-ID} to {@link ModelCatalogService#getEffectiveModelList(String, String)}
 * so the admin Models panel filters providers by the SAME cloud-connect / BYOK
 * rule as the picker - and it stays admin-gated.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelConfigController - effective models")
class ModelConfigControllerTest {

    @Mock private ModelCatalogService service;
    @InjectMocks private ModelConfigController controller;

    @Test
    @DisplayName("admin call forwards the tenant (X-User-ID) and category to the service")
    void adminForwardsTenantAndCategory() {
        when(service.getEffectiveModelList("image_generation", "tenant-42"))
                .thenReturn(List.of(Map.of("id", "gpt-image-1.5")));

        ResponseEntity<?> response = controller.getEffectiveModels("ADMIN", "tenant-42", "image_generation");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).getEffectiveModelList("image_generation", "tenant-42");
    }

    @Test
    @DisplayName("absent X-User-ID forwards a null tenant (key-filter / cloud-prod default)")
    void absentTenantForwardsNull() {
        when(service.getEffectiveModelList(null, null)).thenReturn(List.of());

        ResponseEntity<?> response = controller.getEffectiveModels("ADMIN", null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).getEffectiveModelList(null, null);
    }

    @Test
    @DisplayName("non-admin is rejected with 403 and the service is never queried")
    void nonAdminForbidden() {
        ResponseEntity<?> response = controller.getEffectiveModels("USER", "tenant-42", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("invalid category is rejected (400) before the service is queried")
    void invalidCategoryRejected() {
        ResponseEntity<?> response = controller.getEffectiveModels("ADMIN", "tenant-42", "not a category");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(service);
    }
}
