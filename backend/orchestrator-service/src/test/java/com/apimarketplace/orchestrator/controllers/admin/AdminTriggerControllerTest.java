package com.apimarketplace.orchestrator.controllers.admin;

import com.apimarketplace.orchestrator.services.WorkflowPinService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link AdminTriggerController} - round-7 PR6 admin endpoints.
 *
 * <p>Contract under test:
 * <ol>
 *   <li>ADMIN role required (matches existing {@code AdminRoleGuard} pattern).</li>
 *   <li>{@code /rearm} delegates to {@link WorkflowPinService#rearm(UUID)} and
 *       returns the boolean outcome in the JSON body.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminTriggerController - PR6 admin endpoints")
class AdminTriggerControllerTest {

    @Mock private WorkflowPinService pinService;

    @InjectMocks
    private AdminTriggerController controller;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();

    @Nested
    @DisplayName("POST /workflows/{id}/rearm")
    class RearmTests {

        @Test
        @DisplayName("ADMIN role + rearm returns true → 200 with rearmed=true")
        void adminRearmTrue() {
            when(pinService.rearm(WORKFLOW_ID)).thenReturn(true);

            ResponseEntity<?> res = controller.rearmWorkflow("USER,ADMIN", WORKFLOW_ID);

            assertThat(res.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) res.getBody();
            assertThat(body).containsEntry("rearmed", true);
            assertThat(body).containsEntry("workflowId", WORKFLOW_ID.toString());
            verify(pinService).rearm(WORKFLOW_ID);
        }

        @Test
        @DisplayName("ADMIN role + rearm returns false (cleared) → 200 with rearmed=false")
        void adminRearmFalse() {
            when(pinService.rearm(WORKFLOW_ID)).thenReturn(false);

            ResponseEntity<?> res = controller.rearmWorkflow("ADMIN", WORKFLOW_ID);

            assertThat(res.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) res.getBody();
            assertThat(body).containsEntry("rearmed", false);
        }

        @Test
        @DisplayName("Non-ADMIN role → 403, pin service NOT called")
        void nonAdminForbidden() {
            ResponseEntity<?> res = controller.rearmWorkflow("USER", WORKFLOW_ID);

            assertThat(res.getStatusCode().value()).isEqualTo(403);
            verify(pinService, never()).rearm(WORKFLOW_ID);
        }

        @Test
        @DisplayName("Empty roles header → 403")
        void emptyRolesForbidden() {
            ResponseEntity<?> res = controller.rearmWorkflow("", WORKFLOW_ID);

            assertThat(res.getStatusCode().value()).isEqualTo(403);
            verify(pinService, never()).rearm(WORKFLOW_ID);
        }
    }
}
