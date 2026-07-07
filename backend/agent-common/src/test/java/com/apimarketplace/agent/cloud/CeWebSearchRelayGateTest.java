package com.apimarketplace.agent.cloud;

import com.apimarketplace.agent.domain.ToolDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CeWebSearchRelayGate")
class CeWebSearchRelayGateTest {

    private static final String TENANT = "7";

    @Mock
    private CloudLlmRuntimeAccess runtimeAccess;

    private static ToolDefinition tool(String name) {
        return ToolDefinition.builder().id(name).name(name).description(name).build();
    }

    @Nested
    @DisplayName("cloud / local engine enabled")
    class LocalEnabled {

        @Test
        @DisplayName("web_search is exposable and available without consulting the cloud link")
        void alwaysAvailableWithoutLinkLookup() {
            CeWebSearchRelayGate gate = new CeWebSearchRelayGate(true, runtimeAccess);

            assertThat(gate.isWebSearchExposable()).isTrue();
            assertThat(gate.isWebSearchAvailable(TENANT)).isTrue();
            assertThat(gate.isRelayWired()).isFalse();
            verifyNoInteractions(runtimeAccess);
        }

        @Test
        @DisplayName("filterExposedTools is a no-op")
        void filterIsNoOp() {
            CeWebSearchRelayGate gate = new CeWebSearchRelayGate(true, runtimeAccess);
            List<ToolDefinition> tools = List.of(tool("web_search"), tool("catalog"));

            assertThat(gate.filterExposedTools(tools, TENANT)).isSameAs(tools);
        }
    }

    @Nested
    @DisplayName("CE, relay wired (websearch.enabled=false + runtime access present)")
    class RelayWired {

        @Test
        @DisplayName("web_search is exposable (cache-level) regardless of any single tenant's link")
        void exposableWhenWired() {
            CeWebSearchRelayGate gate = new CeWebSearchRelayGate(false, runtimeAccess);

            assertThat(gate.isRelayWired()).isTrue();
            assertThat(gate.isWebSearchExposable()).isTrue();
        }

        @Test
        @DisplayName("tenant with CLOUD source → available")
        void cloudLinkedTenantIsAvailable() {
            when(runtimeAccess.isCloudSelected(TENANT)).thenReturn(true);
            CeWebSearchRelayGate gate = new CeWebSearchRelayGate(false, runtimeAccess);

            assertThat(gate.isWebSearchAvailable(TENANT)).isTrue();
        }

        @Test
        @DisplayName("unlinked / BYOK tenant → NOT available")
        void byokTenantIsNotAvailable() {
            when(runtimeAccess.isCloudSelected(TENANT)).thenReturn(false);
            CeWebSearchRelayGate gate = new CeWebSearchRelayGate(false, runtimeAccess);

            assertThat(gate.isWebSearchAvailable(TENANT)).isFalse();
        }

        @Test
        @DisplayName("link-state resolution failure → NOT available (fail-closed)")
        void resolutionFailureFailsClosed() {
            when(runtimeAccess.isCloudSelected(TENANT))
                    .thenThrow(new IllegalStateException("publication-service unreachable"));
            CeWebSearchRelayGate gate = new CeWebSearchRelayGate(false, runtimeAccess);

            assertThat(gate.isWebSearchAvailable(TENANT)).isFalse();
        }

        @Test
        @DisplayName("null/blank tenant → NOT available, no link lookup")
        void nullOrBlankTenantIsNotAvailable() {
            CeWebSearchRelayGate gate = new CeWebSearchRelayGate(false, runtimeAccess);

            assertThat(gate.isWebSearchAvailable(null)).isFalse();
            assertThat(gate.isWebSearchAvailable("  ")).isFalse();
            verifyNoInteractions(runtimeAccess);
        }

        @Test
        @DisplayName("filterExposedTools keeps web_search for a cloud-linked tenant")
        void filterKeepsToolForLinkedTenant() {
            when(runtimeAccess.isCloudSelected(TENANT)).thenReturn(true);
            CeWebSearchRelayGate gate = new CeWebSearchRelayGate(false, runtimeAccess);
            List<ToolDefinition> tools = List.of(tool("web_search"), tool("catalog"));

            assertThat(gate.filterExposedTools(tools, TENANT)).isSameAs(tools);
        }

        @Test
        @DisplayName("filterExposedTools removes ONLY web_search for an unlinked tenant")
        void filterRemovesOnlyWebSearchForUnlinkedTenant() {
            when(runtimeAccess.isCloudSelected(TENANT)).thenReturn(false);
            CeWebSearchRelayGate gate = new CeWebSearchRelayGate(false, runtimeAccess);

            List<ToolDefinition> filtered = gate.filterExposedTools(
                    List.of(tool("web_search"), tool("catalog"), tool("workflow")), TENANT);

            assertThat(filtered).extracting(ToolDefinition::name)
                    .containsExactly("catalog", "workflow");
        }

        @Test
        @DisplayName("filterExposedTools without web_search in the list skips the link lookup")
        void filterWithoutWebSearchSkipsLookup() {
            CeWebSearchRelayGate gate = new CeWebSearchRelayGate(false, runtimeAccess);
            List<ToolDefinition> tools = List.of(tool("catalog"));

            assertThat(gate.filterExposedTools(tools, TENANT)).isSameAs(tools);
            verify(runtimeAccess, never()).isCloudSelected(TENANT);
        }
    }

    @Nested
    @DisplayName("CE, relay NOT wired (websearch.enabled=false, no runtime access)")
    class RelayNotWired {

        @Test
        @DisplayName("web_search is neither exposable nor available - pre-relay CE behavior")
        void notExposableNotAvailable() {
            CeWebSearchRelayGate gate = new CeWebSearchRelayGate(false, null);

            assertThat(gate.isRelayWired()).isFalse();
            assertThat(gate.isWebSearchExposable()).isFalse();
            assertThat(gate.isWebSearchAvailable(TENANT)).isFalse();
        }

        @Test
        @DisplayName("filterExposedTools removes web_search")
        void filterRemovesWebSearch() {
            CeWebSearchRelayGate gate = new CeWebSearchRelayGate(false, null);

            List<ToolDefinition> filtered = gate.filterExposedTools(
                    List.of(tool("web_search"), tool("catalog")), TENANT);

            assertThat(filtered).extracting(ToolDefinition::name).containsExactly("catalog");
        }

        @Test
        @DisplayName("filterExposedTools handles null and empty lists")
        void filterHandlesNullAndEmpty() {
            CeWebSearchRelayGate gate = new CeWebSearchRelayGate(false, null);

            assertThat(gate.filterExposedTools(null, TENANT)).isNull();
            assertThat(gate.filterExposedTools(List.of(), TENANT)).isEmpty();
        }
    }

    @Nested
    @DisplayName("removeWebSearch (availability-free drop for callers that already resolved it)")
    class RemoveWebSearch {

        @Test
        @DisplayName("removes ONLY web_search, preserving order of the rest")
        void removesOnlyWebSearch() {
            List<ToolDefinition> filtered = CeWebSearchRelayGate.removeWebSearch(
                    List.of(tool("catalog"), tool("web_search"), tool("workflow")));

            assertThat(filtered).extracting(ToolDefinition::name)
                    .containsExactly("catalog", "workflow");
        }

        @Test
        @DisplayName("handles null and empty lists")
        void handlesNullAndEmpty() {
            assertThat(CeWebSearchRelayGate.removeWebSearch(null)).isNull();
            assertThat(CeWebSearchRelayGate.removeWebSearch(List.of())).isEmpty();
        }
    }
}
