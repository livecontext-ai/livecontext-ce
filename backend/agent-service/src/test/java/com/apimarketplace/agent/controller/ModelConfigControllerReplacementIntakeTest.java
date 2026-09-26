package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.service.ModelCatalogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The replacement a disabled model's runs execute on (V515), at the boundary it enters
 * through. Same three JSON cases as the other explicit-set fields: absent (leave alone),
 * set, and cleared with nulls (back to the platform default).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelConfigController.saveOverride - the replacement pair")
class ModelConfigControllerReplacementIntakeTest {

    @Mock private ModelCatalogService service;
    @InjectMocks private ModelConfigController controller;

    private Map<String, Object> body() {
        Map<String, Object> body = new HashMap<>();
        body.put("provider", "anthropic");
        body.put("modelId", "claude-opus-4-8");
        return body;
    }

    private ModelConfigOverrideEntity save(Map<String, Object> body) {
        when(service.saveOverride(any())).thenAnswer(inv -> {
            ModelConfigOverrideEntity passed = inv.getArgument(0);
            passed.setId(7L);
            return passed;
        });
        ResponseEntity<?> response = controller.saveOverride("ADMIN", body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<ModelConfigOverrideEntity> captor = ArgumentCaptor.forClass(ModelConfigOverrideEntity.class);
        verify(service).saveOverride(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a pair is passed through and marked explicitly set")
    void pairPassedThrough() {
        Map<String, Object> body = body();
        body.put("replacementProvider", "anthropic");
        body.put("replacementModel", "claude-opus-4-9");

        ModelConfigOverrideEntity passed = save(body);

        assertThat(passed.getReplacementProvider()).isEqualTo("anthropic");
        assertThat(passed.getReplacementModel()).isEqualTo("claude-opus-4-9");
        assertThat(passed.isReplacementExplicitlySet()).isTrue();
    }

    @Test
    @DisplayName("explicit nulls are a clear, not an absent key")
    void nullsClear() {
        Map<String, Object> body = body();
        body.put("replacementProvider", null);
        body.put("replacementModel", null);

        ModelConfigOverrideEntity passed = save(body);

        assertThat(passed.isReplacementExplicitlySet()).isTrue();
        assertThat(passed.getReplacementModel()).isNull();
    }

    @Test
    @DisplayName("absent keys leave the flag off, so an unrelated edit keeps the stored replacement")
    void absentKeysNotSet() {
        Map<String, Object> body = body();
        body.put("tier", "top");

        assertThat(save(body).isReplacementExplicitlySet()).isFalse();
    }

    @Test
    @DisplayName("a non-string value is a 400, never a 500")
    void nonStringIs400() {
        Map<String, Object> body = body();
        body.put("replacementModel", 42);

        ResponseEntity<?> response = controller.saveOverride("ADMIN", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("the service's refusal (unknown / self / half pair) reaches the admin as a 400 with its message")
    void serviceRefusalIs400() {
        when(service.saveOverride(any())).thenThrow(new IllegalArgumentException("Unknown replacement model: openai:gpt-typo"));
        Map<String, Object> body = body();
        body.put("replacementProvider", "openai");
        body.put("replacementModel", "gpt-typo");

        ResponseEntity<?> response = controller.saveOverride("ADMIN", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(response.getBody())).contains("Unknown replacement model");
    }
}
