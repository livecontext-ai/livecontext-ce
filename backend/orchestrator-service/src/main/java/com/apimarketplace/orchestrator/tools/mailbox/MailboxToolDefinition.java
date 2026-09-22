package com.apimarketplace.orchestrator.tools.mailbox;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.boolParam;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.enumParam;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.generateInputSchema;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.intParam;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.stringParam;

/**
 * What the agent is told about {@code mailbox}, and nothing it cannot act on.
 *
 * <p>Split from the provider because the text is the larger half and it changes for different
 * reasons than the execution does.
 */
final class MailboxToolDefinition {

    private MailboxToolDefinition() {
    }

    static AgentToolDefinition build() {
        List<ToolParameter> params = List.of(
                enumParam("action",
                        "read | folders | create_folder | send | mark_read | mark_unread | flag | "
                                + "unflag | move | delete | help",
                        true, MailboxToolsProvider.validActions().stream().sorted().toList()),
                stringParam("folder",
                        "Mailbox folder to read or act on. Defaults to INBOX. Use action='folders' to see what exists.",
                        false),
                intParam("limit", "read: how many messages to return, most recent first. 1-100, default 10.",
                        false, 10),
                boolParam("unread_only", "read: only messages that have not been seen. Default false.",
                        false, false),
                intParam("since_days", "read: only messages received in the last N days. 0 (default) = no limit.",
                        false, 0),
                intParam("before_days", "read: only messages OLDER than N days. 0 (default) = no limit.",
                        false, 0),
                stringParam("from_contains", "read: keep messages whose sender contains this text.", false),
                stringParam("subject_contains", "read: keep messages whose subject contains this text.", false),
                stringParam("body_contains", "read: keep messages whose body contains this text.", false),
                boolParam("flagged_only", "read: only flagged messages. Default false.", false, false),
                boolParam("mark_seen", "read: mark the messages this call returns as seen. Default false.",
                        false, false),
                stringParam("message_uid",
                        "Required by mark_read, mark_unread, flag, unflag, move and delete. The `uid` of a "
                                + "message returned by action='read'.",
                        false),
                stringParam("target_folder", "move: the folder to move the message into.", false),
                boolParam("create_target_if_missing",
                        "move: create target_folder when it does not exist. Default false.", false, false),
                stringParam("to", "send: recipient address. Required for send.", false),
                stringParam("cc", "send: carbon-copy address.", false),
                stringParam("bcc", "send: blind carbon-copy address.", false),
                stringParam("subject", "send: subject line. Required for send.", false),
                stringParam("body", "send: message body.", false),
                boolParam("is_html", "send: treat body as HTML rather than plain text. Default false.",
                        false, false),
                stringParam("from_email", "send: sender address, when it differs from the SMTP account.", false),
                stringParam("from_name", "send: display name for the sender.", false),
                stringParam("reply_to", "send: address replies should go to.", false),
                stringParam("in_reply_to",
                        "send: the `messageId` of the message being replied to (camelCase, as read returns it), so the reply threads under it.",
                        false),
                stringParam("references", "send: References header, for threading.", false),
                intParam("credential_id",
                        "Use a specific connected mailbox instead of the default one. Omit unless the account "
                                + "holds several and you were told which. An unavailable selection fails without using another account.",
                        false, null));

        String description = """
                Read and send email on the account's own mailbox, over IMAP and SMTP.

                - read: list messages from a folder, newest first, with filters (unread_only,
                  since_days, before_days, from_contains, subject_contains, body_contains,
                  flagged_only). Each message carries a `uid`, which is what every other action
                  takes, AND its full body, plain and html: there is nothing further to open.
                  Attachment bytes are NOT downloaded; the message lists what it carries.
                - folders: the folder names this mailbox has. create_folder makes one
                  (target_folder).
                - mark_read / mark_unread / flag / unflag / delete: act on one message_uid.
                - move: move one message_uid into target_folder.
                - send: send a message (to, subject, body). Reply in-thread by passing
                  in_reply_to = the messageId of what you are replying to.

                CREDENTIALS: reading uses the account's IMAP credential, sending its SMTP one, and
                they are separate. Neither is something you can supply in the call: a server named
                by the caller is not something this tool will connect to. When one is missing, the
                refusal says which, and credential(action='require', services=['imap']) (or
                ['smtp']) puts a Connect card on the user's screen.

                A mailbox connected here is the SAME one a workflow's email nodes use, so anything
                you can read in chat, a workflow you build can read too.
                """;

        return AgentToolDefinition.builder()
                .name(MailboxToolsProvider.TOOL_NAME)
                .description(description)
                .category(ToolCategory.UTILITY)
                .parameters(params)
                .requiredParameters(List.of("action"))
                .inputSchema(generateInputSchema(params, List.of("action")))
                .helpText("Call mailbox(action='help') for the per-action reference.")
                .requiresAuth(true)
                .tags(List.of("mailbox", "email", "imap", "smtp", "inbox", "mail", "send"))
                // IMAP connect plus fetch on a large folder is slow on some providers; the
                // node's own socket timeouts land well inside this.
                .timeoutMs(180_000L)
                .build();
    }

    static Map<String, Object> help() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("description",
                "MAILBOX - read and send email on the account's connected mailbox. Reading is IMAP, "
                        + "sending is SMTP, and each has its own credential.");

        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("read", Map.of(
                "summary", "List messages from a folder, newest first.",
                "params", "folder (default INBOX), limit (1-100, default 10), unread_only, since_days, "
                        + "before_days, from_contains, subject_contains, body_contains, flagged_only, mark_seen",
                "returns", "{ messages: [ { uid, messageId, subject, from, to, cc, replyTo, date, "
                        + "seen, flagged, folder, references, body, bodyHtml, snippet, "
                        + "hasAttachments, attachments: [ { filename, contentType, size } ] } ], "
                        + "count, folder }. The FULL body is already there, plain and html: there is "
                        + "no per-message open, and re-reading to get it is a wasted call. `uid` is "
                        + "the handle every other action takes; `messageId` is what "
                        + "send(in_reply_to=...) takes. Both are camelCase, and they are different "
                        + "values: uid addresses the message in THIS folder, messageId is the RFC id "
                        + "that threads a reply. The filters you sent back come under "
                        + "resolved_params (limit, unreadOnly, flaggedOnly), not at the top."));
        actions.put("folders", Map.of(
                "summary", "The folder names this mailbox has.",
                "params", "none",
                "returns", "{ folders: [names], count }"));
        actions.put("create_folder", Map.of(
                "summary", "Create a folder.",
                "params", "target_folder (required) - the name to create",
                "returns", "{ action, folder, created, folders: [names] }. created=false means it "
                        + "was already there, which is not a failure."));
        actions.put("send", Map.of(
                "summary", "Send a message over SMTP.",
                "params", "to (required), subject (required), body, cc, bcc, is_html, from_email, "
                        + "from_name, reply_to, in_reply_to, references",
                "returns", "{ sent: true, messageId, recipients, subject, isHtml }. `messageId` is "
                        + "the id of the message you just sent, not of anything you read."));
        actions.put("message actions", Map.of(
                "summary", "mark_read, mark_unread, flag, unflag, delete act on one message; move also "
                        + "takes target_folder.",
                "params", "message_uid (required), target_folder + create_target_if_missing for move",
                "returns", "{ action, messageUid, folder }, plus newMessageUid on a move when the "
                        + "server issues one. A move CHANGES the uid: the old one addresses nothing "
                        + "afterwards, so act again with newMessageUid. The folder you asked for is "
                        + "echoed under resolved_params.targetFolder, not at the top."));
        actions.put("help", Map.of("summary", "This payload. No params."));
        out.put("actions", actions);

        out.put("credentials", List.of(
                "Reading needs an IMAP credential, sending an SMTP one. They are separate, and a mailbox "
                        + "that can be read cannot necessarily be sent from.",
                "You cannot pass a host or a password in the call. Only a mailbox the user connected is used.",
                "Missing one? Relay the refusal and call credential(action='require', services=['imap']) "
                        + "or services=['smtp']. Only the user can complete it.",
                "credential_id selects a specific mailbox when the account connected several. Omit it "
                        + "otherwise and the default one is used. If the selected account is unavailable, "
                        + "relay the refusal to the user; do not omit the selection to retry with another mailbox."));

        out.put("sequence", List.of(
                "read the folder -> take a message `uid` -> act on it (mark_read / move / delete).",
                "read -> take a message `messageId` -> send(in_reply_to=<that messageId>) to reply in thread.",
                "Acting on a message you have not read means guessing a uid; read first."));

        out.put("notes", List.of(
                "Attachment bytes are not fetched by read. The message says what it carries.",
                "delete follows the server's own rule for deletion: on most providers the message moves "
                        + "to Trash rather than disappearing.",
                "The same mailbox is available to a workflow's email nodes, so a scheduled version of what "
                        + "you are doing needs no new setup."));

        out.put("examples", List.of(
                Map.of("action", "read", "folder", "INBOX", "unread_only", true, "limit", 20,
                        "comment", "The 20 most recent unread messages."),
                Map.of("action", "read", "since_days", 2, "subject_contains", "invoice",
                        "comment", "Invoices from the last two days."),
                Map.of("action", "move", "message_uid", "4711", "target_folder", "Archive",
                        "comment", "Archive one message read earlier."),
                Map.of("action", "send", "to", "someone@example.com", "subject", "Re: invoice",
                        "body", "Received, thank you.", "in_reply_to", "<abc@mail.example.com>",
                        "comment", "Reply in thread.")));

        return out;
    }
}
