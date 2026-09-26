package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.analysis.RunAnalysisService;
import com.apimarketplace.orchestrator.services.analysis.RunAnalysisService.RunAnalysis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowRunController - run analysis endpoint")
class WorkflowRunControllerAnalysisTest {

    private static final String RUN_ID = "run_analysis";
    private static final String OWNER = "tenant-A";

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Mock
    private RunAnalysisService runAnalysisService;

    @InjectMocks
    private WorkflowRunController controller;

    private WorkflowRunEntity run;

    @BeforeEach
    void setUp() {
        run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setTenantId(OWNER);
        lenient().when(workflowRunRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
    }

    @Test
    @DisplayName("the owner gets the analysis for the window it asked for")
    void ownerGetsTheAnalysis() {
        RunAnalysis analysis = new RunAnalysis(RUN_ID, 0, List.of());
        when(runAnalysisService.analyze(run, 30)).thenReturn(analysis);

        ResponseEntity<?> response = controller.getRunAnalysis(RUN_ID, 30, OWNER, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(analysis);
    }

    @Test
    @DisplayName("no caller identity is a 401 and reads nothing")
    void anonymousIsRejected() {
        ResponseEntity<?> response = controller.getRunAnalysis(RUN_ID, null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(runAnalysisService, never()).analyze(any(), any());
    }

    @Test
    @DisplayName("another tenant's run answers 404, never its analysis")
    void crossTenantIsHidden() {
        ResponseEntity<?> response = controller.getRunAnalysis(RUN_ID, null, "tenant-B", null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(runAnalysisService, never()).analyze(any(), any());
    }

    @Test
    @DisplayName("an organization run: a member of that organization reads it, the owner viewing another workspace does not")
    void organizationScopeIsStrict() {
        run.setOrganizationId("org-1");
        RunAnalysis analysis = new RunAnalysis(RUN_ID, 0, List.of());
        when(runAnalysisService.analyze(run, null)).thenReturn(analysis);

        assertThat(controller.getRunAnalysis(RUN_ID, null, "teammate", "org-1").getStatusCode().value()).isEqualTo(200);
        assertThat(controller.getRunAnalysis(RUN_ID, null, OWNER, "org-2").getStatusCode().value()).isEqualTo(404);
        assertThat(controller.getRunAnalysis(RUN_ID, null, OWNER, null).getStatusCode().value())
                .as("personal workspace view of an org-tagged run").isEqualTo(404);
    }

    @Test
    @DisplayName("a failure while building the analysis is a 500 that does not leak the exception text")
    void failureIsAnOpaque500() {
        when(runAnalysisService.analyze(run, null)).thenThrow(new IllegalStateException("secret internals"));

        ResponseEntity<?> response = controller.getRunAnalysis(RUN_ID, null, OWNER, null);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(String.valueOf(response.getBody())).doesNotContain("secret internals");
    }

    @Test
    @DisplayName("an application share link reads only the run of ITS publication")
    void shareLinkIsBoundToItsPublication() {
        run.setPublicationId("pub-1");
        when(runAnalysisService.analyze(run, null)).thenReturn(new RunAnalysis(RUN_ID, 0, List.of()));
        org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
        request.addHeader("X-Share-Context", "true");
        request.addHeader("X-Share-Resource-Type", "APPLICATION");
        try {
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(request));

            request.addHeader("X-Share-Resource-Token", "pub-other");
            assertThat(controller.getRunAnalysis(RUN_ID, null, OWNER, null).getStatusCode().value())
                    .as("another publication's share token").isEqualTo(404);

            org.springframework.mock.web.MockHttpServletRequest own = new org.springframework.mock.web.MockHttpServletRequest();
            own.addHeader("X-Share-Context", "true");
            own.addHeader("X-Share-Resource-Type", "APPLICATION");
            own.addHeader("X-Share-Resource-Token", "pub-1");
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                    new org.springframework.web.context.request.ServletRequestAttributes(own));
            assertThat(controller.getRunAnalysis(RUN_ID, null, OWNER, null).getStatusCode().value()).isEqualTo(200);
        } finally {
            org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    @DisplayName("an unknown run answers 404")
    void unknownRunIs404() {
        when(workflowRunRepository.findByRunIdPublic("run_missing")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.getRunAnalysis("run_missing", null, OWNER, null);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
