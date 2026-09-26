package com.apimarketplace.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LogSafePath - a request path with its capability tokens masked")
class LogSafePathTest {

    private static final String T = "FAKEtoken_0123456789abcdefghijklmn";

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            // Public routes as the gateway / CE monolith see them (token = first segment after the prefix).
            "/webhook/wh_" + T + ",                        /webhook/{token}",
            "/webhook/agent/" + T + ",                     /webhook/agent/{token}",
            "/share/sl_" + T + ",                          /share/{token}",
            "/s/sl_" + T + ",                              /s/{token}",
            "/c/cs_" + T + "/messages,                     /c/{token}/messages",
            "/chat/" + T + "/message,                      /chat/{token}/message",
            "/form/" + T + "/config,                       /form/{token}/config",
            "/app/public/" + T + "/render,                 /app/public/{token}/render",
            "/widget/" + T + "/session,                    /widget/{token}/session",
            "/f/" + T + ",                                 /f/{token}",
            "/w/embed/" + T + ",                           /w/embed/{token}",
            "/api/ce-link/squat-recovery/" + T + ",        /api/ce-link/squat-recovery/{token}",
            // Internal look-ups, wherever they sit in the path.
            "/api/internal/trigger/webhooks/by-token/" + T + ",   /api/internal/trigger/webhooks/by-token/{token}",
            "/api/internal/shared-links/validate/" + T + ",       /api/internal/shared-links/validate/{token}",
            "/api/internal/shared-links/by-resource/" + T + ",    /api/internal/shared-links/by-resource/{token}",
    })
    @DisplayName("Regression 2026-09-25: every known token route is masked by prefix, with no pattern")
    void knownTokenRoutesMasked(String path, String expected) {
        assertThat(LogSafePath.of(path)).isEqualTo(expected).doesNotContain(T);
    }

    @Test
    @DisplayName("A matched pattern masks any variable whose name contains 'token', and only those")
    void patternVariablesNamedTokenMasked() {
        assertThat(LogSafePath.of("/api/internal/app/public/" + T + "/file", "/api/internal/app/public/{token}/file"))
                .isEqualTo("/api/internal/app/public/{token}/file");
        assertThat(LogSafePath.of("/api/x/42/" + T, "/api/x/{id}/{resourceToken:.+}"))
                .isEqualTo("/api/x/42/{token}");
        assertThat(LogSafePath.of("/api/x/" + T + "/y", "/api/x/{shareToken}/y"))
                .isEqualTo("/api/x/{token}/y");
    }

    @Test
    @DisplayName("Ids, slugs and every non-token route stay readable: the line must still say what was called")
    void nonTokenPathsUnchanged() {
        assertThat(LogSafePath.of("/api/workflows/123/runs/456", "/api/workflows/{workflowId}/runs/{runId}"))
                .isEqualTo("/api/workflows/123/runs/456");
        assertThat(LogSafePath.of("/api/catalog/public/bundles/latest")).isEqualTo("/api/catalog/public/bundles/latest");
        assertThat(LogSafePath.of("/api/chat/conversations/9")).isEqualTo("/api/chat/conversations/9");
        assertThat(LogSafePath.of("/api/internal/approval-callback/discord/77")).isEqualTo("/api/internal/approval-callback/discord/77");
        assertThat(LogSafePath.of("/webhook/")).isEqualTo("/webhook/");
    }

    @Test
    @DisplayName("A wildcard or misaligned pattern falls back to the prefix rules instead of guessing")
    void unusablePatternFallsBack() {
        assertThat(LogSafePath.of("/webhook/" + T, "/webhook/**")).isEqualTo("/webhook/{token}");
        assertThat(LogSafePath.of("/api/a/" + T + "/b", "/api/a/{token}")).isEqualTo("/api/a/" + T + "/b");
    }

    @Test
    @DisplayName("Null and empty pass through; masking is idempotent")
    void edgeCases() {
        assertThat(LogSafePath.of(null)).isNull();
        assertThat(LogSafePath.of("")).isEmpty();
        String once = LogSafePath.of("/webhook/" + T);
        assertThat(LogSafePath.of(once)).isEqualTo(once);
    }

    @Test
    @DisplayName("tokenPreview keeps 6 characters of a real token and masks a short one entirely")
    void tokenPreview() {
        assertThat(LogSafePath.tokenPreview("wh_" + T)).isEqualTo("wh_FAK***");
        assertThat(LogSafePath.tokenPreview("short1234567")).isEqualTo("***");
        assertThat(LogSafePath.tokenPreview(null)).isEqualTo("null");
    }

    @Test
    @DisplayName("withoutToken rewrites every occurrence of the token in an exception message")
    void withoutToken() {
        String msg = "I/O error on GET request for \"http://h/by-token/sl_" + T + "\": refused (sl_" + T + ")";
        assertThat(LogSafePath.withoutToken(msg, "sl_" + T)).doesNotContain(T).contains("sl_FAK***");
        assertThat(LogSafePath.withoutToken(null, "x")).isNull();
        assertThat(LogSafePath.withoutToken("m", null)).isEqualTo("m");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "/api/internal/webhook/" + T + "/extra,        /api/internal/webhook/{token}/extra",
            "/api/internal/webhook/agent/" + T + ",        /api/internal/webhook/agent/{token}",
            "/api/internal/chat/" + T + "/unknown,         /api/internal/chat/{token}/unknown",
            "/api/internal/form/" + T + ",                 /api/internal/form/{token}",
            "/api/internal/app/public/" + T + "/x,         /api/internal/app/public/{token}/x",
            "/api/internal/widget/" + T + "/config,        /api/internal/widget/{token}/config",
            "/api/public/share/" + T + ",                  /api/public/share/{token}",
            "/api/shared/c/" + T + "/messages,             /api/shared/c/{token}/messages",
    })
    @DisplayName("The internal forms of the public routes are masked too (unmatched request, and the MDC)")
    void internalFormsMasked(String path, String expected) {
        assertThat(LogSafePath.of(path)).isEqualTo(expected).doesNotContain(T);
    }

    @Test
    @DisplayName("A pattern whose literal segments differ from the path is not used to mask by position")
    void patternMustDescribeThePath() {
        assertThat(LogSafePath.of("/api/other/" + T + "/end", "/api/x/{token}/end"))
                .isEqualTo("/api/other/" + T + "/end");
    }

    @Test
    @DisplayName("A matched pattern without a token variable wins over the prefix rules (no false positive)")
    void matchedNonTokenPatternWinsOverPrefixes() {
        assertThat(LogSafePath.of("/api/internal/chat/sync", "/api/internal/chat/sync"))
                .isEqualTo("/api/internal/chat/sync");
        assertThat(LogSafePath.of("/api/internal/widget/loader.js", "/api/internal/widget/loader.js"))
                .isEqualTo("/api/internal/widget/loader.js");
        // Without a pattern (e.g. an unmatched request), the prefix rule still applies.
        assertThat(LogSafePath.of("/api/internal/chat/" + T)).isEqualTo("/api/internal/chat/{token}");
    }
}
