package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.services.OptionalFeatureCapabilityService;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Warns when the plan uses a feature backed by an OPTIONAL deployment component
 * that is absent on this installation (agent-facing mirror of the frontend
 * NodeConfigurationRule availability warnings):
 *
 * <ul>
 *   <li>{@code browser_agent} node while the browser stack is unavailable
 *       (no local engine AND no cloud-link relay) - the node WILL fail at run time.</li>
 *   <li>Interface {@code generateScreenshot}/{@code generatePdf}/{@code generateVideo}
 *       while the renderer sidecar is not configured - the {@code screenshot}/{@code pdf}
 *       /{@code video} output will silently be absent (best-effort contract, run still
 *       succeeds).</li>
 *   <li>{@code media} core node while the renderer sidecar is not configured - unlike the
 *       best-effort interface renders, the media output IS the node's purpose, so the
 *       node WILL fail at run time.</li>
 * </ul>
 *
 * WARNINGS only, never errors: the workflow stays saveable and runs correctly on
 * an installation that has the component (publish/clone). Availability is resolved
 * through {@link OptionalFeatureCapabilityService} - the same source the builder UI
 * and tool exposure use - and only when the plan actually contains an affected node
 * (the browsing check can cost an HTTP roundtrip on relay-wired CE installs).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OptionalComponentValidator implements WorkflowValidator {

    private final OptionalFeatureCapabilityService capabilityService;

    @Override
    public void validate(WorkflowBuilderSession session, ValidationResult result) {
        boolean hasBrowserAgent = session.getMcps().stream()
                .anyMatch(OptionalComponentValidator::isBrowserAgentNode);
        boolean hasRenderToggle = session.getInterfaces().stream()
                .anyMatch(OptionalComponentValidator::hasRenderToggle);
        boolean hasMediaNode = session.getCores().stream()
                .anyMatch(OptionalComponentValidator::isMediaNode);
        if (!hasBrowserAgent && !hasRenderToggle && !hasMediaNode) {
            return;
        }

        try {
            // Split resolution: the renderer verdict is a local property check, the
            // browsing verdict can cost an HTTP roundtrip - only pay it when the plan
            // actually contains a browser_agent node.
            if (hasBrowserAgent && !capabilityService.isBrowsingAvailable(session.getTenantId())) {
                for (Map<String, Object> step : session.getMcps()) {
                    if (!isBrowserAgentNode(step)) {
                        continue;
                    }
                    result.addWarning("BROWSER_AGENT_COMPONENT_UNAVAILABLE",
                            nodeRef(step, "agent"),
                            "Browser automation is not available on this installation (optional component "
                            + "not enabled, no cloud link). This node will fail at run time here; the "
                            + "workflow still runs on installations that have it. Only the user or an "
                            + "administrator can enable the component - tell them if they expect this "
                            + "node to run here, or remove the node.");
                }
            }

            if (hasRenderToggle && !capabilityService.isScreenshotRendererAvailable()) {
                for (Map<String, Object> iface : session.getInterfaces()) {
                    if (!hasRenderToggle(iface)) {
                        continue;
                    }
                    result.addWarning("INTERFACE_RENDERER_UNAVAILABLE",
                            nodeRef(iface, "interface"),
                            "generateScreenshot/generatePdf/generateVideo is enabled but the optional renderer "
                            + "component is not available on this installation: the screenshot/pdf/video output "
                            + "field will be absent at run time (the workflow still succeeds). Only the user or an "
                            + "administrator can enable the component - tell them if they need this output "
                            + "here, or disable the toggle.");
                }
            }

            if (hasMediaNode && !capabilityService.isScreenshotRendererAvailable()) {
                for (Map<String, Object> core : session.getCores()) {
                    if (!isMediaNode(core)) {
                        continue;
                    }
                    result.addWarning("MEDIA_RENDERER_UNAVAILABLE",
                            nodeRef(core, "core"),
                            "Media processing (probe/mux_audio/mix/extract_audio) needs the optional renderer "
                            + "component, which is not available on this installation. This node WILL fail at "
                            + "run time here (its media output is not best-effort); the workflow still runs on "
                            + "installations that have the component. Only the user or an administrator can "
                            + "enable it - tell them if they expect this node to run here, or remove the node.");
                }
            }
        } catch (RuntimeException e) {
            // Unknown availability must never fail (or falsely warn on) validation.
            log.warn("Optional-component capability resolution failed for tenant {}: {}",
                    session.getTenantId(), e.getMessage());
        }
    }

    private static boolean isBrowserAgentNode(Map<String, Object> step) {
        return "browser_agent".equalsIgnoreCase(String.valueOf(step.get("type")));
    }

    private static boolean isMediaNode(Map<String, Object> core) {
        return "media".equalsIgnoreCase(String.valueOf(core.get("type")));
    }

    /**
     * True when the interface node asks for a screenshot, a PDF render, or a video
     * recording. Strict {@code Boolean.TRUE} match, aligned with WorkflowPlanParser: a
     * non-Boolean value (e.g. the string "true") never triggers a render at
     * run time, so warning "renderer unavailable" for it would state the
     * wrong cause for the missing output.
     */
    private static boolean hasRenderToggle(Map<String, Object> iface) {
        return Boolean.TRUE.equals(iface.get("generateScreenshot"))
                || Boolean.TRUE.equals(iface.get("generatePdf"))
                || Boolean.TRUE.equals(iface.get("generateVideo"));
    }

    /**
     * Normalized addressable node key ({@code agent:browse_site} /
     * {@code interface:results_ui}) - the same dialect every sibling
     * sub-validator emits, so the agent can pass it straight to modify/remove.
     * Raw id fallback for label-less nodes.
     */
    private static String nodeRef(Map<String, Object> node, String prefix) {
        Object label = node.get("label");
        if (label != null && !String.valueOf(label).isBlank()) {
            return prefix + ":" + WorkflowBuilderSession.normalizeLabel(String.valueOf(label));
        }
        Object id = node.get("id");
        return id != null ? String.valueOf(id) : null;
    }
}
