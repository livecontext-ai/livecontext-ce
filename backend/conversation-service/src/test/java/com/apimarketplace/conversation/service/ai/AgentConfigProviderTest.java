package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.conversation.service.ai.AgentConfigProvider.AgentConfig;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider.AvailableModel;
import com.apimarketplace.conversation.service.ai.AgentConfigProvider.ToolsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("AgentConfigProvider")
@ExtendWith(MockitoExtension.class)
class AgentConfigProviderTest {

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private AgentConfigProvider agentConfigProvider;

    @Nested
    @DisplayName("getAgentConfig")
    class GetAgentConfig {

        @Test
        @DisplayName("should return null for null agentId")
        void shouldReturnNullForNullAgentId() {
            AgentConfig result = agentConfigProvider.getAgentConfig(null, "tenant-1");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return null for blank agentId")
        void shouldReturnNullForBlankAgentId() {
            AgentConfig result = agentConfigProvider.getAgentConfig("  ", "tenant-1");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should parse agent config from JSON response")
        void shouldParseAgentConfigFromResponse() {
            String jsonResponse = """
                {
                    "name": "My Agent",
                    "systemPrompt": "You are helpful.",
                    "modelProvider": "openai",
                    "modelName": "gpt-4",
                    "temperature": 0.7,
                    "maxTokens": 2000,
                    "maxIterations": 5,
                    "toolsConfig": {
                        "mode": "custom",
                        "tools": ["tool-1", "tool-2"],
                        "workflows": ["wf-1"]
                    }
                }
                """;

            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

            AgentConfig config = agentConfigProvider.getAgentConfig("agent-1", "tenant-1");

            assertThat(config).isNotNull();
            assertThat(config.agentId()).isEqualTo("agent-1");
            assertThat(config.name()).isEqualTo("My Agent");
            assertThat(config.systemPrompt()).isEqualTo("You are helpful.");
            assertThat(config.modelProvider()).isEqualTo("openai");
            assertThat(config.modelName()).isEqualTo("gpt-4");
            assertThat(config.temperature()).isEqualTo(0.7);
            assertThat(config.maxTokens()).isEqualTo(2000);
            assertThat(config.maxIterations()).isEqualTo(5);
            assertThat(config.toolsConfig()).isNotNull();
            assertThat(config.toolsConfig().mode()).isEqualTo("custom");
            assertThat(config.toolsConfig().tools()).containsExactly("tool-1", "tool-2");
            assertThat(config.toolsConfig().workflows()).containsExactly("wf-1");
        }

        @Test
        @DisplayName("sends organization headers when resolving org-scoped agent config")
        void sendsOrganizationHeadersForOrgScopedAgentConfig() {
            String jsonResponse = """
                {
                    "name": "Org Agent",
                    "maxIterations": 2
                }
                """;

            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

            AgentConfig config = agentConfigProvider.getAgentConfig("agent-1", "tenant-1", "org-42", "MEMBER");

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.GET), entityCaptor.capture(), eq(String.class));
            assertThat(config).isNotNull();
            assertThat(config.maxIterations()).isEqualTo(2);
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo("tenant-1");
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID")).isEqualTo("org-42");
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-Role")).isEqualTo("MEMBER");
        }

        @Test
        @DisplayName("Parse: toolsConfig without `mode` still produces 5 internal lists (regression)")
        void parseToolsConfigWithoutModeStillProduces5InternalLists() {
            // Pre-fix: parseToolsConfig short-circuited with `if (mode == null)
            // return null;`. The result was that an agent persisted with absent
            // `mode` (legacy / V163-backfilled) skipped applyToolsConfigCredentials
            // entirely → no __allowed*Ids__ credentials were posted → tool modules
            // saw null and treated as "no restriction" → unrestricted tenant access.
            // This was the deepest leg of the "absent ≠ all" leak.
            //
            // Post-fix: absent `mode` defaults to "all" inside the parser; the 5
            // internal lists are still parsed and the predicates report "no access"
            // for those that arrive empty. Companion write-side: V163 + normalizeToolsConfig
            // both seed `mode='all'` so this code path is rare in practice - but the
            // test pins the contract so the parser cannot regress to short-circuiting.
            String jsonResponse = """
                {
                    "name": "Legacy Agent",
                    "toolsConfig": {
                        "workflows": [],
                        "tables": [],
                        "interfaces": [],
                        "agents": [],
                        "applications": []
                    }
                }
                """;

            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

            AgentConfig config = agentConfigProvider.getAgentConfig("agent-1", "tenant-1");

            assertThat(config).isNotNull();
            assertThat(config.toolsConfig()).isNotNull();
            assertThat(config.toolsConfig().mode()).isEqualTo("all");
            assertThat(config.toolsConfig().isWorkflowsNone()).isTrue();
            assertThat(config.toolsConfig().isTablesNone()).isTrue();
            assertThat(config.toolsConfig().isInterfacesNone()).isTrue();
            assertThat(config.toolsConfig().isAgentsNone()).isTrue();
            assertThat(config.toolsConfig().isApplicationsNone()).isTrue();
        }

        @Test
        @DisplayName("Parse: toolsConfig.files is parsed into the file allow-list (chat-path agent file scoping)")
        void parseToolsConfigFilesAllowList() {
            // Regression: the chat path dropped toolsConfig.files (the ToolsConfig record had
            // no `files` field), so a file-scoped agent reached the files tool unrestricted in
            // chat. The parser must now carry files through so __allowedFileIds__ is emitted.
            String jsonResponse = """
                {
                    "name": "Scoped Agent",
                    "toolsConfig": { "mode": "none", "files": ["file-a", "file-b"] }
                }
                """;

            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

            AgentConfig config = agentConfigProvider.getAgentConfig("agent-1", "tenant-1");

            assertThat(config).isNotNull();
            assertThat(config.toolsConfig()).isNotNull();
            assertThat(config.toolsConfig().files()).containsExactly("file-a", "file-b");
            assertThat(config.toolsConfig().isFilesScoped()).isTrue();
            assertThat(config.toolsConfig().toMap()).containsEntry("files", List.of("file-a", "file-b"));
        }

        @Test
        @DisplayName("Parse: toolsConfig.fileAccessMode='read' is carried through (chat-path read-only file enforcement)")
        void parseToolsConfigFileAccessMode() {
            String jsonResponse = """
                {
                    "name": "Read-only Files Agent",
                    "toolsConfig": { "mode": "all", "fileAccessMode": "read" }
                }
                """;

            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

            AgentConfig config = agentConfigProvider.getAgentConfig("agent-1", "tenant-1");

            assertThat(config).isNotNull();
            assertThat(config.toolsConfig()).isNotNull();
            assertThat(config.toolsConfig().fileAccessMode()).isEqualTo("read");
        }

        @Test
        @DisplayName("Parse: absent toolsConfig.fileAccessMode → null (default 'write' resolved downstream)")
        void parseToolsConfigFileAccessModeAbsent() {
            String jsonResponse = """
                { "name": "Default Files Agent", "toolsConfig": { "mode": "all" } }
                """;

            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

            AgentConfig config = agentConfigProvider.getAgentConfig("agent-1", "tenant-1");

            assertThat(config.toolsConfig().fileAccessMode()).isNull();
        }

        @Test
        @DisplayName("Parse: workflowsGrant='all' makes the BUILDER agent unrestricted on workflows even with an empty list")
        void parseToolsConfigWorkflowsGrantAll() {
            // A BUILDER agent durably granted "all" workflows: the grant drives, the
            // list is a placeholder. isWorkflowsAll true; None/Custom false → no
            // __allowedWorkflowIds__ credential is posted → unrestricted access.
            String jsonResponse = """
                {
                    "name": "Builder Agent",
                    "toolsConfig": { "mode": "all", "workflows": [], "workflowsGrant": "all" }
                }
                """;

            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

            AgentConfig config = agentConfigProvider.getAgentConfig("agent-1", "tenant-1");

            assertThat(config).isNotNull();
            assertThat(config.toolsConfig()).isNotNull();
            assertThat(config.toolsConfig().isWorkflowsAll()).isTrue();
            assertThat(config.toolsConfig().isWorkflowsNone()).isFalse();
            assertThat(config.toolsConfig().isWorkflowsCustom()).isFalse();
            assertThat(config.toolsConfig().toMap()).containsEntry("workflowsGrant", "all");
        }

        @Test
        @DisplayName("Parse: an UNRECOGNISED grant (e.g. 'bogus') resolves to 'none' (deny-by-default) - the read side must NOT fail OPEN")
        void parseToolsConfigInvalidGrantResolvesToNone() {
            // Regression for the fail-open found in the live MCP audit: pre-fix isWorkflowsNone
            // was (grant == null || "none".equals(grant)), so a junk grant was none-of-the-three
            // → AgentContextBuilder emitted NO restriction → __allowedWorkflowIds__ omitted →
            // ToolAccessControl.getAllowedIds == null == UNRESTRICTED. Now isXNone is the
            // complement of all/custom, so 'bogus' (or any unknown) denies. The id list present
            // here must NOT leak access either.
            // A junk grant on EVERY family, each with a non-empty list and a different garbage
            // value (incl. "" and a numeric-string) - none of which is none/all/custom.
            String jsonResponse = """
                {
                    "name": "Junk Grant Agent",
                    "toolsConfig": {
                        "mode": "all",
                        "workflows": ["wf-x"],   "workflowsGrant": "bogus",
                        "tables": ["t-x"],       "tablesGrant": "GARBAGE",
                        "interfaces": ["i-x"],   "interfacesGrant": "123",
                        "agents": ["a-x"],       "agentsGrant": " all ",
                        "applications": ["p-x"], "applicationsGrant": ""
                    }
                }
                """;

            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

            var tc = agentConfigProvider.getAgentConfig("agent-1", "tenant-1").toolsConfig();

            // Every family: NOT all, NOT custom → None (deny). Pre-fix isXNone was FALSE for a
            // junk value (none-of-the-three) → __allowed*Ids__ omitted → fail-OPEN. Tested for
            // all 5 families, not just workflows; the non-empty list must NOT leak access.
            assertThat(tc.isWorkflowsAll()).isFalse();
            assertThat(tc.isWorkflowsCustom()).isFalse();
            assertThat(tc.isWorkflowsNone()).isTrue();
            assertThat(tc.isTablesNone()).isTrue();
            assertThat(tc.isInterfacesNone()).isTrue();
            assertThat(tc.isAgentsNone()).isTrue();      // " all " (padded) is NOT "all"
            assertThat(tc.isApplicationsNone()).isTrue(); // "" is NOT a valid grant
        }

        @Test
        @DisplayName("Parse: absent grant keys resolve to 'none' (deny-by-default, never to the list or 'all')")
        void parseToolsConfigAbsentGrantsResolveToNone() {
            // No *Grant keys → every family resolves to "none" (deny). This is the
            // AUTHORITATIVE safety-net rule: an absent grant never consults the list and
            // never becomes "all" - a row that escaped the backfill can only LOSE access.
            // isXxxNone true, isXxxAll always false regardless of the list contents.
            String jsonResponse = """
                {
                    "name": "Legacy Agent",
                    "toolsConfig": {
                        "mode": "all",
                        "workflows": [], "tables": [], "interfaces": [], "agents": [], "applications": []
                    }
                }
                """;

            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

            AgentConfig config = agentConfigProvider.getAgentConfig("agent-1", "tenant-1");

            assertThat(config).isNotNull();
            assertThat(config.toolsConfig().isWorkflowsNone()).isTrue();
            assertThat(config.toolsConfig().isWorkflowsAll()).isFalse();
            assertThat(config.toolsConfig().toMap()).doesNotContainKey("workflowsGrant");
            assertThat(config.toolsConfig().toMap()).doesNotContainKey("tablesGrant");
        }

        @Test
        @DisplayName("Parse: absent toolsConfig.files leaves the agent unrestricted (files are opt-in)")
        void parseToolsConfigFilesAbsentIsUnrestricted() {
            String jsonResponse = """
                { "name": "Open Agent", "toolsConfig": { "mode": "all" } }
                """;

            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

            AgentConfig config = agentConfigProvider.getAgentConfig("agent-1", "tenant-1");

            assertThat(config.toolsConfig().files()).isNull();
            assertThat(config.toolsConfig().isFilesScoped()).isFalse();
        }

        @Test
        @DisplayName("should handle null optional fields")
        void shouldHandleNullOptionalFields() {
            String jsonResponse = """
                {
                    "name": "Basic Agent"
                }
                """;

            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

            AgentConfig config = agentConfigProvider.getAgentConfig("agent-1", "tenant-1");

            assertThat(config).isNotNull();
            assertThat(config.systemPrompt()).isNull();
            assertThat(config.modelProvider()).isNull();
            assertThat(config.modelName()).isNull();
            assertThat(config.temperature()).isNull();
            assertThat(config.maxTokens()).isNull();
            assertThat(config.maxIterations()).isNull();
            assertThat(config.toolsConfig()).isNull();
        }

        @Test
        @DisplayName("should return null on non-2xx response")
        void shouldReturnNullOnNon2xxResponse() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(HttpStatus.NOT_FOUND));

            AgentConfig config = agentConfigProvider.getAgentConfig("agent-1", "tenant-1");

            assertThat(config).isNull();
        }

        @Test
        @DisplayName("should return null on exception")
        void shouldReturnNullOnException() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new RuntimeException("Connection refused"));

            AgentConfig config = agentConfigProvider.getAgentConfig("agent-1", "tenant-1");

            assertThat(config).isNull();
        }

        @Test
        @DisplayName("should handle null body in response")
        void shouldHandleNullBody() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

            AgentConfig config = agentConfigProvider.getAgentConfig("agent-1", "tenant-1");

            assertThat(config).isNull();
        }

        @Test
        @DisplayName("should handle null tenantId")
        void shouldHandleNullTenantId() {
            String jsonResponse = """
                {
                    "name": "Agent"
                }
                """;

            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

            AgentConfig config = agentConfigProvider.getAgentConfig("agent-1", null);

            assertThat(config).isNotNull();
        }

        @Test
        @DisplayName("should forward organization scope header when provided")
        void shouldForwardOrganizationScopeHeader() {
            String jsonResponse = """
                {
                    "name": "Org Agent",
                    "modelProvider": "deepseek",
                    "modelName": "deepseek-chat",
                    "maxTokens": 96,
                    "maxIterations": 1
                }
                """;

            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

            AgentConfig config = agentConfigProvider.getAgentConfig("agent-1", "tenant-1", "org-1");

            @SuppressWarnings("rawtypes")
            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.GET), entityCaptor.capture(), eq(String.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo("tenant-1");
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID")).isEqualTo("org-1");
            assertThat(config.maxTokens()).isEqualTo(96);
            assertThat(config.maxIterations()).isEqualTo(1);
        }

        @Test
        @DisplayName("should parse toolsConfig with null mode")
        void shouldParseToolsConfigWithNullMode() {
            // Pre-fix: absent `mode` short-circuited to `return null` from
            // parseToolsConfig - meaning the whole ToolsConfig record was thrown
            // away even when other restriction fields were present, and
            // applyToolsConfigCredentials never ran → no __allowed*Ids__ posted →
            // tool modules saw null → tenant-wide access. See companion test
            // parseToolsConfigWithoutModeStillProduces5InternalLists for the
            // full pipeline assertion.
            // Post-fix: absent mode defaults to "all" (MCP product behavior); the
            // 5 internal lists are still parsed and the predicates report "no
            // access" for absent / empty.
            String jsonResponse = """
                {
                    "name": "Agent",
                    "toolsConfig": {}
                }
                """;

            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

            AgentConfig config = agentConfigProvider.getAgentConfig("agent-1", "tenant-1");

            assertThat(config).isNotNull();
            assertThat(config.toolsConfig()).isNotNull();
            assertThat(config.toolsConfig().mode()).isEqualTo("all");
            assertThat(config.toolsConfig().isWorkflowsNone()).isTrue();
            assertThat(config.toolsConfig().isTablesNone()).isTrue();
        }
    }

    @Nested
    @DisplayName("getCompactionOverride - per-agent compaction override (enable + cadence + summariser model)")
    class GetCompactionOverride {

        @Test
        @DisplayName("parses compactionModelProvider/compactionModelName (trimmed) alongside enable + cadence")
        void parsesFullOverrideIncludingModelPair() {
            String jsonResponse = """
                {
                    "name": "Agent",
                    "compactionEnabled": true,
                    "compactionAfterTurns": 6,
                    "compactionModelProvider": " openai ",
                    "compactionModelName": "gpt-5-mini"
                }
                """;
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

            var override = agentConfigProvider.getCompactionOverride("agent-1", "tenant-1");

            assertThat(override.enabled()).isTrue();
            assertThat(override.afterTurns()).isEqualTo(6);
            assertThat(override.modelProvider()).isEqualTo("openai");
            assertThat(override.modelName()).isEqualTo("gpt-5-mini");
        }

        @Test
        @DisplayName("partial model pair (provider without name) nulls BOTH - the tier refuses to guess")
        void partialModelPairNullsBoth() {
            // Persisting/serving only one half of the pair is a broken state; the
            // parser must collapse it to fully-unset so the resolver falls through
            // to the next tier instead of merging halves from different tiers.
            String jsonResponse = """
                {
                    "name": "Agent",
                    "compactionEnabled": false,
                    "compactionModelProvider": "openai"
                }
                """;
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

            var override = agentConfigProvider.getCompactionOverride("agent-1", "tenant-1");

            assertThat(override.modelProvider()).isNull();
            assertThat(override.modelName()).isNull();
            // The enable flag on the same payload still parses - only the pair collapses.
            assertThat(override.enabled()).isFalse();
        }

        @Test
        @DisplayName("numeric model fields are rejected as non-text (no toString coercion) - both null")
        void numericModelFieldsAreNull() {
            // Regression guard for the getString-style toString() coercion bug class
            // (a numeric JSON value once became the literal string "106735" in a
            // content field). A number must NEVER coerce into a model provider/name.
            String jsonResponse = """
                {
                    "name": "Agent",
                    "compactionModelProvider": 106735,
                    "compactionModelName": 42
                }
                """;
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

            var override = agentConfigProvider.getCompactionOverride("agent-1", "tenant-1");

            assertThat(override.modelProvider()).isNull();
            assertThat(override.modelName()).isNull();
        }

        @Test
        @DisplayName("blank model provider collapses the pair to unset - both null despite a real name")
        void blankModelProviderNullsBoth() {
            String jsonResponse = """
                {
                    "name": "Agent",
                    "compactionModelProvider": "   ",
                    "compactionModelName": "claude-haiku-4-5"
                }
                """;
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(jsonResponse, HttpStatus.OK));

            var override = agentConfigProvider.getCompactionOverride("agent-1", "tenant-1");

            assertThat(override.modelProvider()).isNull();
            assertThat(override.modelName()).isNull();
        }
    }

    @Nested
    @DisplayName("ToolsConfig")
    class ToolsConfigTests {

        @Test
        @DisplayName("isToolsNone should return true for none mode")
        void isToolsNoneShouldReturnTrue() {
            ToolsConfig config = new ToolsConfig("none", List.of(), null, null, null, null, null, null, null, null, null, null, null, null);
            assertThat(config.isToolsNone()).isTrue();
            assertThat(config.isToolsCustom()).isFalse();
            assertThat(config.isToolsAll()).isFalse();
        }

        @Test
        @DisplayName("isToolsCustom should return true for custom mode")
        void isToolsCustomShouldReturnTrue() {
            ToolsConfig config = new ToolsConfig("custom", List.of("tool-1"), null, null, null, null, null, null, null, null, null, null, null, null);
            assertThat(config.isToolsCustom()).isTrue();
            assertThat(config.isToolsNone()).isFalse();
            assertThat(config.isToolsAll()).isFalse();
        }

        @Test
        @DisplayName("isToolsAll should return true for all mode")
        void isToolsAllShouldReturnTrue() {
            ToolsConfig config = new ToolsConfig("all", List.of(), null, null, null, null, null, null, null, null, null, null, null, null);
            assertThat(config.isToolsAll()).isTrue();
        }

        @Test
        @DisplayName("isToolsAll should return true for null mode")
        void isToolsAllShouldReturnTrueForNull() {
            ToolsConfig config = new ToolsConfig(null, List.of(), null, null, null, null, null, null, null, null, null, null, null, null);
            assertThat(config.isToolsAll()).isTrue();
        }

        @Test
        @DisplayName("isWorkflowsNone should return true for empty workflows")
        void isWorkflowsNoneShouldReturnTrue() {
            ToolsConfig config = new ToolsConfig("all", List.of(), List.of(), null, null, null, null, null, null, null, null, null, null, null);
            assertThat(config.isWorkflowsNone()).isTrue();
            assertThat(config.isWorkflowsCustom()).isFalse();
        }

        @Test
        @DisplayName("isWorkflowsCustom is true when grant='custom' (the list is the custom payload)")
        void isWorkflowsCustomShouldReturnTrue() {
            // No-back-compat: "custom" requires an explicit grant; a bare non-empty
            // list with no grant resolves to none (see ToolsConfigGrantTests).
            ToolsConfig config = new ToolsConfig("all", List.of(), List.of("wf-1"), null, null, null, null, null, null, null, null, null, null, null, null, "custom", null, null, null, null, null);
            assertThat(config.isWorkflowsCustom()).isTrue();
            assertThat(config.isWorkflowsNone()).isFalse();
        }

        @Test
        @DisplayName("isWorkflowsNone returns true when workflows is null (absent key denies access)")
        void absentWorkflowsKeyIsTreatedAsDeniedNotUnrestricted() {
            // Regression for the "absent ≠ all" security rule. Pre-fix this returned false,
            // causing AgentContextBuilder.applyToolsConfigCredentials to skip
            // __allowedWorkflowIds__ entirely → tool modules saw null → no restriction →
            // legacy agents with absent `workflows` key silently had access to every
            // workflow in the tenant. Post-fix, absent is identical to []: no access.
            // Companion write-side chokepoint: AgentService.normalizeToolsConfig.
            ToolsConfig config = new ToolsConfig("all", List.of(), null, null, null, null, null, null, null, null, null, null, null, null);
            assertThat(config.isWorkflowsNone()).isTrue();
            assertThat(config.isWorkflowsCustom()).isFalse();
        }

        @Test
        @DisplayName("Absent key denies access for all 5 internal resource lists")
        void absentKeyDeniesAccessForAllInternalResources() {
            // Mirrors the workflows guard above for tables/interfaces/agents/applications.
            // All 5 internal resource categories must follow the same "absent = []" rule.
            ToolsConfig config = new ToolsConfig("all", List.of(), null, null, null, null, null, null, null, null, null, null, null, null);
            assertThat(config.isApplicationsNone()).isTrue();
            assertThat(config.isApplicationsCustom()).isFalse();
            assertThat(config.isTablesNone()).isTrue();
            assertThat(config.isTablesCustom()).isFalse();
            assertThat(config.isInterfacesNone()).isTrue();
            assertThat(config.isInterfacesCustom()).isFalse();
            assertThat(config.isAgentsNone()).isTrue();
            assertThat(config.isAgentsCustom()).isFalse();
        }
    }

    @Nested
    @DisplayName("ToolsConfig per-family GRANT sentinel")
    class ToolsConfigGrantTests {

        // Canonical 20-arg constructor: the 5 grants are the LAST 5 args, in order
        // workflowsGrant, tablesGrant, interfacesGrant, agentsGrant, applicationsGrant.
        private ToolsConfig withWorkflowsGrant(List<String> workflows, String grant) {
            return new ToolsConfig("all", List.of(), workflows, null, null, null, null, null,
                null, null, null, null, null, null, null,
                grant, null, null, null, null, null);
        }

        @Test
        @DisplayName("grant='all' → isWorkflowsAll true; None/Custom false even with empty list")
        void grantAllResolvesToAll() {
            ToolsConfig config = withWorkflowsGrant(List.of(), "all");
            assertThat(config.isWorkflowsAll()).isTrue();
            assertThat(config.isWorkflowsNone()).isFalse();
            assertThat(config.isWorkflowsCustom()).isFalse();
        }

        @Test
        @DisplayName("grant='none' → isWorkflowsNone true; All/Custom false even with a non-empty list")
        void grantNoneResolvesToNone() {
            ToolsConfig config = withWorkflowsGrant(List.of("wf-1"), "none");
            assertThat(config.isWorkflowsNone()).isTrue();
            assertThat(config.isWorkflowsAll()).isFalse();
            assertThat(config.isWorkflowsCustom()).isFalse();
        }

        @Test
        @DisplayName("grant='custom' + non-empty list → isWorkflowsCustom true; All/None false")
        void grantCustomResolvesToCustom() {
            ToolsConfig config = withWorkflowsGrant(List.of("wf-1"), "custom");
            assertThat(config.isWorkflowsCustom()).isTrue();
            assertThat(config.isWorkflowsAll()).isFalse();
            assertThat(config.isWorkflowsNone()).isFalse();
        }

        @Test
        @DisplayName("grant ABSENT + empty list → isWorkflowsNone true (absent ⇒ none, deny-by-default)")
        void absentGrantEmptyListStaysNone() {
            ToolsConfig config = withWorkflowsGrant(List.of(), null);
            assertThat(config.isWorkflowsNone()).isTrue();
            assertThat(config.isWorkflowsCustom()).isFalse();
            assertThat(config.isWorkflowsAll()).isFalse();
        }

        @Test
        @DisplayName("grant ABSENT + non-empty list → isWorkflowsNone (no legacy fallback: a list never grants without a grant)")
        void absentGrantNonEmptyListIsNoneNotCustom() {
            ToolsConfig config = withWorkflowsGrant(List.of("wf-1"), null);
            assertThat(config.isWorkflowsNone()).isTrue();
            assertThat(config.isWorkflowsCustom()).isFalse();
            assertThat(config.isWorkflowsAll()).isFalse();
        }

        @Test
        @DisplayName("grant ABSENT for ALL families (14-arg ctor) → every family resolves to 'none' (deny-by-default)")
        void absentGrantsAllFamiliesAreNone() {
            // 14-arg back-compat ctor → all 5 grants null → every family resolves to
            // "none" (the authoritative deny-default). The list is never consulted;
            // isXxxNone true, isXxxAll always false regardless of list contents.
            ToolsConfig config = new ToolsConfig("all", List.of(), null, null, null, null, null, null,
                null, null, null, null, null, null);
            assertThat(config.isWorkflowsNone()).isTrue();
            assertThat(config.isWorkflowsAll()).isFalse();
            assertThat(config.isTablesNone()).isTrue();
            assertThat(config.isTablesAll()).isFalse();
            assertThat(config.isInterfacesNone()).isTrue();
            assertThat(config.isInterfacesAll()).isFalse();
            assertThat(config.isAgentsNone()).isTrue();
            assertThat(config.isAgentsAll()).isFalse();
            assertThat(config.isApplicationsNone()).isTrue();
            assertThat(config.isApplicationsAll()).isFalse();
        }

        @Test
        @DisplayName("grant='all' resolves for every one of the 5 families independently")
        void grantAllPerFamily() {
            // tablesGrant, interfacesGrant, agentsGrant, applicationsGrant in turn.
            ToolsConfig tables = new ToolsConfig("all", List.of(), null, null, List.of(), null, null, null,
                null, null, null, null, null, null, null, null, "all", null, null, null, null);
            assertThat(tables.isTablesAll()).isTrue();
            assertThat(tables.isTablesNone()).isFalse();

            ToolsConfig interfaces = new ToolsConfig("all", List.of(), null, null, null, List.of(), null, null,
                null, null, null, null, null, null, null, null, null, "all", null, null, null);
            assertThat(interfaces.isInterfacesAll()).isTrue();
            assertThat(interfaces.isInterfacesNone()).isFalse();

            ToolsConfig agents = new ToolsConfig("all", List.of(), null, null, null, null, List.of(), null,
                null, null, null, null, null, null, null, null, null, null, "all", null, null);
            assertThat(agents.isAgentsAll()).isTrue();
            assertThat(agents.isAgentsNone()).isFalse();

            ToolsConfig applications = new ToolsConfig("all", List.of(), null, List.of(), null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, "all", null);
            assertThat(applications.isApplicationsAll()).isTrue();
            assertThat(applications.isApplicationsNone()).isFalse();
        }

        @Test
        @DisplayName("toMap emits the grant key when present and omits it when absent")
        void toMapEmitsGrant() {
            ToolsConfig withGrant = withWorkflowsGrant(List.of(), "all");
            assertThat(withGrant.toMap()).containsEntry("workflowsGrant", "all");
            // The other 4 family grants were null → omitted.
            assertThat(withGrant.toMap()).doesNotContainKey("tablesGrant");

            ToolsConfig noGrant = withWorkflowsGrant(List.of("wf-1"), null);
            assertThat(noGrant.toMap()).doesNotContainKey("workflowsGrant");
        }

        @Test
        @DisplayName("toMap round-trips all 5 grant keys when all present")
        void toMapRoundTripsAllGrants() {
            ToolsConfig config = new ToolsConfig("all", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                null, null, null, null, null, null, null,
                "all", "none", "custom", "all", "none", null);
            java.util.Map<String, Object> map = config.toMap();
            assertThat(map).containsEntry("workflowsGrant", "all");
            assertThat(map).containsEntry("tablesGrant", "none");
            assertThat(map).containsEntry("interfacesGrant", "custom");
            assertThat(map).containsEntry("agentsGrant", "all");
            assertThat(map).containsEntry("applicationsGrant", "none");
        }
    }

    @Nested
    @DisplayName("ToolsConfig.toMap")
    class ToolsConfigToMapTests {

        @Test
        @DisplayName("toMap should include all non-null fields")
        void toMapShouldIncludeAllFields() {
            ToolsConfig config = new ToolsConfig("custom",
                List.of("tool-1"), List.of("wf-1"), List.of("app-1"),
                List.of("table-1"), List.of("iface-1"), List.of("agent-1"), true,
                null, null, null, null, null, null);

            java.util.Map<String, Object> map = config.toMap();

            assertThat(map).containsEntry("mode", "custom");
            assertThat(map).containsEntry("tools", List.of("tool-1"));
            assertThat(map).containsEntry("workflows", List.of("wf-1"));
            assertThat(map).containsEntry("applications", List.of("app-1"));
            assertThat(map).containsEntry("tables", List.of("table-1"));
            assertThat(map).containsEntry("interfaces", List.of("iface-1"));
            assertThat(map).containsEntry("agents", List.of("agent-1"));
            assertThat(map).containsEntry("webSearch", true);
        }

        @Test
        @DisplayName("toMap should exclude null fields")
        void toMapShouldExcludeNullFields() {
            ToolsConfig config = new ToolsConfig("none", null, null, null, null, null, null, null, null, null, null, null, null, null);

            java.util.Map<String, Object> map = config.toMap();

            assertThat(map).containsEntry("mode", "none");
            assertThat(map).doesNotContainKey("tools");
            assertThat(map).doesNotContainKey("workflows");
            assertThat(map).doesNotContainKey("applications");
            assertThat(map).doesNotContainKey("tables");
            assertThat(map).doesNotContainKey("interfaces");
            assertThat(map).doesNotContainKey("agents");
            assertThat(map).doesNotContainKey("webSearch");
        }

        @Test
        @DisplayName("toMap should handle empty lists correctly")
        void toMapShouldHandleEmptyLists() {
            ToolsConfig config = new ToolsConfig("custom",
                List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), false,
                null, null, null, null, null, null);

            java.util.Map<String, Object> map = config.toMap();

            assertThat(map).containsEntry("tables", List.of());
            assertThat(map).containsEntry("interfaces", List.of());
            assertThat(map).containsEntry("webSearch", false);
        }

        @Test
        @DisplayName("toMap with null mode should not include mode key")
        void toMapNullModeShouldExcludeMode() {
            ToolsConfig config = new ToolsConfig(null, null, null, null, null, null, null, null, null, null, null, null, null, null);

            java.util.Map<String, Object> map = config.toMap();

            assertThat(map).doesNotContainKey("mode");
        }
    }

    @Nested
    @DisplayName("AgentConfig")
    class AgentConfigTests {

        @Test
        @DisplayName("hasSystemPrompt should return true when set")
        void hasSystemPromptShouldReturnTrue() {
            AgentConfig config = new AgentConfig("id", "name", "prompt", null, null, null, null, null, null, null, null,
                null, null, null);
            assertThat(config.hasSystemPrompt()).isTrue();
        }

        @Test
        @DisplayName("hasSystemPrompt should return false for null")
        void hasSystemPromptShouldReturnFalseForNull() {
            AgentConfig config = new AgentConfig("id", "name", null, null, null, null, null, null, null, null, null,
                null, null, null);
            assertThat(config.hasSystemPrompt()).isFalse();
        }

        @Test
        @DisplayName("hasSystemPrompt should return false for blank")
        void hasSystemPromptShouldReturnFalseForBlank() {
            AgentConfig config = new AgentConfig("id", "name", "  ", null, null, null, null, null, null, null, null,
                null, null, null);
            assertThat(config.hasSystemPrompt()).isFalse();
        }

        @Test
        @DisplayName("hasModel should return true when set")
        void hasModelShouldReturnTrue() {
            AgentConfig config = new AgentConfig("id", "name", null, null, "gpt-4", null, null, null, null, null, null,
                null, null, null);
            assertThat(config.hasModel()).isTrue();
        }

        @Test
        @DisplayName("hasModel should return false for null")
        void hasModelShouldReturnFalseForNull() {
            AgentConfig config = new AgentConfig("id", "name", null, null, null, null, null, null, null, null, null,
                null, null, null);
            assertThat(config.hasModel()).isFalse();
        }

        @Test
        @DisplayName("hasProvider should return true when set")
        void hasProviderShouldReturnTrue() {
            AgentConfig config = new AgentConfig("id", "name", null, "openai", null, null, null, null, null, null, null,
                null, null, null);
            assertThat(config.hasProvider()).isTrue();
        }

        @Test
        @DisplayName("hasProvider should return false for null")
        void hasProviderShouldReturnFalseForNull() {
            AgentConfig config = new AgentConfig("id", "name", null, null, null, null, null, null, null, null, null,
                null, null, null);
            assertThat(config.hasProvider()).isFalse();
        }

        @Test
        @DisplayName("hasToolsConfig should return true when set")
        void hasToolsConfigShouldReturnTrue() {
            ToolsConfig tc = new ToolsConfig("all", List.of(), null, null, null, null, null, null, null, null, null, null, null, null);
            AgentConfig config = new AgentConfig("id", "name", null, null, null, null, null, null, tc, null, null,
                null, null, null);
            assertThat(config.hasToolsConfig()).isTrue();
        }

        @Test
        @DisplayName("hasToolsConfig should return false for null")
        void hasToolsConfigShouldReturnFalseForNull() {
            AgentConfig config = new AgentConfig("id", "name", null, null, null, null, null, null, null, null, null,
                null, null, null);
            assertThat(config.hasToolsConfig()).isFalse();
        }
    }

    // ======================================================================
    // getAvailableModels - platform-wide AI catalog for prompt injection
    // ======================================================================

    /**
     * These tests pin the contract between conversation-service and agent-service's
     * {@code /api/internal/agent/models/flat} endpoint. The response is embedded
     * verbatim in the agent system prompt - any silent parsing failure here means
     * the LLM falls back to hallucinating training-data model names.
     *
     * <p>Each test uses a fresh provider instance so the internal
     * {@code AtomicReference} cache starts empty.
     */
    @Nested
    @DisplayName("getAvailableModels - model catalog cache + RPC")
    class GetAvailableModels {

        @Test
        @DisplayName("parses a valid flat response")
        void parsesValidResponse() {
            String body = """
                [
                  {"provider":"anthropic","modelId":"claude-opus-4-6","tier":"top"},
                  {"provider":"openai","modelId":"gpt-5","tier":"top"}
                ]
                """;
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

            List<AvailableModel> models = agentConfigProvider.getAvailableModels();

            assertThat(models).hasSize(2);
            assertThat(models.get(0).provider()).isEqualTo("anthropic");
            assertThat(models.get(0).modelId()).isEqualTo("claude-opus-4-6");
            assertThat(models.get(0).tier()).isEqualTo("top");
            assertThat(models.get(1).provider()).isEqualTo("openai");
        }

        @Test
        @DisplayName("defaults missing tier to 'mid'")
        void defaultsMissingTier() {
            String body = """
                [{"provider":"openai","modelId":"gpt-5"}]
                """;
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

            List<AvailableModel> models = agentConfigProvider.getAvailableModels();

            assertThat(models).hasSize(1);
            assertThat(models.get(0).tier()).isEqualTo("mid");
        }

        @Test
        @DisplayName("skips entries missing provider or modelId")
        void skipsMalformedEntries() {
            // An entry without provider OR without modelId is meaningless for
            // the prompt injection - drop it silently rather than poison the
            // whole catalog with half-formed pairs.
            String body = """
                [
                  {"provider":"openai","modelId":"gpt-5","tier":"top"},
                  {"modelId":"orphan-model","tier":"top"},
                  {"provider":"anthropic","tier":"top"}
                ]
                """;
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

            List<AvailableModel> models = agentConfigProvider.getAvailableModels();

            assertThat(models).hasSize(1);
            assertThat(models.get(0).modelId()).isEqualTo("gpt-5");
        }

        @Test
        @DisplayName("returns empty list (not null) when response is empty array")
        void emptyResponse() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>("[]", HttpStatus.OK));

            List<AvailableModel> models = agentConfigProvider.getAvailableModels();

            assertThat(models).isNotNull().isEmpty();
        }

        @Test
        @DisplayName("returns empty on non-2xx HTTP status")
        void non2xxReturnsEmpty() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR));

            List<AvailableModel> models = agentConfigProvider.getAvailableModels();

            assertThat(models).isEmpty();
        }

        @Test
        @DisplayName("returns empty when RestTemplate throws - never propagates")
        void rpcExceptionReturnsEmpty() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new RuntimeException("Connection refused"));

            // Must NOT throw - caller (AgentContextBuilder) will skip the
            // section silently, which is strictly better than a failed
            // conversation init.
            List<AvailableModel> models = agentConfigProvider.getAvailableModels();

            assertThat(models).isEmpty();
        }

        @Test
        @DisplayName("returns empty on non-array body (malformed JSON shape)")
        void malformedBodyReturnsEmpty() {
            // agent-service regression scenario: somebody changes the endpoint
            // to return an object like {"models":[...]} instead of a raw array.
            // The parser must stay defensive and not NPE or throw.
            String body = """
                {"models":[{"provider":"openai","modelId":"gpt-5"}]}
                """;
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

            List<AvailableModel> models = agentConfigProvider.getAvailableModels();

            assertThat(models).isEmpty();
        }

        @Test
        @DisplayName("returns empty on invalid JSON body (ObjectMapper exception)")
        void invalidJsonReturnsEmpty() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>("not json at all {{{", HttpStatus.OK));

            List<AvailableModel> models = agentConfigProvider.getAvailableModels();

            assertThat(models).isEmpty();
        }

        @Test
        @DisplayName("second call within TTL is served from cache - no second RPC")
        void secondCallHitsCache() {
            // 90 s TTL means two consecutive synchronous calls should result
            // in exactly one RPC invocation. This is the hot path in
            // AgentContextBuilder and the primary reason the cache exists.
            String body = """
                [{"provider":"openai","modelId":"gpt-5","tier":"top"}]
                """;
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

            List<AvailableModel> first = agentConfigProvider.getAvailableModels();
            List<AvailableModel> second = agentConfigProvider.getAvailableModels();

            assertThat(first).hasSize(1);
            assertThat(second).hasSize(1);
            verify(restTemplate, times(1))
                    .exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        }

        @Test
        @DisplayName("serves stale cache on refresh failure - never reverts to empty when a prior snapshot exists")
        void servesStaleOnRefreshFailure() throws Exception {
            // First call succeeds and populates the cache. Then we forcibly
            // expire the cache via reflection and make the next RPC fail.
            // The provider should serve the stale snapshot rather than
            // returning an empty list - losing a working catalog because of a
            // transient network blip would immediately re-open the
            // hallucination hole we're closing.
            String okBody = """
                [{"provider":"openai","modelId":"gpt-5","tier":"top"}]
                """;
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(okBody, HttpStatus.OK));

            List<AvailableModel> warm = agentConfigProvider.getAvailableModels();
            assertThat(warm).hasSize(1);

            // Force cache expiry by clearing the AtomicReference via reflection.
            java.lang.reflect.Field cacheField = AgentConfigProvider.class.getDeclaredField("catalogCache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.atomic.AtomicReference<Object> cache =
                    (java.util.concurrent.atomic.AtomicReference<Object>) cacheField.get(agentConfigProvider);
            // Re-wrap current snapshot with an already-expired expiresAt so
            // the next call treats it as stale.
            Class<?> cachedCatalogClass = Class.forName(
                    "com.apimarketplace.conversation.service.ai.AgentConfigProvider$CachedCatalog");
            java.lang.reflect.Constructor<?> ctor = cachedCatalogClass.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Object expired = ctor.newInstance(warm, java.time.Instant.now().minusSeconds(1));
            cache.set(expired);

            // Clear the OK stub and re-stub to throw on next call. Reset is
            // needed so the previous thenReturn doesn't interfere with the
            // thenThrow (strict Mockito would otherwise complain).
            reset(restTemplate);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenThrow(new RuntimeException("agent-service down"));

            List<AvailableModel> stale = agentConfigProvider.getAvailableModels();
            assertThat(stale).hasSize(1);
            assertThat(stale.get(0).modelId()).isEqualTo("gpt-5");
        }

        @Test
        @DisplayName("warmed cache short-circuits subsequent concurrent calls (1 warmup + N hot)")
        void concurrentColdStart() throws Exception {
            // The cache is lock-free (AtomicReference + CAS). It does NOT coalesce
            // in-flight fetches (explicitly chosen - see comment in the impl):
            // every thread that reads `null` BEFORE publication issues its own
            // RPC. That makes the original test design flawed - releasing 50
            // threads simultaneously gives every thread time to read `null`
            // BEFORE the winner publishes, so RPC count = threads on fast CI.
            //
            // Real contract under test: AFTER one warmup call has completed
            // and published its result, subsequent concurrent calls must
            // short-circuit via the cache (zero additional RPCs in the warm
            // window). This is the actually-observable deduplication, and the
            // path that matters in production (cold-start is rare; warm reuse
            // is the steady state). Audit Opus 2026-05-10 - replaces the
            // tautological `<= threads` with a strict, deterministic assertion.
            String body = """
                [{"provider":"openai","modelId":"gpt-5","tier":"top"}]
                """;
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));

            // Phase 1 - warmup. One call publishes the cache.
            List<AvailableModel> warmupResult = agentConfigProvider.getAvailableModels();
            assertThat(warmupResult).isNotEmpty();
            long rpcAfterWarmup = mockingDetails(restTemplate).getInvocations().stream()
                    .filter(inv -> "exchange".equals(inv.getMethod().getName()))
                    .count();
            assertThat(rpcAfterWarmup)
                    .as("warmup itself must issue exactly one RPC")
                    .isEqualTo(1L);

            // Phase 2 - N concurrent hot calls. All must hit the cache.
            int hotThreads = 50;
            ExecutorService pool = Executors.newFixedThreadPool(hotThreads);
            CountDownLatch ready = new CountDownLatch(hotThreads);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger populated = new AtomicInteger();
            try {
                for (int i = 0; i < hotThreads; i++) {
                    pool.submit(() -> {
                        ready.countDown();
                        try {
                            go.await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                        List<AvailableModel> models = agentConfigProvider.getAvailableModels();
                        if (!models.isEmpty()) populated.incrementAndGet();
                    });
                }
                ready.await(5, TimeUnit.SECONDS);
                go.countDown();
                pool.shutdown();
                assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                pool.shutdownNow();
            }

            // Every hot caller saw the cached catalog.
            assertThat(populated.get()).isEqualTo(hotThreads);

            // The 50 hot calls must produce ZERO additional RPCs (cache hot).
            long rpcAfterHot = mockingDetails(restTemplate).getInvocations().stream()
                    .filter(inv -> "exchange".equals(inv.getMethod().getName()))
                    .count();
            assertThat(rpcAfterHot - rpcAfterWarmup)
                    .as("warm cache MUST short-circuit ALL %d hot calls (zero new RPCs)", hotThreads)
                    .isZero();
        }
    }
}
