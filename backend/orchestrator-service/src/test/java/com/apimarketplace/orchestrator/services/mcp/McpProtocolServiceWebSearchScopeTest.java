package com.apimarketplace.orchestrator.services.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Knock-on guard for the LC-029 reclassification of {@code web_search:fetch} from READ to
 * WRITE in {@code ToolAccessControl}.
 *
 * <p>{@link McpProtocolService#restrictionCredentials} derives an API key's per-tool access
 * mode from its granted actions ("all reads" means read mode). Making fetch a write action
 * therefore flips a key scoped {@code web_search.fetch} from read mode to write mode. That
 * flip must NOT hand the key the write actions it was never granted: the per-action scope
 * check ({@link McpProtocolService#actionInScope}, LC-054) runs before execution and is
 * independent of the derived mode.
 *
 * <p>Without this pairing the reclassification would be a widening, which is the opposite of
 * what the finding asks for.
 */
@DisplayName("LC-029 knock-on: a fetch-scoped MCP key is not widened by fetch becoming a write")
class McpProtocolServiceWebSearchScopeTest {

    @Test
    @DisplayName("a key scoped web_search.fetch now derives write mode (fetch is no longer a read)")
    void fetchOnlyKeyDerivesWriteMode() {
        assertThat(McpProtocolService.restrictionCredentials(Set.of("web_search.fetch")))
                .containsEntry("web_searchAccessMode", "write");
    }

    @Test
    @DisplayName("a key scoped web_search.search still derives read mode")
    void searchOnlyKeyDerivesReadMode() {
        assertThat(McpProtocolService.restrictionCredentials(Set.of("web_search.search")))
                .containsEntry("web_searchAccessMode", "read");
    }

    @Test
    @DisplayName("the write mode does not unlock agent_browse: the action was never granted")
    void writeModeDoesNotUnlockUngrantedActions() {
        Set<String> scopes = Set.of("web_search.fetch");

        assertThat(McpProtocolService.actionInScope(
                "web_search", Map.of("action", "agent_browse"), scopes)).isFalse();
        assertThat(McpProtocolService.actionInScope(
                "web_search", Map.of("action", "browse_abort"), scopes)).isFalse();
        assertThat(McpProtocolService.actionInScope(
                "web_search", Map.of("action", "fetch"), scopes)).isTrue();
    }
}
