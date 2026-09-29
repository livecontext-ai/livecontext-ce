package com.apimarketplace.orchestrator.controllers.interfaces;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.SharedApplicationScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Share-link probe through the REAL {@link InterfaceRenderService} (only its repositories and
 * clients are mocked): a share holder pairing the SHARED run with a foreign interface id of the
 * owner's workspace gets 404 on /items and /run-info, and nothing is fetched for that interface.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterfaceController - share probe with a foreign interface id (real render service)")
class InterfaceControllerShareProbeTest {

    private static final String OWNER = "owner-1";
    private static final String ORG = "org-1";
    private static final String RUN_ID = "run_shared";
    private static final String PUB = "11111111-1111-1111-1111-111111111111";
    private static final UUID APP_INTERFACE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID FOREIGN_INTERFACE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private WorkflowStepDataRepository stepDataRepository;
    @Mock private InterfaceClient interfaceClient;
    @Mock private SharedApplicationScopeService sharedApplicationScopeService;

    @InjectMocks
    private InterfaceRenderService renderService;

    private InterfaceController controller;

    @BeforeEach
    void setUp() {
        controller = new InterfaceController(renderService, new TenantResolver());
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setTenantId(OWNER);
        run.setOrganizationId(ORG);
        run.setPublicationId(PUB);
        run.setPlan(Map.of("interfaces", List.of(Map.of("id", APP_INTERFACE.toString(), "label", "Page"))));
        when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(shareRequest()));
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static MockHttpServletRequest shareRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User-ID", OWNER);
        req.addHeader("X-Organization-ID", ORG);
        req.addHeader("X-Share-Context", "true");
        req.addHeader("X-Share-Resource-Type", "APPLICATION");
        req.addHeader("X-Share-Resource-Token", PUB);
        return req;
    }

    @Test
    @DisplayName("/items with a foreign interface id on the shared run: 404, no template fetched")
    void itemsForeignInterfaceIs404() {
        var response = controller.getInterfaceItem(FOREIGN_INTERFACE, 0, RUN_ID, 0, shareRequest(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(interfaceClient, never()).getInterfaceTemplateForRender(any());
        verify(interfaceClient, never()).getSnapshot(any(), any(), any());
    }

    @Test
    @DisplayName("/run-info with a foreign interface id on the shared run: 404, no template fetched")
    void runInfoForeignInterfaceIs404() {
        var response = controller.getInterfaceRunInfo(FOREIGN_INTERFACE, RUN_ID, shareRequest(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(interfaceClient, never()).getInterfaceTemplateForRender(any());
    }

    @Test
    @DisplayName("/render with a foreign interface id on the shared run: 404")
    void renderForeignInterfaceIs404() {
        var response = controller.renderInterface(FOREIGN_INTERFACE, RUN_ID, 0, 10, null, null, shareRequest(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("/items with the application's own interface passes the gate")
    void itemsOwnInterfacePasses() {
        var response = controller.getInterfaceItem(APP_INTERFACE, 0, RUN_ID, 0, shareRequest(), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
