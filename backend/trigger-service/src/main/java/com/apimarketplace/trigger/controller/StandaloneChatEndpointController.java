package com.apimarketplace.trigger.controller;

import com.apimarketplace.trigger.client.dto.ChatEndpointAccessLogDto;
import com.apimarketplace.trigger.client.dto.EndpointConfigDto;
import com.apimarketplace.trigger.client.dto.StandaloneChatEndpointDto;
import com.apimarketplace.trigger.client.dto.StandaloneChatEndpointRequest;
import com.apimarketplace.trigger.client.dto.WorkflowReferenceRequest;
import com.apimarketplace.trigger.service.StandaloneChatEndpointService;
import com.apimarketplace.trigger.service.WorkflowReferenceImmutableException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat-endpoints")
public class StandaloneChatEndpointController {

    private final StandaloneChatEndpointService chatEndpointService;

    public StandaloneChatEndpointController(StandaloneChatEndpointService chatEndpointService) {
        this.chatEndpointService = chatEndpointService;
    }

    @GetMapping
    public ResponseEntity<List<StandaloneChatEndpointDto>> getAll(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        return ResponseEntity.ok(chatEndpointService.getAll(tenantId, organizationId));
    }

    @PostMapping
    public ResponseEntity<StandaloneChatEndpointDto> create(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan,
            @Valid @RequestBody StandaloneChatEndpointRequest request) {
        try {
            StandaloneChatEndpointDto response = chatEndpointService.create(tenantId, organizationId, userPlan, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<StandaloneChatEndpointDto> getById(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable UUID id) {
        try {
            return ResponseEntity.ok(chatEndpointService.getById(tenantId, organizationId, id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<StandaloneChatEndpointDto> update(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable UUID id,
            @Valid @RequestBody StandaloneChatEndpointRequest request) {
        try {
            return ResponseEntity.ok(chatEndpointService.update(tenantId, organizationId, id, request));
        } catch (WorkflowReferenceImmutableException e) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable UUID id) {
        try {
            chatEndpointService.delete(tenantId, organizationId, id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/regenerate-token")
    public ResponseEntity<StandaloneChatEndpointDto> regenerateToken(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable UUID id) {
        try {
            return ResponseEntity.ok(chatEndpointService.regenerateToken(tenantId, organizationId, id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/logs")
    public ResponseEntity<Page<ChatEndpointAccessLogDto>> getAccessLogs(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ResponseEntity.ok(chatEndpointService.getAccessLogs(tenantId, organizationId, id, page, size));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PatchMapping("/{id}/workflow")
    public ResponseEntity<StandaloneChatEndpointDto> updateWorkflowReference(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable UUID id,
            @RequestBody WorkflowReferenceRequest request) {
        try {
            UUID workflowId = request.workflowId() != null ? UUID.fromString(request.workflowId()) : null;
            return ResponseEntity.ok(chatEndpointService.updateWorkflowReference(tenantId, organizationId, id, workflowId, request.workflowName()));
        } catch (WorkflowReferenceImmutableException e) {
            return ResponseEntity.badRequest().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/config")
    public ResponseEntity<EndpointConfigDto> getConfig(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan) {
        return ResponseEntity.ok(chatEndpointService.getConfig(tenantId, organizationId, userPlan));
    }
}
