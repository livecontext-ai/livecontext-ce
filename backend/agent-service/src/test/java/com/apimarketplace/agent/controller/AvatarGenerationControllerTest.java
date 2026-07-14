package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.service.avatar.AvatarGenerationService;
import com.apimarketplace.common.web.TenantResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AvatarGenerationController - POST /api/agents/avatar/generate")
class AvatarGenerationControllerTest {

    @Mock private AvatarGenerationService avatarGenerationService;
    @Mock private TenantResolver tenantResolver;
    @Mock private HttpServletRequest httpRequest;

    private AvatarGenerationController controller;

    @BeforeEach
    void setUp() {
        controller = new AvatarGenerationController(avatarGenerationService, tenantResolver);
        lenient().when(tenantResolver.resolveOrNull(httpRequest)).thenReturn("tenant-1");
        lenient().when(tenantResolver.resolveOrgId(httpRequest)).thenReturn("org-1");
    }

    @Test
    @DisplayName("happy path returns {svg} and runs the generation INSIDE the caller's org scope")
    void returnsSvgWithinOrgScope() {
        AtomicReference<String> orgDuringGenerate = new AtomicReference<>();
        when(avatarGenerationService.generate("a fox", null, null, "tenant-1"))
                .thenAnswer(inv -> {
                    orgDuringGenerate.set(TenantResolver.currentRequestOrganizationId());
                    return "<svg/>";
                });

        ResponseEntity<?> response = controller.generateAvatar(httpRequest, Map.of("prompt", "a fox"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map<String, Object>) response.getBody()).containsEntry("svg", "<svg/>");
        assertThat(orgDuringGenerate.get())
                .as("credential/link resolution downstream must see the active workspace")
                .isEqualTo("org-1");
    }

    @Test
    @DisplayName("caller errors (blank prompt, bridge-linked model) map to 400 with the message")
    void callerErrorsMapTo400() {
        when(avatarGenerationService.generate(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("prompt is required"));

        Map<String, Object> body = new HashMap<>();
        body.put("prompt", null);
        ResponseEntity<?> response = controller.generateAvatar(httpRequest, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((Map<String, Object>) response.getBody())
                .containsEntry("error", "avatar_generation_invalid")
                .containsEntry("message", "prompt is required");
    }

    @Test
    @DisplayName("upstream/model failures map to 502 with a GENERIC message (no parser text leak)")
    void upstreamFailuresMapTo502Generic() {
        when(avatarGenerationService.generate(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("Model produced an invalid SVG document"));

        ResponseEntity<?> response = controller.generateAvatar(httpRequest, Map.of("prompt", "x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("error", "avatar_generation_failed");
        assertThat((String) body.get("message")).doesNotContain("invalid SVG document");
    }

    @Test
    @DisplayName("non-string provider/model values are treated as absent, not crashed on")
    void nonStringValuesTreatedAsAbsent() {
        when(avatarGenerationService.generate("x", null, null, "tenant-1")).thenReturn("<svg/>");

        Map<String, Object> body = new HashMap<>();
        body.put("prompt", "x");
        body.put("provider", 42);
        body.put("model", Map.of());
        ResponseEntity<?> response = controller.generateAvatar(httpRequest, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
