package com.apimarketplace.catalog.web;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.catalog.tools.generation.GenerationModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression for POST /api/generation/execute: a paid generation that stores its output in
 * the workspace ran for a VIEWER, because the handler built its tool context with the role
 * hard-coded to null. It now binds X-Organization-Role, refuses a VIEWER, and passes the
 * real role on.
 */
@DisplayName("GenerationController.execute binds X-Organization-Role and refuses VIEWER")
class GenerationExecuteViewerGateWebMvcTest {

    private GenerationModule module;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        module = mock(GenerationModule.class);
        Constructor<?> ctor = Arrays.stream(GenerationController.class.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Object[] args = Arrays.stream(ctor.getParameterTypes())
                .map(t -> t == GenerationModule.class ? module : mock(t)).toArray();
        mockMvc = MockMvcBuilders.standaloneSetup(ctor.newInstance(args)).build();
    }

    @Test
    @DisplayName("VIEWER: 403 and the generation module is never called")
    void viewerRefused() throws Exception {
        mockMvc.perform(post("/api/generation/execute").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("X-User-ID", "7").header("X-Organization-ID", "org-g")
                        .header("X-Organization-Role", "VIEWER"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(module);
    }

    @Test
    @DisplayName("MEMBER: the generation runs and receives the real role (no longer null)")
    void memberRunsWithRole() throws Exception {
        when(module.execute(eq("create"), anyMap(), anyString(), any())).thenReturn(Optional.empty());

        mockMvc.perform(post("/api/generation/execute").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("X-User-ID", "7").header("X-Organization-ID", "org-g")
                        .header("X-Organization-Role", "MEMBER"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isNotEqualTo(403));

        ArgumentCaptor<ToolExecutionContext> ctx = ArgumentCaptor.forClass(ToolExecutionContext.class);
        verify(module).execute(eq("create"), anyMap(), eq("7"), ctx.capture());
        assertThat(ctx.getValue().orgRole()).isEqualTo("MEMBER");
        assertThat(ctx.getValue().orgId()).isEqualTo("org-g");
    }
}
