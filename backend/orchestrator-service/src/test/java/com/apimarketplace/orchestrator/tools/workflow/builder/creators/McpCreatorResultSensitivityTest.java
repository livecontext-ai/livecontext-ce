package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.common.classification.RestrictedDataPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CASA LC-066, re-audit 2026-09-29: adding a Gmail node to a workflow plan returned a result whose
 * metadata named Gmail (the node card's icon), so the result was classified restricted data. On a
 * model outside the allow-list the agent then got the refusal in place of the saved node, although
 * no mailbox was read.
 */
@DisplayName("McpCreator add_node result - a node card is not Gmail data (LC-066)")
class McpCreatorResultSensitivityTest {

    @Test
    @DisplayName("regression: adding a Gmail node returns a result that is not restricted data")
    void gmailNodeCardIsNotRestrictedData() {
        Map<String, Object> metadata = McpCreator.resultMetadata("gmail", "list_messages", "Fetch mail", true);

        assertThat(metadata).containsEntry("iconSlug", "gmail").containsEntry("credentialRequired", true);
        assertThat(RestrictedDataPolicy.fromToolMetadata(metadata).isRestricted()).isFalse();
    }

    @Test
    @DisplayName("a node with no icon carries no flag, only what the card is drawn with")
    void iconlessNodeCarriesNoFlag() {
        assertThat(McpCreator.resultMetadata(null, null, "Transform", false))
            .containsOnlyKeys("label");
    }
}
