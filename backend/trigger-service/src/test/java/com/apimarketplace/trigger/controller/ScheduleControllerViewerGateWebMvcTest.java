package com.apimarketplace.trigger.controller;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.trigger.repository.ScheduledExecutionRepository;
import com.apimarketplace.trigger.service.PlanLimitHelper;
import com.apimarketplace.trigger.service.ScheduleCronParser;
import com.apimarketplace.trigger.service.TriggerLifecycleManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Defence in depth: the schedule writes of a workflow (create/update, toggle, delete one,
 * delete all) are gateway-routed, and a read-only VIEWER reached them. They now refuse the
 * VIEWER role before touching any row, like the webhook / chat / form endpoints.
 */
@DisplayName("ScheduleController writes refuse the workspace VIEWER role")
class ScheduleControllerViewerGateWebMvcTest {

    private static final UUID WORKFLOW = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String BASE = "/api/v2/workflows/" + WORKFLOW + "/schedule";

    private ScheduledExecutionRepository repository;
    private TriggerLifecycleManager lifecycle;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository = mock(ScheduledExecutionRepository.class);
        lifecycle = mock(TriggerLifecycleManager.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ScheduleController(repository,
                mock(ScheduleCronParser.class), new TenantResolver(), mock(PlanLimitHelper.class), lifecycle))
                .build();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String role) {
        return b.header("X-User-ID", "u-1").header("X-Organization-ID", "org-t")
                .header("X-Organization-Role", role).contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("VIEWER: create/update, toggle, delete one and delete all are 403, no row is read or written")
    void viewerRefused() throws Exception {
        mockMvc.perform(as(post(BASE + "/trigger:s"), "VIEWER").content("{\"cron\":\"0 9 * * *\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(post(BASE + "/toggle/trigger:s"), "VIEWER").content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(delete(BASE + "/trigger:s"), "VIEWER")).andExpect(status().isForbidden());
        mockMvc.perform(as(delete(BASE), "VIEWER")).andExpect(status().isForbidden());
        verifyNoInteractions(repository, lifecycle);
    }

    @Test
    @DisplayName("MEMBER: toggle and delete reach the repository; VIEWER can still read the status")
    void memberProceedsViewerReads() throws Exception {
        when(repository.findAllByWorkflowIdAndTriggerId(WORKFLOW, "trigger:s")).thenReturn(List.of());
        when(repository.findByWorkflowId(WORKFLOW)).thenReturn(List.of());

        mockMvc.perform(as(post(BASE + "/toggle/trigger:s"), "MEMBER").content("{\"enabled\":false}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(delete(BASE), "MEMBER")).andExpect(status().isNoContent());
        verify(repository).findAllByWorkflowIdAndTriggerId(WORKFLOW, "trigger:s");
        verify(repository).findByWorkflowId(WORKFLOW);

        mockMvc.perform(as(get(BASE + "/status"), "VIEWER"))
                .andExpect(r -> org.assertj.core.api.Assertions.assertThat(r.getResponse().getStatus())
                        .isNotEqualTo(403));
    }
}
