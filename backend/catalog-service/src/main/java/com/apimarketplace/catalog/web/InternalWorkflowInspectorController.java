package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.dto.CustomApiRefDTO;
import com.apimarketplace.catalog.dto.ToolBatchRequest;
import com.apimarketplace.catalog.service.WorkflowInspectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Service-to-service twin of {@link WorkflowInspectorController} for the catalog
 * lookups other services need while validating a workflow or agent snapshot.
 *
 * <p>Only reachable in-cluster ({@code /api/internal/**} is never routed from outside,
 * and the CE monolith blocks it for external callers), so no user scoping is applied.
 */
@RestController
@RequestMapping("/api/internal/catalog/workflow-inspector")
@RequiredArgsConstructor
@Slf4j
public class InternalWorkflowInspectorController {

    private final WorkflowInspectorService workflowInspectorService;

    /**
     * Report which of the given workflow tool identifiers belong to a CUSTOM API
     * ({@code apis.source = 'custom'}).
     *
     * <p>Called by publication-service to refuse a shareable publication (PUBLIC /
     * UNLISTED) built on a tenant-private API: the acquirer's catalog has no such API,
     * so every node on it would fail at run time. The publish surfaces call the public
     * {@code POST /api/workflow-inspector/custom-apis} to warn before submitting; this
     * endpoint is what actually gates the publish.
     *
     * <p>Identifiers accept the forms a plan uses: {@code apiSlug/toolSlug} (mcp node),
     * {@code apiSlug:toolSlug} (agent tool grant), a bare {@code tool_slug}, or an
     * {@code api_tools.id} UUID.
     *
     * <p>The publishing tenant travels in {@code X-User-ID} / {@code X-Organization-ID}:
     * the publisher's OWN custom APIs match on the api-slug prefix (so a renamed tool row
     * still blocks), while any other tenant's custom API needs an exact row match. See
     * {@code WorkflowInspectorService.findCustomApiRefs} for why that asymmetry exists.
     *
     * Request body: {@code {"toolSlugs": ["my-api/do-thing", ...]}}
     * Response: {@code {"customApis": [{"apiSlug", "apiName", "toolIdentifiers"[]}]}}
     */
    @PostMapping("/custom-apis")
    public ResponseEntity<Map<String, Object>> resolveCustomApiRefs(
            @RequestBody ToolBatchRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String publisherId,
            @RequestHeader(value = "X-Organization-ID", required = false) String publisherOrgId) {
        List<String> identifiers = request != null ? request.toolSlugs() : null;
        if (identifiers == null || identifiers.isEmpty()) {
            return ResponseEntity.ok(Map.of("customApis", List.of()));
        }
        List<CustomApiRefDTO> customApis =
                workflowInspectorService.findCustomApiRefs(identifiers, publisherId, publisherOrgId);
        return ResponseEntity.ok(Map.of("customApis", customApis));
    }
}
