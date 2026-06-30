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
                        + "successful capture. Best-effort: a failed capture leaves this field absent - "
                        + "downstream consumers should null-guard or use a SpEL pipe default.")
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
                "html", "css", "javascript", "source", "template"))
            .build();
    }
}
