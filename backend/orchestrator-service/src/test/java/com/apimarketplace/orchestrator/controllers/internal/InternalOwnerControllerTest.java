package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.services.access.OwnershipResolverRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("InternalOwnerController")
class InternalOwnerControllerTest {

    private OwnershipResolverRegistry registry;
    private InternalOwnerController controller;

    @BeforeEach
    void setUp() {
        registry = mock(OwnershipResolverRegistry.class);
        controller = new InternalOwnerController(registry);
    }

    @Test
    @DisplayName("Returns 200 + ownerOrgId payload when the resolver finds the org")
    void returns200WhenFound() {
        when(registry.resolveOwnerOrgId("workflow", "wf-1")).thenReturn(Optional.of("org-42"));

        ResponseEntity<Map<String, String>> response = controller.getOwner("workflow", "wf-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("ownerOrgId", "org-42");
    }

    @Test
    @DisplayName("Returns 404 when the resolver returns empty (not found, personal, or unknown type)")
    void returns404WhenEmpty() {
        when(registry.resolveOwnerOrgId("workflow", "missing")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, String>> response = controller.getOwner("workflow", "missing");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("Type and id are forwarded verbatim to the registry")
    void forwardsArgsVerbatim() {
        when(registry.resolveOwnerOrgId("agent", "a-1")).thenReturn(Optional.of("org-X"));

        controller.getOwner("agent", "a-1");

        verify(registry).resolveOwnerOrgId("agent", "a-1");
    }
}
