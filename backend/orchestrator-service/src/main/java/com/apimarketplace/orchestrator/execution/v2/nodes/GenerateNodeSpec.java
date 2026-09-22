package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Node specification for Generate (asset generation from a prompt).
 *
 * <p>Every output field is the same whatever the model produced: the asset is
 * always a file, and the format it is in is reported as {@code kind} rather than
 * changing the shape. That is what lets one node cover image, video, audio,
 * voice and music, and what lets a new format be a catalog seed rather than a
 * new node type.
 *
 * <p>No {@code customTransform}: the generic mapper persists exactly the fields
 * declared here, which is what keeps the persisted output, the agent-facing
 * {@code node_type_documentation} row (V429) and the inspector's schema in step.
 */
@Component
public class GenerateNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("GENERATE")
            .label("Generate")
            // "agent", not "ai", and that is not a slip. Two vocabularies name
            // this family: the node-spec registry has four categories (core,
            // agent, trigger, table) and the documentation table has its own set
            // in which the family is called "ai". All five AI specs say "agent"
            // here and all five documentation rows say "ai" there; matching the
            // siblings on each side is what keeps either list whole. Changing
            // this one to "ai" would file it under a category the registry does
            // not have and drop it out of the family it just joined.
            .category("agent")
            .variablePrefix("agent")
            .description("Generates an asset from a prompt: image, video, audio, voice or music. "
                + "The model chosen decides the format, the parameters accepted and the price")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("file")
                    .type("object")
                    .description("Canonical FileRef of the generated asset, stored so it outlives the "
                        + "provider's own link. Reference it via {{agent:label.output.file}} and map the "
                        + "WHOLE object into a downstream file param.")
                    .children(FileRefSchema.children())
                    .build(),
                OutputFieldDef.builder()
                    .key("model")
                    .type("string")
                    .description("Generation model that ran, as its public id (e.g. 'seedance-2.0-fast')")
                    .build(),
                OutputFieldDef.builder()
                    .key("kind")
                    .type("string")
                    .description("Format produced: image, video, audio, voice, music, ... Same value the "
                        + "model was listed under")
                    .build(),
                OutputFieldDef.builder()
                    .key("provider")
                    .type("string")
                    .description("Name of the provider whose model ran")
                    .build(),
                OutputFieldDef.builder()
                    .key("billed_quantity")
                    .type("number")
                    .description("Size the call was billed on, in billed_unit (e.g. 10 for a 10 second "
                        + "video). Derived from the request, not supplied by the node. A model sold per "
                        + "call reports 1")
                    .build(),
                OutputFieldDef.builder()
                    .key("billed_unit")
                    .type("string")
                    .description("What billed_quantity counts: call, second, image or character. This is "
                        + "the unit the size was MEASURED in, which is not always the unit the rate is "
                        + "quoted in: a model listed per minute reports seconds here. To work out the "
                        + "cost, convert billed_quantity into the rate's own unit first (60 seconds is 1 "
                        + "minute), then multiply")
                    .build(),
                OutputFieldDef.builder()
                    .key("billed_credits")
                    .type("number")
                    .description("What the platform charged for this run, in credits, as it was "
                        + "committed. Read this rather than working the cost out from "
                        + "billed_quantity and a rate: the rate can be republished between the run "
                        + "and the reading. ABSENT means the platform charged nothing - "
                        + "credential_source='user' pays the provider directly - and absent is not "
                        + "a charge of zero")
                    .build(),
                OutputFieldDef.builder()
                    .key("provider_response")
                    .type("object")
                    .description("The provider's own payload, kept under its own key so a provider field "
                        + "can never shadow file or model. Shape varies by provider; nothing downstream "
                        + "should depend on it")
                    .build()
            ))
            .keywords(List.of("generate", "generation", "image", "video", "audio", "voice", "music",
                "tts", "speech", "text-to-image", "text-to-video", "text-to-speech", "prompt",
                "asset", "render", "create"))
            .build();
    }
}
