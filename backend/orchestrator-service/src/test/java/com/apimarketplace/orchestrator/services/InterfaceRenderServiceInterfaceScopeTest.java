package com.apimarketplace.orchestrator.services;

import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Which interface a run may render.
 *
 * <p>Two bugs, one shape (an interface id that does not belong to the run is rendered anyway):
 * <ul>
 *   <li>the live-template fallback fetched the interface with NO scope, so pairing a foreign
 *       workspace's interface id with one's OWN run returned the foreign template;</li>
 *   <li>a share-link holder (acting as the owner) could render any interface of the owner's
 *       workspace against the shared run.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterfaceRenderService - interface must belong to the rendered run")
class InterfaceRenderServiceInterfaceScopeTest {

    private static final String RUN_ID = "run_attacker";
    private static final UUID INTERFACE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private InterfaceClient interfaceClient;
    @Mock private WorkflowStepDataRepository stepDataRepository;
    @Mock private SharedApplicationScopeService sharedApplicationScopeService;

    @InjectMocks
    private InterfaceRenderService service;

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static WorkflowRunEntity run(String tenant, String org, String publicationId, Map<String, Object> plan) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId(tenant);
        run.setOrganizationId(org);
        run.setPublicationId(publicationId);
        run.setPlan(plan);
        return run;
    }

    private static InterfaceDto liveInterface(String tenant, String org) {
        InterfaceDto dto = new InterfaceDto();
        dto.setId(INTERFACE_ID);
        dto.setTenantId(tenant);
        dto.setOrganizationId(org);
        dto.setHtmlTemplate("<div>secret template</div>");
        return dto;
    }

    private void givenNoSnapshot() {
        when(stepDataRepository.findWorkflowRunIdsByRunId(RUN_ID)).thenReturn(List.of());
    }

    // ===== live-template fallback (finding: cross-tenant render via own run) =====

    @Test
    @DisplayName("live fallback: another workspace's interface paired with the caller's own run renders nothing")
    void liveFallback_foreignInterface_notRendered() {
        when(workflowRunRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(run("attacker", "org-attacker", null, Map.of())));
        givenNoSnapshot();
        when(interfaceClient.getInterfaceTemplateForRender(INTERFACE_ID))
                .thenReturn(liveInterface("victim", "org-victim"));

        Optional<Map<String, Object>> info = service.getRunInfo(INTERFACE_ID, RUN_ID, "attacker");

        assertThat(info).isEmpty();
    }

    @Test
    @DisplayName("live fallback: an interface of the run's own workspace still renders")
    void liveFallback_sameWorkspaceInterface_rendered() {
        when(workflowRunRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(run("owner", "org-1", null, Map.of())));
        givenNoSnapshot();
        when(interfaceClient.getInterfaceTemplateForRender(INTERFACE_ID))
                .thenReturn(liveInterface("teammate", "org-1"));

        Optional<Map<String, Object>> info = service.getRunInfo(INTERFACE_ID, RUN_ID, "owner");

        assertThat(info).isPresent();
        assertThat(info.get().get("htmlTemplate")).isEqualTo("<div>secret template</div>");
    }

    @Test
    @DisplayName("live fallback: an unknown run renders nothing")
    void liveFallback_unknownRun_notRendered() {
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.empty());
        givenNoSnapshot();
        when(interfaceClient.getInterfaceTemplateForRender(INTERFACE_ID))
                .thenReturn(liveInterface("owner", "org-1"));

        assertThat(service.getRunInfo(INTERFACE_ID, RUN_ID, "owner")).isEmpty();
    }

    // ===== share-link binding =====

    private void shareRequest(String publicationId) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Share-Context", "true");
        req.addHeader("X-Share-Resource-Type", "APPLICATION");
        req.addHeader("X-Share-Resource-Token", publicationId);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
    }

    private static Map<String, Object> planReferencing(UUID interfaceId) {
        return Map.of("interfaces", List.of(Map.of("id", interfaceId.toString(), "label", "Page")));
    }

    @Test
    @DisplayName("share: an owner interface NOT referenced by the shared run's plan is refused")
    void share_interfaceOutsideRunPlan_refused() {
        String pub = UUID.randomUUID().toString();
        shareRequest(pub);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(run("owner", "org-1", pub, planReferencing(UUID.randomUUID()))));

        assertThat(service.callerCanRenderInterface(INTERFACE_ID, RUN_ID, "owner", "org-1")).isFalse();
    }

    @Test
    @DisplayName("share: an interface absent from the run plan but in the application's current plan (publication snapshot) is allowed")
    void share_interfaceInApplicationPlan_allowed() {
        String pub = UUID.randomUUID().toString();
        shareRequest(pub);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(run("owner", "org-1", pub, planReferencing(UUID.randomUUID()))));
        when(sharedApplicationScopeService.interfaceBelongsToApplication(UUID.fromString(pub), "org-1", INTERFACE_ID))
                .thenReturn(true);

        assertThat(service.callerCanRenderInterface(INTERFACE_ID, RUN_ID, "owner", "org-1")).isTrue();
    }

    @Test
    @DisplayName("share: an interface referenced by the shared run's plan is allowed")
    void share_interfaceInRunPlan_allowed() {
        String pub = UUID.randomUUID().toString();
        shareRequest(pub);
        when(workflowRunRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(run("owner", "org-1", pub, planReferencing(INTERFACE_ID))));

        assertThat(service.callerCanRenderInterface(INTERFACE_ID, RUN_ID, "owner", "org-1")).isTrue();
    }

    @Test
    @DisplayName("no share context: the owner renders any interface of a run they can read (unchanged)")
    void noShare_ownerUnchanged() {
        when(workflowRunRepository.findByRunIdPublic(RUN_ID))
                .thenReturn(Optional.of(run("owner", "org-1", null, Map.of())));

        assertThat(service.callerCanRenderInterface(INTERFACE_ID, RUN_ID, "owner", "org-1")).isTrue();
    }
}
