package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EmailInboxNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("EMAIL_INBOX")
            .label("Email Inbox")
            .category("core")
            .variablePrefix("core")
            .description("Reads messages and performs mailbox actions (mark read/unread, flag, move, delete) over IMAP")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("messages")
                    .type("array")
                    .description("Messages read from the folder (READ mode). Each item: uid, from, to, cc, replyTo, subject, date, seen, flagged, folder, messageId, references, body, bodyHtml, snippet, hasAttachments, attachments[{filename, contentType, size, file?}]")
                    .build(),
                OutputFieldDef.builder()
                    .key("count")
                    .type("number")
                    .description("Number of messages returned (READ mode), or folder count (list_folders)")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("folders")
                    .type("array")
                    .description("Mailbox folder names (list_folders action)")
                    .build(),
                OutputFieldDef.builder()
                    .key("folder")
                    .type("string")
                    .description("The mailbox folder that was read or acted on")
                    .build(),
                OutputFieldDef.builder()
                    .key("action")
                    .type("string")
                    .description("The mailbox action performed (ACTION mode): mark_read, mark_unread, flag, unflag, move, delete")
                    .build(),
                OutputFieldDef.builder()
                    .key("messageUid")
                    .type("number")
                    .description("IMAP UID of the message acted on (ACTION mode)")
                    .build(),
                OutputFieldDef.builder()
                    .key("success")
                    .type("boolean")
                    .description("Whether the operation was successful")
                    .defaultValue(false)
                    .build()
            ))
            .keywords(List.of("email", "inbox", "read", "imap", "mailbox", "receive"))
            .build();
    }
}
