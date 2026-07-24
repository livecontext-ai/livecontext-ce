package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Node specification for the Interface node.
 *
 * Output schema:
 * - interface_id: string - the ID of the rendered interface
 * - action_mapping: object - maps button IDs to workflow actions
 * - is_entry_interface: boolean - whether this is the first interface in the workflow
 * - screenshot: object (FileRef) - PNG capture of the rendered interface (only present when
 *   generateScreenshot=true on the node AND the renderer sidecar successfully captured it).
 *   Conditional: no default, absent when not generated.
 * - pdf: object (FileRef) - PDF rendering of the interface (only present when generatePdf=true on
 *   the node AND the renderer sidecar successfully rendered it). Conditional: absent when not generated.
 * - video: object (FileRef) - MP4 recording of the interface's animation (only present when
 *   generateVideo=true on the node AND the renderer sidecar successfully recorded it).
 *   Conditional: absent when not generated.
 * - rendered_html / rendered_css / rendered_js: string - the resolved interface templates
 *   (only present when exposeRenderedSource=true on the node AND the renderer returned a
 *   non-null value for that part). Conditional: no default, absent when not exposed.
 */
@Component
public class InterfaceNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("INTERFACE")
            .label("Interface")
            .category("interface")
            .variablePrefix("interface")
            .description("Renders a web interface and waits for user interaction")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("interface_id")
                    .type("string")
                    .description("ID of the rendered interface")
                    .build(),
                OutputFieldDef.builder()
                    .key("action_mapping")
                    .type("object")
                    .description("Maps button/action IDs to workflow targets")
                    .build(),
                OutputFieldDef.builder()
                    .key("is_entry_interface")
                    .type("boolean")
                    .description("Whether this is the first interface in the workflow")
                    .defaultValue(false)
                    .build(),
                OutputFieldDef.builder()
                    .key("screenshot")
                    .type("object")
                    .description("FileRef to a PNG capture of the rendered interface. Only emitted when "
                        + "the generateScreenshot parameter is true AND the renderer sidecar returns a "
                        + "successful capture. Dimensions follow the interface's own format: an exact "
                        + "WIDTHxHEIGHT frame when it declares one, otherwise a full-page capture at "
                        + "1280x800 viewport width. Best-effort: a failed capture leaves this field absent - "
                        + "downstream consumers should null-guard or use a SpEL pipe default.")
                    .build(),
                OutputFieldDef.builder()
                    .key("pdf")
                    .type("object")
                    .description("FileRef to a PDF rendering of the interface. Only emitted when the "
                        + "generatePdf parameter is true AND the renderer sidecar returns a successful "
                        + "render. Page size follows pdfFormat (A4/Letter/Legal, default A4) and "
                        + "pdfLandscape. Best-effort: a failed render leaves this field absent - map the "
                        + "whole FileRef into a file-accepting param (email attachment, Telegram "
                        + "send_document, agent file input) to use it.")
                    .build(),
                OutputFieldDef.builder()
                    .key("video")
                    .type("object")
                    .description("FileRef to an MP4 recording of the interface's animation. Only "
                        + "emitted when the generateVideo parameter is true AND the renderer sidecar "
                        + "returns a successful recording. Capture dimensions follow the explicit "
                        + "videoPreset when set (vertical 1080x1920 / horizontal 1920x1080 / square "
                        + "1080x1080), otherwise the interface's own format, otherwise vertical; "
                        + "the recording stops when the page sets window.__DONE__ = true "
                        + "or after videoMaxDurationSeconds (default 30, max 120). Best-effort: a "
                        + "failed recording leaves this field absent - map the whole FileRef into a "
                        + "file-accepting param (social upload, Telegram send_video, email "
                        + "attachment) to use it.")
                    .build(),
                OutputFieldDef.builder()
                    .key("rendered_html")
                    .type("string")
                    .description("Resolved HTML template of the rendered interface. Only emitted when "
                        + "the exposeRenderedSource parameter is true AND the renderer returned a "
                        + "non-null htmlTemplate. Best-effort: render failure leaves this field absent. "
                        + "Capped at 256 KB; oversized templates are truncated to the first 256 KB.")
                    .build(),
                OutputFieldDef.builder()
                    .key("rendered_css")
                    .type("string")
                    .description("Resolved CSS template of the rendered interface. Only emitted when "
                        + "the exposeRenderedSource parameter is true AND the renderer returned a "
                        + "non-null cssTemplate. Absent for interfaces with no CSS. Capped at 256 KB.")
                    .build(),
                OutputFieldDef.builder()
                    .key("rendered_js")
                    .type("string")
                    .description("Resolved JS template of the rendered interface. Only emitted when "
                        + "the exposeRenderedSource parameter is true AND the renderer returned a "
                        + "non-null jsTemplate. Absent for interfaces with no JS. Capped at 256 KB.")
                    .build()
            ))
            .keywords(List.of("interface", "ui", "page", "form", "display", "screenshot", "capture",
                "pdf", "document", "print", "video", "recording", "mp4", "html", "css", "javascript",
                "source", "template"))
            .build();
    }
}
