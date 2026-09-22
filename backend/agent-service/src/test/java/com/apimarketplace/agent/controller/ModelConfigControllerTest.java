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
    @DisplayName("a refusal from the service reaches the browser as a 400 that SAYS why, not a bodyless 500")
    void aRefusalCarriesItsReason() {
        // The price guard names the model and what to do about it. Thrown from the service, it
        // used to land outside the controller's try and leave as a 500 with no body, which the
        // panel could only render as "Failed to save changes".
        when(service.saveOverride(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalArgumentException("Cannot enable zai:glm-5 - it has no price."));

        ResponseEntity<?> response = controller.saveOverride("ADMIN",
                Map.of("provider", "zai", "modelId", "glm-5", "enabled", true));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().toString()).contains("it has no price");
    }

    @Test
    @DisplayName("the disabled-provider list is admin-gated and relays the service answer")
    void disabledProvidersIsAdminGated() {
        when(service.disabledProviderNames()).thenReturn(List.of("openrouter"));

        assertThat(controller.listDisabledProviders("ADMIN").getBody()).isEqualTo(List.of("openrouter"));

        ResponseEntity<?> denied = controller.listDisabledProviders("USER");
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("the provider switch is admin-gated, needs the flag, and relays a refusal as a 400")
    void providerSwitchContract() {
        ResponseEntity<?> denied = controller.setProviderEnabled("USER", "openrouter", Map.of("enabled", false));
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(service);

        // The flag is the whole body: without it the request says nothing, and defaulting it
        // either way would silently do the opposite of what someone meant.
        ResponseEntity<?> missing = controller.setProviderEnabled("ADMIN", "openrouter", Map.of());
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        org.mockito.Mockito.doThrow(new IllegalArgumentException("Invalid provider name: Open Router!"))
                .when(service).setProviderEnabled("Open Router!", false);
        ResponseEntity<?> invalid =
                controller.setProviderEnabled("ADMIN", "Open Router!", Map.of("enabled", false));
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody().toString()).contains("Invalid provider");
    }

    @Test
    @DisplayName("an admin switching a provider off reaches the service with the provider and the flag")
    void providerSwitchForwardsTheCall() {
        ResponseEntity<?> response =
                controller.setProviderEnabled("ADMIN", "openrouter", Map.of("enabled", false));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(service).setProviderEnabled("openrouter", false);
    }

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
