package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SendEmailNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("SEND_EMAIL")
            .label("Send Email")
            .category("core")
            .variablePrefix("core")
            .description("Sends an email message")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("sent")
                    .type("boolean")
                    .description("Whether the email was sent")
                    .defaultValue(false)
                    .build(),
                OutputFieldDef.builder()
                    .key("messageId")
                    .type("string")
                    .description("Message ID from the mail server")
                    .build(),
                OutputFieldDef.builder()
                    .key("recipients")
                    .type("array")
                    .description("List of recipient email addresses")
                    .build(),
                OutputFieldDef.builder()
                    .key("subject")
                    .type("string")
                    .description("Email subject line")
                    .build(),
                OutputFieldDef.builder()
                    .key("isHtml")
                    .type("boolean")
                    .description("Whether the email body is HTML")
                    .defaultValue(false)
                    .build(),
                OutputFieldDef.builder()
                    .key("success")
                    .type("boolean")
                    .description("Whether the operation was successful")
                    .defaultValue(false)
                    .build()
            ))
            .keywords(List.of("email", "send", "mail", "smtp"))
            .build();
    }
}
