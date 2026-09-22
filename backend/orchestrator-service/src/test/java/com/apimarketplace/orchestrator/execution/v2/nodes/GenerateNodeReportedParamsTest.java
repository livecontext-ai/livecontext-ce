package com.apimarketplace.orchestrator.execution.v2.nodes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a generate node reports it was charged for.
 *
 * <p>Its parameters are the author's own map, so both halves of the gate apply: a
 * credential-named key must not reach the row, and a prompt has no length limit while a
 * reference image can arrive as a data URI - onto the step row of every item. Neither was
 * asserted; `GenerateNodeTest` never reads `resolved_params`.
 *
 * <p>Reached by reflection because the builder is private and the node's own execute path
 * needs a provider, a credential and a live generation. The alternative was no test at all,
 * which is what there was.
 */
@DisplayName("a generate node reports what it ran with, masked and bounded")
class GenerateNodeReportedParamsTest {

    private static final String SECRET = "sk-live-should-never-be-persisted";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> reportable(Map<String, Object> source) throws Exception {
        Method method = GenerateNode.class.getDeclaredMethod("reportableParams", Map.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(null, source);
    }

    @Test
    @DisplayName("the credential id is dropped and a credential-named param is masked: two rules, both needed")
    void masksTheCredentialsAmongTheAuthorsOwnParams() throws Exception {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("model", "gpt-image-1");
        source.put("prompt", "a cat wearing a hat");
        source.put("credential_id", 42L);
        source.put("api_key", SECRET);

        Map<String, Object> reported = reportable(source);

        assertThat(reported).doesNotContainKey("credential_id");
        assertThat(reported.get("api_key")).isEqualTo("<withheld: credential>");
        assertThat(reported.toString()).doesNotContain(SECRET);
        // And what a reader came for survives: this is a generation they were charged for.
        assertThat(reported.get("model")).isEqualTo("gpt-image-1");
        assertThat(reported.get("prompt")).isEqualTo("a cat wearing a hat");
    }

    @Test
    @DisplayName("a reference image arriving as a data URI is described, not copied onto the row")
    void boundsADataUriReferenceImage() throws Exception {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("model", "gpt-image-1");
        source.put("input_image", "data:image/png;base64," + "A".repeat(50_000));

        Map<String, Object> reported = reportable(source);

        assertThat(String.valueOf(reported.get("input_image")).length())
            .as("a data URI on the step row of every item is the payload this bound exists for")
            .isLessThan(500);
        assertThat(reported.get("model")).isEqualTo("gpt-image-1");
    }

    @Test
    @DisplayName("a null parameter is dropped rather than reported as an absent one")
    void dropsNullParams() throws Exception {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("model", "gpt-image-1");
        source.put("size", null);

        assertThat(reportable(source)).doesNotContainKey("size");
    }
}
