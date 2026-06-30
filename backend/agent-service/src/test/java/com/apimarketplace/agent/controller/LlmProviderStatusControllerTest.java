package com.apimarketplace.agent.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LlmProviderStatusController#computeConnected}.
 *
 * The controller's {@code /bridge-status} endpoint must return {@code connected=true}
 * with these semantics:
 *   - With a CLI filter → only when that specific CLI is reported installed.
 *   - Without a filter   → when at least one CLI is installed.
 *
 * These tests guard against regressions where any 200 from the bridge would
 * incorrectly mark every CLI as connected (the original bug).
 */
@DisplayName("LlmProviderStatusController#computeConnected")
class LlmProviderStatusControllerTest {

    private final LlmProviderStatusController controller =
            new LlmProviderStatusController(null, null, null);

    private static Map<String, Object> entry(boolean installed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("installed", installed);
        return m;
    }

    @Nested
    @DisplayName("with cli filter")
    class WithFilter {

        @Test
        void returnsTrueWhenSelectedCliInstalled() {
            Map<String, Object> body = Map.of("cli", entry(true));
            assertThat(controller.computeConnected(body, "claudeCode")).isTrue();
        }

        @Test
        void returnsFalseWhenSelectedCliNotInstalled() {
            Map<String, Object> body = Map.of("cli", entry(false));
            assertThat(controller.computeConnected(body, "claudeCode")).isFalse();
        }

        @Test
        void returnsFalseWhenCliFieldMissing() {
            Map<String, Object> body = Map.of("bridgeReachable", true);
            assertThat(controller.computeConnected(body, "claudeCode")).isFalse();
        }
    }

    @Test
    @DisplayName("SUPPORTED_CLIS contains the four bridge ids and nothing else")
    void supportedClisAllowlistMatchesBridge() {
        assertThat(LlmProviderStatusController.SUPPORTED_CLIS)
                .containsExactlyInAnyOrder("claudeCode", "codex", "geminiCli", "mistralVibe");
    }

    @Nested
    @DisplayName("without cli filter")
    class WithoutFilter {

        @Test
        void returnsTrueWhenAtLeastOneCliInstalled() {
            Map<String, Object> clis = Map.of(
                    "claudeCode", entry(false),
                    "codex", entry(true),
                    "geminiCli", entry(false),
                    "mistralVibe", entry(false));
            Map<String, Object> body = Map.of("clis", clis);
            assertThat(controller.computeConnected(body, null)).isTrue();
        }

        @Test
        void returnsFalseWhenNoCliInstalled() {
            Map<String, Object> clis = Map.of(
                    "claudeCode", entry(false),
                    "codex", entry(false),
                    "geminiCli", entry(false),
                    "mistralVibe", entry(false));
            Map<String, Object> body = Map.of("clis", clis);
            assertThat(controller.computeConnected(body, null)).isFalse();
        }

        @Test
        void returnsFalseWhenClisFieldMissing() {
            Map<String, Object> body = Map.of("bridgeReachable", true);
            assertThat(controller.computeConnected(body, null)).isFalse();
        }

        @Test
        void treatsBlankFilterAsNoFilter() {
            Map<String, Object> clis = Map.of("codex", entry(true));
            Map<String, Object> body = Map.of("clis", clis);
            assertThat(controller.computeConnected(body, "")).isTrue();
        }
    }
}
