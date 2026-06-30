package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.InterfaceRenderService.InterfaceRenderResult;
import com.apimarketplace.orchestrator.services.InterfaceRenderService.ItemRenderData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("InternalPublicationSupportController.renderInterfaceForScreening - Wave 2b item-data render")
class InternalPublicationSupportControllerScreeningRenderTest {

    @Mock WorkflowRunRepository workflowRunRepository;
    @Mock InterfaceRenderService interfaceRenderService;

    private InternalPublicationSupportController controller() {
        // 20-arg constructor; only workflowRunRepository (#2) and
        // interfaceRenderService (#11) are exercised by this endpoint.
        return new InternalPublicationSupportController(
                null, workflowRunRepository, null, null, null, null, null, null,
                null, null, interfaceRenderService, null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("404 when the run does not exist")
    void notFoundWhenRunMissing() {
        when(workflowRunRepository.findByRunIdPublic("run_missing")).thenReturn(Optional.empty());

        ResponseEntity<?> resp = controller().renderInterfaceForScreening(
                "run_missing", UUID.randomUUID(), null, "5", "org-5");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("404 when the caller is out of the run's tenant/org scope (no cross-tenant render)")
    void notFoundWhenOutOfScope() {
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        when(run.getTenantId()).thenReturn("owner");
        when(run.getOrganizationId()).thenReturn("org-owner");
        when(workflowRunRepository.findByRunIdPublic("run_x")).thenReturn(Optional.of(run));

        ResponseEntity<?> resp = controller().renderInterfaceForScreening(
                "run_x", UUID.randomUUID(), null, "intruder", "org-intruder");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("200 returns only items[].data for an in-scope run")
    void returnsItemsForInScopeRun() {
        UUID interfaceId = UUID.randomUUID();
        WorkflowRunEntity run = mock(WorkflowRunEntity.class);
        when(run.getTenantId()).thenReturn("5");
        when(run.getOrganizationId()).thenReturn("org-5");
        when(workflowRunRepository.findByRunIdPublic("run_ok")).thenReturn(Optional.of(run));

        ItemRenderData item = mock(ItemRenderData.class);
        when(item.epoch()).thenReturn(0);
        when(item.itemIndex()).thenReturn(0);
        when(item.data()).thenReturn(Map.of("displayUrl", "https://cdn.example.com/a.jpg"));
        InterfaceRenderResult result = mock(InterfaceRenderResult.class);
        when(result.items()).thenReturn(List.of(item));
        when(interfaceRenderService.render(eq(interfaceId), eq("run_ok"), eq("5"), eq(0), anyInt(), any()))
                .thenReturn(result);

        ResponseEntity<?> resp = controller().renderInterfaceForScreening(
                "run_ok", interfaceId, null, "5", "org-5");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        assertThat(items).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) items.get(0).get("data");
        assertThat(data).containsEntry("displayUrl", "https://cdn.example.com/a.jpg");
    }
}
