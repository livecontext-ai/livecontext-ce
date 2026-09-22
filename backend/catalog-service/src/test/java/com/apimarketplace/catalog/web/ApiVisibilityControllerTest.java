package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.service.ApiVisibilityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiVisibilityControllerTest {

    @Mock
    private ApiVisibilityService service;

    @InjectMocks
    private ApiVisibilityController controller;

    @Test
    @DisplayName("listIntegrations returns integrations wrapped in response")
    void listIntegrations() {
        List<Map<String, Object>> data = List.of(Map.of("apiName", "Slack"));
        when(service.listIntegrations()).thenReturn(data);

        ResponseEntity<?> response = controller.listIntegrations("ADMIN");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsKey("integrations");
    }

    @Test
    @DisplayName("toggleApi as ADMIN returns success on valid toggle")
    void toggleApi() {
        UUID apiId = UUID.randomUUID();
        doNothing().when(service).toggleApi(apiId, true);

        ResponseEntity<?> response = controller.toggleApi(apiId, true, "ADMIN");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(service).toggleApi(apiId, true);
    }

    @Test
    @DisplayName("toggleApi as a non-admin (USER) is rejected 403 and never reaches the service")
    void toggleApiNonAdminForbidden() {
        UUID apiId = UUID.randomUUID();

        ResponseEntity<?> response = controller.toggleApi(apiId, true, "USER");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("toggleApi returns 404 when API not found")
    void toggleApiNotFound() {
        UUID apiId = UUID.randomUUID();
        doThrow(new NoSuchElementException("API not found: " + apiId))
                .when(service).toggleApi(apiId, true);

        ResponseEntity<?> response = controller.toggleApi(apiId, true, "ADMIN");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("listApiTools returns tool list")
    void listApiTools() {
        UUID apiId = UUID.randomUUID();
        List<Map<String, Object>> tools = List.of(Map.of("toolName", "Create"));
        when(service.listApiTools(apiId)).thenReturn(tools);

        ResponseEntity<?> response = controller.listApiTools(apiId, "ADMIN");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(tools);
    }

    @Test
    @DisplayName("toggleTool as ADMIN returns success")
    void toggleTool() {
        UUID toolId = UUID.randomUUID();
        doNothing().when(service).toggleTool(toolId, false);

        ResponseEntity<?> response = controller.toggleTool(toolId, false, "ADMIN");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(service).toggleTool(toolId, false);
    }

    @Test
    @DisplayName("toggleTool as a non-admin (USER) is rejected 403 and never reaches the service")
    void toggleToolNonAdminForbidden() {
        UUID toolId = UUID.randomUUID();

        ResponseEntity<?> response = controller.toggleTool(toolId, false, "USER");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("listIntegrations as a non-admin (USER) is rejected 403 and never reaches the service")
    void listIntegrationsNonAdminForbidden() {
        // Regression: the two reads on this controller were ungated while the two writes
        // carried AdminRoleGuard. They enumerate the SAME global cross-tenant is_active
        // state, including rows hidden from every tenant, so any signed-in user could read
        // the catalogue's hidden inventory. In CE the monolith does not authenticate at the
        // filter, which made the same reads anonymous there.
        ResponseEntity<?> response = controller.listIntegrations("USER");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("listApiTools as a non-admin (USER) is rejected 403 and never reaches the service")
    void listApiToolsNonAdminForbidden() {
        ResponseEntity<?> response = controller.listApiTools(UUID.randomUUID(), "USER");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("toggleTool returns 404 when tool not found")
    void toggleToolNotFound() {
        UUID toolId = UUID.randomUUID();
        doThrow(new NoSuchElementException("Tool not found: " + toolId))
                .when(service).toggleTool(toolId, true);

        ResponseEntity<?> response = controller.toggleTool(toolId, true, "ADMIN");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
