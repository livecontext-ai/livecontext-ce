package com.apimarketplace.catalog.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.catalog.config.GlobalExceptionHandler;
import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.service.CatalogV1Service;
import com.apimarketplace.catalog.service.exception.ToolNotFoundException;
import com.apimarketplace.catalog.service.execution.MockToolExecutionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Executing a tool id that names no tool is a caller mistake: 404 TOOL_NOT_FOUND at INFO.
 *
 * <p>Regression: the controller's generic catch swallowed {@link ToolNotFoundException}
 * before the global 404 handler could see it, answering 500 and logging a stack trace at
 * ERROR. The agent-facing caller then read a server error and advised a retry that can
 * never succeed. Dispatched with the real advice so the test also proves the catch order.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogV1Controller - unknown tool id")
class CatalogV1ControllerToolNotFoundTest {

    @Mock
    private CatalogV1Service catalogV1Service;

    @Mock
    private MockToolExecutionService mockToolExecutionService;

    private MockMvc mockMvc;
    private ListAppender<ILoggingEvent> appender;
    private Logger controllerLogger;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new CatalogV1Controller(catalogV1Service, mockToolExecutionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        controllerLogger = (Logger) LoggerFactory.getLogger(CatalogV1Controller.class);
        appender = new ListAppender<>();
        appender.start();
        controllerLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        controllerLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("POST /tools/{id}/execute on a missing tool -> 404 TOOL_NOT_FOUND with toolId, no ERROR log")
    void executeMissingToolIs404() throws Exception {
        when(catalogV1Service.executeTool(eq("dead-id"), any(ToolExecutionRequest.class), any(), any(), any()))
                .thenThrow(new ToolNotFoundException("dead-id"));

        mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", "dead-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value(ToolNotFoundException.ERROR_CODE))
                .andExpect(jsonPath("$.message").value("Tool not found: dead-id"))
                .andExpect(jsonPath("$.toolId").value("dead-id"));

        assertThat(appender.list).noneMatch(e -> e.getLevel() == Level.ERROR);
        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.INFO
                && e.getFormattedMessage().contains("dead-id"));
    }

    @Test
    @DisplayName("POST /tools/{apiSlug}/{toolSlug}/execute on a missing tool -> 404 with the combined id")
    void executeMissingSlugToolIs404() throws Exception {
        when(catalogV1Service.executeTool(eq("gmail/gone"), any(ToolExecutionRequest.class), any(), any(), any()))
                .thenThrow(new ToolNotFoundException("gmail/gone"));

        mockMvc.perform(post("/catalog/v1/tools/{apiSlug}/{toolSlug}/execute", "gmail", "gone")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(ToolNotFoundException.ERROR_CODE))
                .andExpect(jsonPath("$.toolId").value("gmail/gone"));
    }

    @Test
    @DisplayName("POST /tools/{id}/execute-mock on a missing tool -> 404 now carrying the TOOL_NOT_FOUND code")
    void executeMockMissingToolCarriesCode() throws Exception {
        when(mockToolExecutionService.executeMockTool(eq("dead-id"), any()))
                .thenThrow(new ToolNotFoundException("dead-id"));

        mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute-mock", "dead-id"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(ToolNotFoundException.ERROR_CODE))
                .andExpect(jsonPath("$.message").value("Tool not found"));
    }

    @Test
    @DisplayName("an unexpected failure is unchanged: 500, same body shape, logged at ERROR")
    void unexpectedFailureStill500() throws Exception {
        when(catalogV1Service.executeTool(eq("t1"), any(ToolExecutionRequest.class), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        mockMvc.perform(post("/catalog/v1/tools/{toolId}/execute", "t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Unable to execute tool"))
                .andExpect(jsonPath("$.error").value("db down"));

        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.ERROR);
    }
}
