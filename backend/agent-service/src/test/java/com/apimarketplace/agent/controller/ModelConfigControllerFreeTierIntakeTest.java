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
 * The admin's free-tier switch, at the boundary it enters through (V493).
 *
 * <p>This field decides which models a FREE account may spend its AI allowance on, so
 * the intake has to distinguish three cases that look alike in JSON: the key is
 * absent (the admin edited something else, leave the switch alone), the key is
 * present with a value (set it), and the key is present but null (a cleared control,
 * which must read as "not on the free tier" because the column is NOT NULL).
 *
 * <p>The distinction is carried by the transient {@code freeTierEnabledExplicitlySet}
 * flag, which the merge in {@code ModelCatalogService.saveOverride} keys on. Nothing
 * failed when it was unset: the save returned 200 and the switch silently snapped
 * back off, which is why the boundary is pinned here rather than only downstream.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelConfigController.saveOverride - the free-tier switch")
class ModelConfigControllerFreeTierIntakeTest {

    @Mock private ModelCatalogService service;
    @InjectMocks private ModelConfigController controller;

    private Map<String, Object> body() {
        Map<String, Object> body = new HashMap<>();
        body.put("provider", "anthropic");
        body.put("modelId", "claude-haiku-4-5");
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
    @DisplayName("true opens the model and marks the field as explicitly set")
    void trueOpensTheModel() {
        Map<String, Object> body = body();
        body.put("freeTierEnabled", true);

        ModelConfigOverrideEntity passed = save(body);

        assertThat(passed.isFreeTierEnabled()).isTrue();
        assertThat(passed.isFreeTierEnabledExplicitlySet())
                .as("without this flag the merge skips the field and the switch snaps back off")
                .isTrue();
    }

    @Test
    @DisplayName("false closes it, and is still an explicit decision")
    void falseClosesTheModel() {
        Map<String, Object> body = body();
        body.put("freeTierEnabled", false);

        ModelConfigOverrideEntity passed = save(body);

        assertThat(passed.isFreeTierEnabled()).isFalse();
        assertThat(passed.isFreeTierEnabledExplicitlySet())
                .as("closing a model must reach the merge, or an open model can never be closed")
                .isTrue();
    }

    @Test
    @DisplayName("an explicit null reads as closed rather than blowing up on the NOT NULL column")
    void explicitNullReadsAsClosed() {
        Map<String, Object> body = body();
        body.put("freeTierEnabled", null);

        ModelConfigOverrideEntity passed = save(body);

        assertThat(passed.isFreeTierEnabled()).isFalse();
        assertThat(passed.isFreeTierEnabledExplicitlySet()).isTrue();
    }

    @Test
    @DisplayName("an absent key leaves the stored value alone")
    void absentKeyIsNotADecision() {
        // The admin renamed the model and saved. The switch is not in the payload,
        // so the merge must not touch it - an unrelated edit closing a model an
        // admin opened is invisible until a free user is refused.
        Map<String, Object> body = body();
        body.put("displayName", "Haiku");

        ModelConfigOverrideEntity passed = save(body);

        assertThat(passed.isFreeTierEnabledExplicitlySet())
                .as("no key means no decision, so the merge must skip the field")
                .isFalse();
    }

    @Test
    @DisplayName("a non-admin cannot open a model to the free tier")
    void nonAdminIsRejected() {
        Map<String, Object> body = body();
        body.put("freeTierEnabled", true);

        ResponseEntity<?> response = controller.saveOverride("USER", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(service);
    }
}
