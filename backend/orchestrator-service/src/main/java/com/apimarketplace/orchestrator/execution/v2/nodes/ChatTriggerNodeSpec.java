package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import com.apimarketplace.orchestrator.services.persistence.schema.GenericOutputSchemaMapper;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class ChatTriggerNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("CHAT_TRIGGER")
            .label("Chat Trigger")
            .category("trigger")
            .variablePrefix("trigger")
            .description("Triggered by a chat message")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("message")
                    .type("string")
                    .description("The chat message that triggered the workflow")
                    .build(),
                OutputFieldDef.builder()
                    .key("extracted_message")
                    .type("string")
                    .description("Message after pattern extraction (prefix/suffix trimmed)")
                    .aliases(List.of("extractedMessage"))
                    .build(),
                OutputFieldDef.builder()
                    .key("conversation_id")
                    .type("string")
                    .description("Conversation identifier")
                    .aliases(List.of("conversationId"))
                    .build(),
                OutputFieldDef.builder()
                    .key("attachments")
                    .type("array")
                    .description("File attachments sent with the message - each item is a canonical FileRef ({_type:'file', path, name, mimeType, size}); drop into <img src=\"{{photo}}\"/> via variable_mapping {'photo':'{{trigger:chat.output.attachments[0]}}'}")
                    .build(),
                OutputFieldDef.builder()
                    .key("matched")
                    .type("boolean")
                    .description("Whether the message matched the trigger pattern")
                    .build(),
                OutputFieldDef.builder()
                    .key("match_type")
                    .type("string")
                    .description("Pattern type used (any, starts_with, ends_with, contains, equals, regex)")
                    .aliases(List.of("matchType"))
                    .build(),
                OutputFieldDef.builder()
                    .key("match_value")
                    .type("string")
                    .description("The pattern value matched against")
                    .aliases(List.of("matchValue"))
                    .build(),
                OutputFieldDef.builder()
                    .key("triggered_at")
                    .type("datetime")
                    .description("ISO timestamp when triggered")
                    .defaultValue("__NOW__")
                    .aliases(List.of("triggeredAt"))
                    .build(),
                OutputFieldDef.builder()
                    .key("triggered_by")
                    .type("string")
                    .description("Display name of the user who triggered the workflow (empty string when unknown). Never the raw tenantId.")
                    .defaultValue("")
                    .aliases(List.of("triggeredBy"))
                    .build(),
                OutputFieldDef.builder()
                    .key("trigger_id")
                    .type("string")
                    .description("Internal id of the trigger that fired")
                    .build(),
                OutputFieldDef.builder()
                    .key("item_id")
                    .type("string")
                    .description("Item identifier for split-context tracking")
                    .build(),
                OutputFieldDef.builder()
                    .key("item_index")
                    .type("number")
                    .description("Index when iterating over items")
                    .build(),
                OutputFieldDef.builder()
                    .key("data")
                    .type("array")
                    .description("Raw trigger data items emitted by the resolver (one entry per matched message)")
                    .build(),
                OutputFieldDef.builder()
                    .key("count")
                    .type("number")
                    .description("Number of items in the data array (0 when no match, 1 when matched)")
                    .build()
            ))
            .keywords(List.of("chat", "message", "trigger", "file", "attachment"))
            .build();
    }

    /**
     * Custom transform: resolver now emits canonical snake_case keys and flattened attachments
     * directly, so this transform is a passthrough - it copies all keys verbatim.
     *
     * Metadata-only keys that should not appear in persisted output are omitted:
     * triggerId, tenantId, type, source, status (internal resolver fields).
     */
    @Override
    public Map<String, Object> customTransform(Map<String, Object> backendOutput) {
        Map<String, Object> dbOutput = new HashMap<>(backendOutput);
        // Remove internal resolver metadata that should not leak into persisted output
        dbOutput.remove("triggerId");
        dbOutput.remove("tenantId");
        dbOutput.remove("type");
        dbOutput.remove("source");
        dbOutput.remove("status");
        // Remove universal engine-envelope keys
        GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS.forEach(dbOutput::remove);
        return dbOutput;
    }
}
