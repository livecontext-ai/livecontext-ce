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
            .description("Reads messages and performs mailbox actions (mark read/unread, flag, move, delete, create folder) over IMAP")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("messages")
                    .type("array")
                    .description("Messages read from the folder (READ mode). Each item: uid, from, to, cc, replyTo, subject, date, seen, flagged, folder, messageId, references, body, bodyHtml, snippet, hasAttachments, attachments[{filename, contentType, size, file?}]")
                    .build(),
                OutputFieldDef.builder()
                    .key("count")
                    .type("number")
                    .description("Number of live messages returned (READ mode; messages flagged deleted and awaiting expunge are excluded), or folder count (list_folders)")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("folders")
                    .type("array")
                    .description("Mailbox folder names, as server paths (list_folders and create_folder actions)")
                    .build(),
                OutputFieldDef.builder()
                    .key("folder")
                    .type("string")
                    .description("The mailbox folder that was read, acted on, or created")
                    .build(),
                OutputFieldDef.builder()
                    .key("created")
                    .type("boolean")
                    .description("create_folder action: true when the folder was created, false when it already existed")
                    .defaultValue(false)
                    .build(),
                OutputFieldDef.builder()
                    .key("action")
                    .type("string")
                    .description("The mailbox action performed (ACTION mode): mark_read, mark_unread, flag, unflag, move, delete, create_folder")
                    .build(),
                OutputFieldDef.builder()
                    .key("messageUid")
                    .type("number")
                    .description("IMAP UID of the message acted on (ACTION mode). For move this is the SOURCE uid, which is invalid after the move; use newMessageUid when present")
                    .build(),
                OutputFieldDef.builder()
                    .key("newMessageUid")
                    .type("number")
                    .description("move only: UID of the message in the target folder, present only when the IMAP server supports UIDPLUS/COPYUID. Absent otherwise (re-read the target folder to find the message)")
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
