package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 3-way alignment guard for the media node's output schema: the NodeSpec must declare
 * exactly the fields the runtime emits (v1 file/duration/probe flats + the v2
 * frame-only timestamp_seconds), and customTransform must pass timestamp_seconds
 * through untouched (it is a real output, not engine envelope).
 */
@DisplayName("MediaNodeSpec - 3-way alignment: output declarations + customTransform passthrough")
class MediaNodeSpecTest {

    private final MediaNodeSpec spec = new MediaNodeSpec();

    private Set<String> outputKeys() {
        return spec.definition().outputs().stream()
            .map(OutputFieldDef::key)
            .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("definition: nodeType MEDIA / core category / core variable prefix")
    void coreMetadata() {
        NodeDefinition def = spec.definition();
        assertThat(def.nodeType()).isEqualTo("MEDIA");
        assertThat(def.category()).isEqualTo("core");
        assertThat(def.variablePrefix()).isEqualTo("core");
    }

    @Test
    @DisplayName("outputs: declares timestamp_seconds (frame only) alongside the v1 fields, nothing dropped")
    void declaresTimestampSecondsWithV1Fields() {
        assertThat(outputKeys()).containsExactlyInAnyOrder(
            "file", "duration_seconds", "timestamp_seconds",
            "size_bytes", "format_name", "bit_rate", "has_video", "has_audio",
            "video", "audio");
    }

    @Test
    @DisplayName("timestamp_seconds is typed number and its description marks it frame-only")
    void timestampSecondsIsFrameOnlyNumber() {
        OutputFieldDef field = spec.definition().outputs().stream()
            .filter(f -> "timestamp_seconds".equals(f.key()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("timestamp_seconds output not declared on MediaNodeSpec"));
        assertThat(field.type()).isEqualTo("number");
        assertThat(field.description())
            .as("agents must learn from the schema that only frame emits this field")
            .containsIgnoringCase("frame only");
    }

    @Nested
    @DisplayName("customTransform - passthrough minus engine envelope")
    class CustomTransform {

        @Test
        @DisplayName("a frame output keeps file + timestamp_seconds (null duration) and loses node_type")
        void frameOutputKeepsTimestampSeconds() {
            Map<String, Object> backend = new LinkedHashMap<>();
            backend.put("file", Map.of("_type", "file", "path", "t/x.jpg"));
            backend.put("duration_seconds", null);
            backend.put("timestamp_seconds", 7.5);
            backend.put("node_type", "MEDIA");

            Map<String, Object> result = spec.customTransform(backend);

            assertThat(result)
                .containsEntry("timestamp_seconds", 7.5)
                .containsKey("file")
                .doesNotContainKey("node_type");
        }

        @Test
        @DisplayName("a non-frame output simply has no timestamp_seconds key - the transform never invents one")
        void nonFrameOutputHasNoTimestampSeconds() {
            Map<String, Object> backend = new LinkedHashMap<>();
            backend.put("file", Map.of("_type", "file", "path", "t/x.mp4"));
            backend.put("duration_seconds", 20.5);

            Map<String, Object> result = spec.customTransform(backend);

            assertThat(result).doesNotContainKey("timestamp_seconds");
            assertThat(result).containsEntry("duration_seconds", 20.5);
        }
    }
}
