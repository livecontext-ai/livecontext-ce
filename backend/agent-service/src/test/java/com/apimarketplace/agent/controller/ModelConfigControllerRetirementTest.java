package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.service.ModelCatalogService;
import com.apimarketplace.agent.service.ModelCatalogService.ModelRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The V533 retirement endpoints: admin-only, a validated body, counts in the answer, and a 409
 * (not a 500) when an admin tries to delete a retired model's row.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelConfigController - model retirement")
class ModelConfigControllerRetirementTest {

    @Mock private ModelCatalogService service;
    @InjectMocks private ModelConfigController controller;

    private static Map<String, Object> body(Object models) {
        return Map.of("models", models);
    }

    @Test
    @DisplayName("retire forwards every model and the admin id, and answers the count")
    void retireForwardsAndCounts() {
        when(service.retireModels(any(), any())).thenReturn(2);

        ResponseEntity<?> response = controller.retire("ADMIN", "7", body(List.of(
                Map.of("provider", "openai", "modelId", "gpt-4o"),
                Map.of("provider", "xai", "modelId", "grok-3-beta"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(Map.of("retired", 2));
        verify(service).retireModels(List.of(new ModelRef("openai", "gpt-4o"), new ModelRef("xai", "grok-3-beta")), "7");
    }

    @Test
    @DisplayName("a model named twice in one request is forwarded once")
    void duplicatesAreCollapsed() {
        when(service.retireModels(any(), any())).thenReturn(1);

        controller.retire("ADMIN", "7", body(List.of(
                Map.of("provider", "xai", "modelId", "grok-3-beta"),
                Map.of("provider", "xai", "modelId", "grok-3-beta"))));

        verify(service).retireModels(List.of(new ModelRef("xai", "grok-3-beta")), "7");
    }

    @Test
    @DisplayName("restore forwards every model and answers the count")
    void restoreForwardsAndCounts() {
        when(service.restoreModels(any())).thenReturn(1);

        ResponseEntity<?> response = controller.restore("ADMIN",
                body(List.of(Map.of("provider", "openai", "modelId", "gpt-4o"))));

        assertThat(response.getBody()).isEqualTo(Map.of("restored", 1));
        verify(service).restoreModels(List.of(new ModelRef("openai", "gpt-4o")));
    }

    @Test
    @DisplayName("every retirement endpoint is admin-only and touches nothing for a user")
    void adminOnly() {
        assertThat(controller.retire("USER", "7", body(List.of())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.restore("USER", body(List.of())).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(controller.listRetired("USER").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("a malformed body is a 400 that says what is wrong, and nothing is retired")
    void malformedBodies() {
        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 0; i <= ModelConfigController.MAX_MODELS_PER_REQUEST; i++) {
            tooMany.add(Map.of("provider", "openai", "modelId", "m" + i));
        }
        List<Map<String, Object>> bad = List.of(
                Map.of(),                                       // no models key
                body(List.of()),                                // empty
                body("gpt-4o"),                                 // not a list
                body(List.of("gpt-4o")),                        // item not an object
                body(List.of(Map.of("provider", "openai"))),    // modelId missing
                body(List.of(Map.of("provider", " ", "modelId", "x"))), // blank provider
                body(List.of(Map.of("provider", "p".repeat(51), "modelId", "x"))),  // wider than the column
                body(List.of(Map.of("provider", "openai", "modelId", "m".repeat(151)))),
                body(tooMany));
        for (Map<String, Object> b : bad) {
            ResponseEntity<?> response = controller.retire("ADMIN", "7", b);
            assertThat(response.getStatusCode()).as("body %s", b).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        verify(service, never()).retireModels(any(), any());
    }

    @Test
    @DisplayName("a refusal from the service (unknown model) is a 400 that says why")
    void serviceRefusalIsBadRequest() {
        when(service.retireModels(any(), any())).thenThrow(new IllegalArgumentException("Unknown model: openai:gtp-4o"));

        ResponseEntity<?> response = controller.retire("ADMIN", "7",
                body(List.of(Map.of("provider", "openai", "modelId", "gtp-4o"))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().toString()).contains("Unknown model");
    }

    @Test
    @DisplayName("deleting a retired model's row is a 409 with the reason, not a 500")
    void deleteRetiredIsConflict() {
        doThrow(new IllegalStateException("Model openai/gpt-4o is retired. Restore it before deleting its override."))
                .when(service).deleteOverride(anyString(), anyString());

        ResponseEntity<?> response = controller.deleteOverride("ADMIN", "openai", "gpt-4o");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().toString()).contains("is retired");
    }

    @Test
    @DisplayName("the retired list is relayed as the service returns it")
    void listRetired() {
        when(service.listRetiredModels()).thenReturn(List.of(Map.of("provider", "openai", "modelId", "gpt-4o")));

        assertThat(controller.listRetired("ADMIN").getBody())
                .isEqualTo(List.of(Map.of("provider", "openai", "modelId", "gpt-4o")));
    }
}
