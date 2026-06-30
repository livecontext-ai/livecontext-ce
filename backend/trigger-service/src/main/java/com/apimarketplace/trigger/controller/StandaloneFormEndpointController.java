package com.apimarketplace.trigger.controller;

import com.apimarketplace.trigger.client.dto.EndpointConfigDto;
import com.apimarketplace.trigger.client.dto.FormSubmissionLogDto;
import com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto;
import com.apimarketplace.trigger.client.dto.StandaloneFormEndpointRequest;
import com.apimarketplace.trigger.client.dto.WorkflowReferenceRequest;
import com.apimarketplace.trigger.service.StandaloneFormEndpointService;
import com.apimarketplace.trigger.service.WorkflowReferenceImmutableException;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/form-endpoints")
public class StandaloneFormEndpointController {

    private final StandaloneFormEndpointService formEndpointService;

    public StandaloneFormEndpointController(StandaloneFormEndpointService formEndpointService) {
        this.formEndpointService = formEndpointService;
    }

    @GetMapping
    public ResponseEntity<List<StandaloneFormEndpointDto>> getAll(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        // PR22 - strict-isolation list.
        return ResponseEntity.ok(formEndpointService.getAll(tenantId, organizationId));
    }

    @PostMapping
    public ResponseEntity<StandaloneFormEndpointDto> create(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-User-Plan", required = false) String userPlan,
            @Valid @RequestBody StandaloneFormEndpointRequest request) {
        try {
            StandaloneFormEndpointDto response = formEndpointService.create(tenantId, organizationId, userPlan, request);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<StandaloneFormEndpointDto> getById(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable UUID id) {
        try {
            return ResponseEntity.ok(formEndpointService.getById(tenantId, organizationId, id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<StandaloneFormEndpointDto> update(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable UUID id,
            @Valid @RequestBody StandaloneFormEndpointRequest request) {
        try {
            return ResponseEntity.ok(formEndpointService.update(tenantId, organizationId, id, request));
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
            formEndpointService.delete(tenantId, organizationId, id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/regenerate-token")
    public ResponseEntity<StandaloneFormEndpointDto> regenerateToken(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable UUID id) {
        try {
            return ResponseEntity.ok(formEndpointService.regenerateToken(tenantId, organizationId, id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/{id}/logs")
    public ResponseEntity<Page<FormSubmissionLogDto>> getSubmissionLogs(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ResponseEntity.ok(formEndpointService.getSubmissionLogs(tenantId, organizationId, id, page, size));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PatchMapping("/{id}/workflow")
    public ResponseEntity<StandaloneFormEndpointDto> updateWorkflowReference(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @PathVariable UUID id,
            @RequestBody WorkflowReferenceRequest request) {
        try {
            UUID workflowId = request.workflowId() != null ? UUID.fromString(request.workflowId()) : null;
            return ResponseEntity.ok(formEndpointService.updateWorkflowReference(tenantId, organizationId, id, workflowId, request.workflowName()));
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
        return ResponseEntity.ok(formEndpointService.getConfig(tenantId, organizationId, userPlan));
    }
}
