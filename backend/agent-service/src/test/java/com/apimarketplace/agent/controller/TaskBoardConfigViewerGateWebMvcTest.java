package com.apimarketplace.agent.controller;

import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression (MockMvc, real TenantResolver header binding) for the task-board configuration
 * writes a VIEWER could still reach while the task board itself was read-only: labels
 * (create / rename / delete / set on a task) and board columns (create / update / delete /
 * reorder).
 */
@DisplayName("task labels and board columns refuse the VIEWER role header")
class TaskBoardConfigViewerGateWebMvcTest {

    private static final String ID = "7d3c0f4e-2b1a-4e6f-9c8d-5a4b3c2d1e0f";
    private final List<Object> deps = new ArrayList<>();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = MockMvcBuilders.standaloneSetup(build(TaskLabelController.class), build(TaskStatusController.class))
                .build();
    }

    private <T> T build(Class<T> type) throws Exception {
        Constructor<?> ctor = Arrays.stream(type.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Object[] args = Arrays.stream(ctor.getParameterTypes()).map(t -> {
            if (t == TenantResolver.class) {
                return new TenantResolver();
            }
            Object m = mock(t);
            deps.add(m);
            return m;
        }).toArray();
        return type.cast(ctor.newInstance(args));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String role) {
        return b.header("X-User-ID", "7").header("X-Organization-ID", "org-a")
                .header("X-Organization-Role", role).contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("VIEWER: all eight label / column writes are 403 and reach no service")
    void viewerRefused() throws Exception {
        mockMvc.perform(as(post("/api/tasks/labels"), "VIEWER").content("{\"name\":\"l\"}")).andExpect(status().isForbidden());
        mockMvc.perform(as(patch("/api/tasks/labels/" + ID), "VIEWER").content("{\"name\":\"l\"}")).andExpect(status().isForbidden());
        mockMvc.perform(as(delete("/api/tasks/labels/" + ID), "VIEWER")).andExpect(status().isForbidden());
        mockMvc.perform(as(put("/api/tasks/" + ID + "/labels"), "VIEWER").content("{\"labelIds\":[]}")).andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/tasks/statuses"), "VIEWER").content("{\"label\":\"c\"}")).andExpect(status().isForbidden());
        mockMvc.perform(as(patch("/api/tasks/statuses/" + ID), "VIEWER").content("{\"label\":\"c\"}")).andExpect(status().isForbidden());
        mockMvc.perform(as(delete("/api/tasks/statuses/" + ID), "VIEWER")).andExpect(status().isForbidden());
        mockMvc.perform(as(put("/api/tasks/statuses/order"), "VIEWER").content("{\"orderedIds\":[\"" + ID + "\"]}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(deps.toArray());
    }

    @Test
    @DisplayName("MEMBER: a label delete is not refused by the role gate")
    void memberProceeds() throws Exception {
        mockMvc.perform(as(delete("/api/tasks/labels/" + ID), "MEMBER"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isNotEqualTo(403));
    }
}
