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
import static org.mockito.Mockito.when;

/**
 * The admin's unlist switch (V554), at the boundary it enters through. Same three cases as the
 * free-tier switch, for the same reason (a NOT NULL column behind a PATCH-style save): absent
 * leaves it alone, a value sets it, null reads as listed.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelConfigController.saveOverride - the unlisted switch")
class ModelConfigControllerUnlistedIntakeTest {

    @Mock private ModelCatalogService service;
    @InjectMocks private ModelConfigController controller;

    private Map<String, Object> body() {
        Map<String, Object> body = new HashMap<>();
        body.put("provider", "openai");
        body.put("modelId", "gpt-4o");
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
    @DisplayName("true unlists, together with enabled=true in the same request (off -> unlisted in one save)")
    void trueUnlists() {
        Map<String, Object> body = body();
        body.put("enabled", true);
        body.put("unlisted", true);

        ModelConfigOverrideEntity passed = save(body);

        assertThat(passed.isUnlisted()).isTrue();
        assertThat(passed.isUnlistedExplicitlySet()).isTrue();
        assertThat(passed.getEnabled()).isTrue();
    }

    @Test
    @DisplayName("false lists it again, and is still an explicit decision")
    void falseLists() {
        Map<String, Object> body = body();
        body.put("unlisted", false);

        ModelConfigOverrideEntity passed = save(body);

        assertThat(passed.isUnlisted()).isFalse();
        assertThat(passed.isUnlistedExplicitlySet()).isTrue();
    }

    @Test
    @DisplayName("null reads as listed (the column is NOT NULL)")
    void nullReadsAsListed() {
        Map<String, Object> body = body();
        body.put("unlisted", null);

        ModelConfigOverrideEntity passed = save(body);

        assertThat(passed.isUnlisted()).isFalse();
        assertThat(passed.isUnlistedExplicitlySet()).isTrue();
    }

    @Test
    @DisplayName("absent: an unrelated edit does not touch the flag")
    void absentLeavesItAlone() {
        Map<String, Object> body = body();
        body.put("tier", "mid");

        ModelConfigOverrideEntity passed = save(body);

        assertThat(passed.isUnlistedExplicitlySet())
                .as("without this the save would carry false and list the model again")
                .isFalse();
    }

    @Test
    @DisplayName("a non-boolean value is a 400, not a silent listing")
    void garbageIsRefused() {
        Map<String, Object> body = body();
        body.put("unlisted", "yes please");

        ResponseEntity<?> response = controller.saveOverride("ADMIN", body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
