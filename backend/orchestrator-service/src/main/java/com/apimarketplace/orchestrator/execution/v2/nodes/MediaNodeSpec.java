package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import com.apimarketplace.orchestrator.services.persistence.schema.GenericOutputSchemaMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Node specification for Media (audio/video processing).
 *
 * <p>Output shape depends on the operation: {@code mux_audio}/{@code mix}/{@code
 * extract_audio}/{@code concat}/{@code overlay} emit {@code file} +
 * {@code duration_seconds}; {@code frame} emits {@code file} (an image) +
 * {@code timestamp_seconds} with a null {@code duration_seconds}; {@code probe} emits
 * the FLAT metadata fields ({@code duration_seconds}, {@code size_bytes},
 * {@code format_name}, {@code bit_rate}, {@code has_video}, {@code has_audio},
 * {@code video}, {@code audio}) and no file. Operation-only fields are marked as such
 * in their descriptions (the union-documentation style used across specs for
 * operation-dependent outputs).</p>
 */
@Component
public class MediaNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("MEDIA")
            .label("Media")
            .category("core")
            .variablePrefix("core")
            .description("Processes audio/video files: probe metadata, mux audio onto video, mix tracks, "
                + "extract audio, concat videos, extract a still frame, overlay an image (watermark)")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("file")
                    .type("object")
                    .description("Canonical FileRef of the produced media (mux_audio/concat/overlay: mp4; "
                        + "mix: mp4 with video else mp3/wav/aac; extract_audio: mp3/wav/aac; frame: "
                        + "image/jpeg or image/png). Null for probe. Reference via "
                        + "{{core:label.output.file}} and map the WHOLE object into downstream file params.")
                    .children(FileRefSchema.children())
                    .build(),
                OutputFieldDef.builder()
                    .key("duration_seconds")
                    .type("number")
                    .description("Duration in seconds: the produced file's duration for mux_audio/mix/"
                        + "extract_audio/concat/overlay, the probed file's duration for probe. Null for "
                        + "frame (a still image has no duration).")
                    .build(),
                OutputFieldDef.builder()
                    .key("timestamp_seconds")
                    .type("number")
                    .description("frame only: the ACTUAL timestamp the still was taken at, after the "
                        + "default (the middle of the video) and the end-of-video clamp. Null for every "
                        + "other operation.")
                    .build(),
                OutputFieldDef.builder()
                    .key("size_bytes")
                    .type("number")
                    .description("probe only: file size in bytes")
                    .build(),
                OutputFieldDef.builder()
                    .key("format_name")
                    .type("string")
                    .description("probe only: container format name (e.g. 'mov,mp4,m4a,...' or 'mp3')")
                    .build(),
                OutputFieldDef.builder()
                    .key("bit_rate")
                    .type("number")
                    .description("probe only: overall bit rate in bits per second, null when unknown")
                    .build(),
                OutputFieldDef.builder()
                    .key("has_video")
                    .type("boolean")
                    .description("probe only: true when the file contains a video stream")
                    .build(),
                OutputFieldDef.builder()
                    .key("has_audio")
                    .type("boolean")
                    .description("probe only: true when the file contains an audio stream")
                    .build(),
                OutputFieldDef.builder()
                    .key("video")
                    .type("object")
                    .description("probe only: {codec, width, height, fps} of the video stream, null when has_video is false")
                    .build(),
                OutputFieldDef.builder()
                    .key("audio")
                    .type("object")
                    .description("probe only: {codec, sample_rate, channels} of the audio stream, null when has_audio is false")
                    .build()
            ))
            .keywords(List.of("media", "audio", "video", "mux", "mix", "duck", "extract", "probe",
                "ffmpeg", "soundtrack", "music", "voiceover", "concat", "stitch", "trim", "crossfade",
                "frame", "thumbnail", "cover", "overlay", "watermark", "logo"))
            .build();
    }

    @Override
    public Map<String, Object> customTransform(Map<String, Object> backendOutput) {
        if (backendOutput == null) return new LinkedHashMap<>();
        Map<String, Object> result = new LinkedHashMap<>(backendOutput);
        GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS.forEach(result::remove);
        return result;
    }
}
