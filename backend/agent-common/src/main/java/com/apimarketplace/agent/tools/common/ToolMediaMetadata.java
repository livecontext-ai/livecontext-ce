package com.apimarketplace.agent.tools.common;

import com.apimarketplace.agent.domain.MessageAttachment;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Convention + helpers for carrying raw media bytes (e.g. an image a vision-capable
 * model must SEE) on a tool result's {@code metadata} map.
 *
 * <p>There are TWO consumers that turn these descriptors into native image content
 * blocks fed to the model, one per agent execution path:
 * <ul>
 *   <li><b>CLI/bridge path</b> - the MCP bridge (agent-cli-server.mjs / toolContent.mjs)
 *       converts each descriptor into a native MCP {@code {type:'image'}} block.</li>
 *   <li><b>Direct-API path</b> - the agent loop ({@code AgentLoopExecutor}) calls
 *       {@link #toImageAttachments(Map)} to turn the descriptors into
 *       {@link MessageAttachment} images carried on a synthetic USER message; each
 *       {@code LLMProvider} already serialises USER attachments to its provider-native
 *       image block.</li>
 * </ul>
 *
 * <p>Every OTHER metadata sink - the Redis chat stream and the observability persistence
 * rows - MUST drop the heavy bytes first via {@link #withoutHeavyMedia(Map)}: a multi-MB
 * base64 string has no value to the frontend (it already has the file URL / click-to-open
 * card) and would bloat Redis messages and JSONB observability rows.
 *
 * <p>Descriptor shape (one entry per image):
 * <pre>{ "type": "image", "mimeType": "image/png", "dataBase64": "&lt;base64&gt;" }</pre>
 */
public final class ToolMediaMetadata {

    /** Metadata key holding a {@code List<Map>} of media descriptors (see class doc). */
    public static final String MEDIA_KEY = "__media__";

    /** Lightweight replacement key written by {@link #withoutHeavyMedia(Map)}. */
    public static final String MEDIA_SUMMARY_KEY = "__media_summary__";

    /** Descriptor field: media type discriminator (currently only {@code "image"}). */
    public static final String TYPE = "type";
    /** Descriptor field: IANA mime type, e.g. {@code image/png}. */
    public static final String MIME_TYPE = "mimeType";
    /** Descriptor field: base64-encoded raw bytes. */
    public static final String DATA_BASE64 = "dataBase64";

    private ToolMediaMetadata() {}

    /** Build a single image descriptor (see class doc for the shape). */
    public static Map<String, Object> imageDescriptor(String mimeType, String base64) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put(TYPE, "image");
        d.put(MIME_TYPE, mimeType);
        d.put(DATA_BASE64, base64);
        return d;
    }

    /**
     * Extract the {@link #MEDIA_KEY} image descriptors from a tool result's metadata as
     * {@link MessageAttachment} images, for the direct-API agent loop to carry on a
     * synthetic USER message (see class doc). Decodes the base64 once here; the per-image
     * byte cap is enforced downstream by each provider's attachment size guard, so this
     * method does not cap bytes - it only skips malformed / non-image entries.
     *
     * <p>Returns an empty list (never {@code null}) when there is no media, the payload
     * is not a list, or every entry is unusable.</p>
     */
    public static List<MessageAttachment> toImageAttachments(Map<String, Object> metadata) {
        List<MessageAttachment> attachments = new ArrayList<>();
        if (metadata == null) {
            return attachments;
        }
        Object media = metadata.get(MEDIA_KEY);
        if (!(media instanceof List<?> list)) {
            return attachments;
        }
        int idx = 0;
        for (Object entry : list) {
            idx++;
            if (!(entry instanceof Map<?, ?> descriptor)) {
                continue;
            }
            Object type = descriptor.get(TYPE);
            if (type != null && !"image".equals(type)) {
                continue; // only images are vision-capable today
            }
            Object base64 = descriptor.get(DATA_BASE64);
            if (!(base64 instanceof String b64) || b64.isBlank()) {
                continue;
            }
            Object mime = descriptor.get(MIME_TYPE);
            String mimeType = (mime instanceof String s && !s.isBlank()) ? s : "image/png";
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(b64);
            } catch (IllegalArgumentException ex) {
                continue; // malformed base64 - skip rather than poison the request
            }
            if (bytes.length == 0) {
                continue;
            }
            attachments.add(MessageAttachment.image(bytes, mimeType, "tool-image-" + idx));
        }
        return attachments;
    }

    /**
     * Return a copy of {@code metadata} with the heavy {@link #MEDIA_KEY} payload
     * replaced by a lightweight summary (block count), so non-bridge sinks never carry
     * the raw base64. Returns the same instance untouched when there is nothing to
     * strip. Never mutates the input.
     */
    public static Map<String, Object> withoutHeavyMedia(Map<String, Object> metadata) {
        if (metadata == null || !metadata.containsKey(MEDIA_KEY)) {
            return metadata;
        }
        Map<String, Object> copy = new LinkedHashMap<>(metadata);
        Object media = copy.remove(MEDIA_KEY);
        int count = (media instanceof List<?> list) ? list.size() : 1;
        copy.put(MEDIA_SUMMARY_KEY, count + " media block(s) omitted (sent to vision channel only)");
        return copy;
    }
}
