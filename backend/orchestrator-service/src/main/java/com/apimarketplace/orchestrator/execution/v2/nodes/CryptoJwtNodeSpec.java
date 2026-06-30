package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CryptoJwtNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("CRYPTO_JWT")
            .label("Crypto/JWT")
            .category("core")
            .variablePrefix("core")
            .description("Performs cryptographic or JWT operations")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("result")
                    .type("string")
                    .description("The cryptographic/JWT operation result")
                    .build(),
                OutputFieldDef.builder()
                    .key("operation")
                    .type("string")
                    .description("The operation performed")
                    .defaultValue("hash")
                    .build()
            ))
            .keywords(List.of("crypto", "jwt", "hash", "encrypt", "decrypt", "sign"))
            .build();
    }
}
