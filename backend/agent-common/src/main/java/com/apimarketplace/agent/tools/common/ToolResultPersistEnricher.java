package com.apimarketplace.agent.tools.common;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared persistence + enrichment helper for {@link com.apimarketplace.agent.tools.ToolsProvider}
 * implementations whose successful results need to be:
 *
 * <ol>
 *   <li>Persisted as a typed Interface entity (so the chat side panel can
 *       reload the result later via {@code interfaceId}),</li>
 *   <li>Enriched with a {@code [visualize:&lt;type&gt;:&lt;id&gt;]} marker in
 *       {@code data} so the LLM can reference the persisted artifact in its
 *       reply, and</li>
 *   <li>Decorated with {@code metadata.visualization = {type, id, title}} so
 *       the streaming callback opens the right side-panel card without the
 *       LLM having to ask.</li>
 * </ol>
 *
 * <p>This logic was originally inlined in {@code WebSearchToolsProvider}
 * (~100 LOC). Adding a second consumer (image generation) without
 * extracting it would have duplicated the credential-extraction guard, the
 * marker emission, and the metadata.visualization shape - and any later
 * change to the marker grammar would have to be done in two places. By
 * funnelling both providers through this helper, the marker contract stays
 * mechanically aligned with the frontend's
 * {@code MarkdownRender.tsx VISUALIZE_TYPES} regex.
 *
 * <p><b>Scope-specific extras</b> &mdash; some providers need post-persist
 * side effects that are not generic (web-search stores a
 * {@code toolCallId → interfaceId} mapping in Redis so an async screenshot
 * callback can reach the right interface). These are passed via the
 * {@link PostPersistHook} parameter; image-generation passes {@code null}.
 *
 * <p>Thread-safe: stateless static method.
 */
public final class ToolResultPersistEnricher {

    private static final Logger log = LoggerFactory.getLogger(ToolResultPersistEnricher.class);

    private ToolResultPersistEnricher() {}

    /**
     * Persist {@code originalResult} (whose {@code success() == true} is the
     * caller's responsibility to verify) and return an enriched
     * {@link ToolExecutionResult} carrying display + marker + visualization
     * metadata.
     *
     * <p>If the credentials map lacks the chat identifiers needed to attach
     * the Interface to a conversation, returns {@code originalResult}
     * unchanged - the LLM still receives its result, no visualization card
     * is rendered. Same posture as the original WebSearch implementation.
     *
     * <p>If {@code persistFn} returns {@code null} (Interface service down,
     * validation error, …), logs a warning and returns {@code originalResult}
     * - the caller-visible behaviour is identical to a successful tool
     * execution without the visualization sugar.
     *
     * @param originalResult  the underlying tool result (must already be successful)
     * @param parameters       original tool-call parameters (passed through to {@code persistFn})
     * @param context          tool execution context (carries credentials, tenantId)
     * @param visualizationType frontend type discriminator (e.g. {@code "web_search"},
     *                          {@code "agent_browse"}, {@code "image_generation"});
     *                          must match a value in {@code MarkdownRender.tsx VISUALIZE_TYPES}
     * @param persistFn        adapter that builds the provider-specific Interface DTO
     *                          and calls the matching {@code interfaceClient.createOrUpdate*}
     *                          method, returning the persisted entity (or null on failure)
     * @param postPersistHook  optional side effect after successful persist
     *                          (web-search uses this for the Redis tool→iface mapping);
     *                          may be {@code null}; exceptions are logged and swallowed
     */
    /**
     * 6-arg overload retained for providers that don't carry a binary
     * payload to strip (e.g. {@code web_search} routes around this helper
     * for screenshots, but the textual paths use the 6-arg form). New
     * providers shipping bytes (audio, video, PDF, …) MUST use the 7-arg
     * form with a {@link StripFn} - otherwise the agent receives the raw
     * bytes inline and blows the tool-result token budget. Lint rule:
     * any {@code persistFn} that calls {@code req.setData(map)} where
     * {@code map} contains a binary field MUST be paired with a stripFn.
     */
    public static ToolExecutionResult enrichAndPersist(
            ToolExecutionResult originalResult,
            Map<String, Object> parameters,
            ToolExecutionContext context,
            String visualizationType,
            PersistFn persistFn,
            PostPersistHook postPersistHook) {
        return enrichAndPersist(originalResult, parameters, context, visualizationType,
                persistFn, postPersistHook, /* stripFn */ null);
    }

    /**
     * Full overload accepting a {@link StripFn}. Centralizes the
     * post-persist strip pattern so providers (image-gen, audio-gen, future
     * PDF/video) declare what to remove from the agent-visible result once
     * the bytes are safely in the Interface entity. Without this, every
     * provider has to re-implement the strip and one will get it wrong -
     * the iteration-5 audit identified this as the architectural gap that
     * caused the {@code image_generation} 1.98 MB leak.
     */
    public static ToolExecutionResult enrichAndPersist(
            ToolExecutionResult originalResult,
            Map<String, Object> parameters,
            ToolExecutionContext context,
            String visualizationType,
            PersistFn persistFn,
            PostPersistHook postPersistHook,
            StripFn stripFn) {

        try {
            Map<String, Object> credentials = context != null ? context.credentials() : null;
            String conversationId = credentials != null ? (String) credentials.get("conversationId") : null;
            String messageId = credentials != null ? (String) credentials.get("__messageId__") : null;

            if (conversationId == null || messageId == null) {
                log.debug("[{}] Missing conversationId or messageId, skipping interface persistence", visualizationType);
                return originalResult;
            }

            PersistedInterface persisted = persistFn.persist(context, parameters, originalResult.data());
            if (persisted == null || persisted.id() == null) {
                log.warn("[{}] Persist function returned null/empty interface - returning original result", visualizationType);
                return originalResult;
            }

            // Optional scope-specific post-persist side effect.
            if (postPersistHook != null) {
                try {
                    postPersistHook.afterPersist(context, parameters, persisted);
                } catch (Exception hookEx) {
                    log.warn("[{}] postPersistHook failed (non-fatal): {}", visualizationType, hookEx.getMessage());
                }
            }

            String interfaceId = persisted.id();
            String displayTitle = persisted.title();

            // Apply the provider's strip BEFORE copying into enrichedData so
            // the bytes never enter the agent-visible map. A null stripFn
            // (or a strip that returns null) means "use the original data
            // verbatim" - back-compat for providers that don't carry binary.
            Object dataForAgent = originalResult.data();
            if (stripFn != null) {
                try {
                    Object stripped = stripFn.stripAfterPersist(context, parameters, originalResult.data(), persisted);
                    if (stripped != null) dataForAgent = stripped;
                } catch (Exception stripEx) {
                    log.warn("[{}] stripFn failed (non-fatal - agent will see original data): {}",
                            visualizationType, stripEx.getMessage());
                }
            }

            Map<String, Object> enrichedData = new LinkedHashMap<>();
            if (dataForAgent instanceof Map<?, ?> resultMap) {
                enrichedData.putAll(asStringKeyMap(resultMap));
            } else if (dataForAgent != null) {
                enrichedData.put("result", dataForAgent);
            }

            enrichedData.put("display", Map.of(
                    "type", visualizationType,
                    "id", interfaceId,
                    "title", displayTitle != null ? displayTitle : ""
            ));
            enrichedData.put("marker", "[visualize:" + visualizationType + ":" + interfaceId + "]");

            Map<String, Object> enrichedMetadata = new HashMap<>(
                    originalResult.metadata() != null ? originalResult.metadata() : Map.of());
            enrichedMetadata.put("visualization", Map.of(
                    "type", visualizationType,
                    "id", interfaceId,
                    "title", displayTitle != null ? displayTitle : ""
            ));

            return ToolExecutionResult.success(enrichedData, enrichedMetadata);

        } catch (Exception e) {
            log.warn("[{}] enrichAndPersist threw - returning original result: {}",
                    visualizationType, e.getMessage());
            return originalResult;
        }
    }

    /**
     * Deep-copy a result-data map so callers can mutate the copy before
     * stuffing it into a persistence DTO without leaking changes back into
     * the LLM-visible result. Lifted from the original WebSearch
     * implementation; kept here so future providers don't reinvent it.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepCopyResultData(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> mapVal) {
                copy.put(entry.getKey(), deepCopyResultData(asStringKeyMap(mapVal)));
            } else if (value instanceof List<?> listVal) {
                List<Object> listCopy = new java.util.ArrayList<>(listVal.size());
                for (Object item : listVal) {
                    if (item instanceof Map<?, ?> mapItem) {
                        listCopy.add(deepCopyResultData(asStringKeyMap(mapItem)));
                    } else {
                        listCopy.add(item);
                    }
                }
                copy.put(entry.getKey(), listCopy);
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        return copy;
    }

    /** Coerces a {@code Map<?, ?>} (e.g. from JSON deserialisation) to {@code Map<String, Object>}. */
    public static Map<String, Object> asStringKeyMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    /**
     * Provider-specific persistence adapter. Receives the tool's
     * {@code originalData} so the implementation can reshape it (deep-copy,
     * field selection, …) into whatever its Interface DTO requires.
     *
     * <p>Implementations should return {@code null} on persistence failure
     * rather than throw - the helper logs at warn level and returns the
     * un-enriched result, which is the same posture as a successful
     * tool execution without persistence sugar.
     */
    @FunctionalInterface
    public interface PersistFn {
        PersistedInterface persist(ToolExecutionContext context,
                                    Map<String, Object> parameters,
                                    Object originalData) throws Exception;
    }

    /**
     * Optional scope-specific side effect run after successful persistence.
     * Web-search uses this to store a {@code toolCallId → interfaceId} entry
     * in Redis so an async screenshot callback (which doesn't have access
     * to the original ToolExecutionContext) can find the right Interface.
     * Exceptions are logged and swallowed - they never affect the
     * caller-visible result.
     */
    @FunctionalInterface
    public interface PostPersistHook {
        void afterPersist(ToolExecutionContext context,
                          Map<String, Object> parameters,
                          PersistedInterface persisted) throws Exception;
    }

    /**
     * Optional post-persist transform that produces the agent-visible data
     * map. Lets a provider declare which fields to strip (image bytes, audio
     * blobs, PDF base64, …) once persistence has succeeded, so the LLM
     * receives the marker + metadata + lightweight descriptors but NEVER
     * the megabytes of raw binary that already live in the persisted
     * Interface entity.
     *
     * <p>Centralized here (not per-provider) so future image / audio /
     * video / document tools can't silently leak the same way
     * {@code image_generation} did pre-v1.9 - every provider that persists
     * ALSO declares its strip in one place.
     *
     * <p>The function receives the raw original data (the same Map the
     * persistFn just consumed) and returns the rewritten data the agent
     * will see. Returning {@code null} is treated as "no strip" and the
     * original data is reused verbatim. Exceptions inside the strip are
     * caught and logged; the caller still receives an enriched result
     * (with the original data) so the agent path never breaks because of
     * a strip bug.
     */
    @FunctionalInterface
    public interface StripFn {
        Object stripAfterPersist(ToolExecutionContext context,
                                  Map<String, Object> parameters,
                                  Object originalData,
                                  PersistedInterface persisted) throws Exception;
    }

    /**
     * Minimal facade over the persisted Interface entity. Decouples this
     * helper (in {@code agent-common}) from the {@code interface-client}
     * module's {@code InterfaceDto} type so this jar doesn't pull in a
     * domain dependency. Callers wrap their concrete DTO in this record.
     */
    public record PersistedInterface(String id, String title) {}
}
