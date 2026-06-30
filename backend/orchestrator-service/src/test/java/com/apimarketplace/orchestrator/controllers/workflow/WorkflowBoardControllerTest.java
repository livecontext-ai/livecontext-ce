package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.controllers.dto.WorkflowBoardResponse;
import com.apimarketplace.orchestrator.services.WorkflowBoardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract for the workflow board controller. The critical bit is the
 * {@code applicationsOnly} flag: the {@code /applications/board*} endpoints MUST
 * source APPLICATION-type workflows ({@code loadColumn/buildBoard(..., true)}) while
 * the existing {@code /board*} endpoints MUST stay on the regular-workflow overload
 * (no flag) so applications never leak into the workflow board.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBoardController - applications vs workflows source")
class WorkflowBoardControllerTest {

    @Mock
    private WorkflowBoardService boardService;

    @InjectMocks
    private WorkflowBoardController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /applications/board/column → loadColumn(..., applicationsOnly=true)")
    void applicationsColumnUsesApplicationSource() throws Exception {
        when(boardService.loadColumn(isNull(), isNull(), isNull(), eq("draft"), eq(0), eq(20), eq(true)))
                .thenReturn(new WorkflowBoardService.WorkflowBoardColumnPage("draft", List.of(), 0, 0, 20));

        mockMvc.perform(get("/api/workflows/applications/board/column").param("column", "draft"))
                .andExpect(status().isOk());

        verify(boardService).loadColumn(isNull(), isNull(), isNull(), eq("draft"), eq(0), eq(20), eq(true));
    }

    @Test
    @DisplayName("GET /applications/board → buildBoard(..., applicationsOnly=true)")
    void applicationsBoardUsesApplicationSource() throws Exception {
        when(boardService.buildBoard(isNull(), isNull(), isNull(), eq(0), eq(25), eq(true)))
                .thenReturn(new WorkflowBoardResponse(Map.of(), 0, 0, 25));

        mockMvc.perform(get("/api/workflows/applications/board"))
                .andExpect(status().isOk());

        verify(boardService).buildBoard(isNull(), isNull(), isNull(), eq(0), eq(25), eq(true));
    }

    @Test
    @DisplayName("GET /board/column stays on the regular-workflow overload (no applicationsOnly flag)")
    void workflowColumnUnaffected() throws Exception {
        when(boardService.loadColumn(isNull(), isNull(), isNull(), eq("draft"), eq(0), eq(20)))
                .thenReturn(new WorkflowBoardService.WorkflowBoardColumnPage("draft", List.of(), 0, 0, 20));

        mockMvc.perform(get("/api/workflows/board/column").param("column", "draft"))
                .andExpect(status().isOk());

        // The existing endpoint calls the 6-arg overload - it must NOT hit the applications source.
        verify(boardService).loadColumn(isNull(), isNull(), isNull(), eq("draft"), eq(0), eq(20));
        verify(boardService, never()).loadColumn(any(), any(), any(), any(), anyInt(), anyInt(), eq(true));
    }
}
