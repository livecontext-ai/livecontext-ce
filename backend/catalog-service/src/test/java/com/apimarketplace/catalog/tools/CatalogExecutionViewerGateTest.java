package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.catalog.tools.generation.GenerationModule;
import com.apimarketplace.credential.client.CredentialClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Regression for the VIEWER write gap on catalog execution: a read-only VIEWER could run any
 * third-party action (send a mail, post a message, spend credits) through the chat
 * {@code catalog} tool and the {@code generate} tool, because only the per-agent access mode
 * was checked. The workspace role is now checked too.
 */
@DisplayName("Catalog execution refuses the workspace VIEWER role")
class CatalogExecutionViewerGateTest {

    private static ToolExecutionContext ctx(String orgId, String role) {
        return new ToolExecutionContext("7", new HashMap<>(), Map.of(), Set.of(), null, null, orgId, role);
    }

    @Nested
    @DisplayName("CatalogExecuteModule")
    class ExecuteModule {

        private CatalogExecuteModule module;
        private RestTemplate restTemplate;
        private CredentialClient credentialClient;

        @BeforeEach
        void setUp() throws Exception {
            credentialClient = mock(CredentialClient.class);
            module = new CatalogExecuteModule(new ObjectMapper(), credentialClient);
            restTemplate = mock(RestTemplate.class);
            Field rt = CatalogExecuteModule.class.getDeclaredField("restTemplate");
            rt.setAccessible(true);
            rt.set(module, restTemplate);
        }

        @Test
        @DisplayName("VIEWER: execute is refused with PERMISSION_DENIED and nothing is called")
        void viewerExecuteRefused() {
            Optional<ToolExecutionResult> result = module.execute("execute",
                    Map.of("tool_id", "gmail/send"), "7", ctx("org-1", "VIEWER"));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(result.get().error()).contains("read-only (VIEWER)");
            verifyNoInteractions(restTemplate, credentialClient);
        }

        @Test
        @DisplayName("VIEWER: the legacy 'call' alias is refused too")
        void viewerCallRefused() {
            Optional<ToolExecutionResult> result = module.execute("call",
                    Map.of("tool_id", "gmail/send"), "7", ctx("org-1", "viewer"));

            assertThat(result.get().errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verifyNoInteractions(restTemplate, credentialClient);
        }

        @Test
        @DisplayName("VIEWER: executeGeneration (the generate tool's path) is refused before billing")
        void viewerGenerationRefused() {
            Optional<ToolExecutionResult> result = module.executeGeneration(
                    Map.of("tool_id", "fal/video"), ctx("org-1", "VIEWER"), null);

            assertThat(result.get().errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verifyNoInteractions(restTemplate, credentialClient);
        }

        @Test
        @DisplayName("MEMBER, and a VIEWER role with no workspace, are not refused by the role gate")
        void memberAndPersonalScopeNotRoleRefused() {
            assertThat(CatalogExecuteModule.checkViewerRole(ctx("org-1", "MEMBER"), "execute")).isEmpty();
            assertThat(CatalogExecuteModule.checkViewerRole(ctx(null, "VIEWER"), "execute")).isEmpty();
            assertThat(CatalogExecuteModule.checkViewerRole(null, "execute")).isEmpty();
        }
    }

    @Nested
    @DisplayName("GenerationModule")
    class Generation {

        private GenerationModule module;
        private CatalogExecuteModule executeModule;

        @BeforeEach
        void setUp() throws Exception {
            executeModule = mock(CatalogExecuteModule.class);
            Constructor<?> ctor = Arrays.stream(GenerationModule.class.getConstructors())
                    .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
            Object[] args = Arrays.stream(ctor.getParameterTypes())
                    .map(t -> t == CatalogExecuteModule.class ? executeModule : mock(t)).toArray();
            module = (GenerationModule) ctor.newInstance(args);
        }

        @Test
        @DisplayName("VIEWER: create and the legacy generate are refused, nothing is executed")
        void viewerCreateRefused() {
            for (String action : new String[]{"create", "generate"}) {
                Optional<ToolExecutionResult> result = module.execute(action,
                        Map.of("model", "vid-fast", "prompt", "a cat"), "7", ctx("org-1", "VIEWER"));

                assertThat(result).isPresent();
                assertThat(result.get().errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
                assertThat(result.get().error()).contains("read-only (VIEWER)").contains("action='models'");
            }
            verifyNoInteractions(executeModule);
        }

        @Test
        @DisplayName("a MEMBER is not refused by the role gate (the create path is reached)")
        void memberNotRoleRefused() {
            Optional<ToolExecutionResult> result = module.execute("create",
                    Map.of("model", "vid-fast", "prompt", "a cat"), "7", ctx("org-1", "MEMBER"));

            assertThat(result).isPresent();
            assertThat(String.valueOf(result.get().error())).doesNotContain("read-only (VIEWER)");
        }
    }
}
