package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.controllers.dto.WorkflowBoardResponse;
import com.apimarketplace.orchestrator.services.WorkflowBoardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Controller for the workflow board endpoint.
 * Returns workflows pre-classified into 4 columns: draft, production, needsReview, paused.
 */
@RestController
@RequestMapping("/api/workflows")
public class WorkflowBoardController {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowBoardController.class);

    private final WorkflowBoardService boardService;

    public WorkflowBoardController(WorkflowBoardService boardService) {
        this.boardService = boardService;
    }

    @GetMapping("/board")
    public ResponseEntity<WorkflowBoardResponse> getBoard(
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {

        String tenantId = decodeTenantId(userIdHeader);
        logger.info("GET /api/workflows/board - tenantId: '{}', page: {}, size: {}", tenantId, page, size);

        return ResponseEntity.ok(boardService.buildBoard(tenantId, orgId, orgRole, page, size));
    }

    /**
     * Per-column lazy loading endpoint. Each kanban column scrolls independently:
     * the frontend asks for the next page when the user scrolls within that column.
     * Returns {@code { column, items, totalCount, page, size }}.
     */
    @GetMapping("/board/column")
    public ResponseEntity<com.apimarketplace.orchestrator.services.WorkflowBoardService.WorkflowBoardColumnPage> getBoardColumn(
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestParam("column") String column,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {

        String tenantId = decodeTenantId(userIdHeader);
        logger.debug("GET /api/workflows/board/column - tenantId: '{}', column: {}, page: {}, size: {}",
                tenantId, column, page, size);

        return ResponseEntity.ok(boardService.loadColumn(tenantId, orgId, orgRole, column, page, size));
    }

    /**
     * Applications board - same 4 columns (draft/production/needsReview/paused) and per-column lazy
     * loading as {@link #getBoard}, but sourced from APPLICATION-type workflows. Applications are
     * pinnable workflows, so they classify identically (pinnedVersion / production run / pending
     * approval). Returns {@code { columns, totalCount, page, size }}.
     */
    @GetMapping("/applications/board")
    public ResponseEntity<WorkflowBoardResponse> getApplicationsBoard(
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size) {

        String tenantId = decodeTenantId(userIdHeader);
        logger.info("GET /api/workflows/applications/board - tenantId: '{}', page: {}, size: {}", tenantId, page, size);

        return ResponseEntity.ok(boardService.buildBoard(tenantId, orgId, orgRole, page, size, true));
    }

    /**
     * Per-column lazy loading for the applications board (APPLICATION-type workflows).
     * Returns {@code { column, items, totalCount, page, size }}.
     */
    @GetMapping("/applications/board/column")
    public ResponseEntity<com.apimarketplace.orchestrator.services.WorkflowBoardService.WorkflowBoardColumnPage> getApplicationsBoardColumn(
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestParam("column") String column,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {

        String tenantId = decodeTenantId(userIdHeader);
        logger.debug("GET /api/workflows/applications/board/column - tenantId: '{}', column: {}, page: {}, size: {}",
                tenantId, column, page, size);

        return ResponseEntity.ok(boardService.loadColumn(tenantId, orgId, orgRole, column, page, size, true));
    }

    private String decodeTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return tenantId;
        }
        try {
            if (tenantId.contains("%")) {
                return URLDecoder.decode(tenantId, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            logger.warn("Failed to decode tenantId '{}': {}", tenantId, e.getMessage());
        }
        return tenantId;
    }
}
