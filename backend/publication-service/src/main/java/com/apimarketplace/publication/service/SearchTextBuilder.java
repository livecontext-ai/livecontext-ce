package com.apimarketplace.publication.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Builds the denormalized {@code search_text} blob persisted on
 * {@link com.apimarketplace.publication.domain.WorkflowPublicationEntity#getSearchText()}.
 *
 * <p>Tokens are lowercased, deduplicated, joined by space. Tokens shorter
 * than 2 chars are dropped. Total length is capped at {@link #MAX_LENGTH};
 * once reached, further additions are silently ignored and a warning is
 * logged on {@link #build(UUID, String)}.
 *
 * <p>Used by the three publish paths:
 * <ul>
 *   <li>{@code WorkflowPublicationService.enrichAndSetPlanSnapshot} (workflow apps)
 *   <li>{@code AgentPublicationService.publishAgent} (standalone agents)
 *   <li>{@code ResourcePublicationService.publishResource} (TABLE/INTERFACE/SKILL)
 * </ul>
 */
public final class SearchTextBuilder {

    private static final int MAX_LENGTH = 10_000;
    private static final int MAX_AGENT_DEPTH = 8;
    private static final Logger log = LoggerFactory.getLogger(SearchTextBuilder.class);

    private final LinkedHashSet<String> tokens = new LinkedHashSet<>();
    private int currentLength = 0;
    private boolean truncated = false;

    private SearchTextBuilder() {}

    public static SearchTextBuilder create() {
        return new SearchTextBuilder();
    }

    /**
     * Add a piece of text. Null / blank skipped. Tokenised on whitespace,
     * lowercased, tokens shorter than 2 chars dropped, deduped. Once the
     * total length exceeds {@link #MAX_LENGTH} the truncated flag is set
     * and all subsequent additions are silently ignored.
     */
    public SearchTextBuilder add(String text) {
        if (text == null || text.isBlank() || truncated) return this;
        for (String tok : text.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (tok.length() < 2) continue;
            if (tokens.add(tok)) {
                currentLength += tok.length() + 1;
                if (currentLength > MAX_LENGTH) {
                    truncated = true;
                    return this;
                }
            }
        }
        return this;
    }

    /**
     * Extracts indexable text from a workflow plan snapshot.
     * Plan structure (from {@code WorkflowPlanParser}):
     * <pre>
     *   { triggers:[{label, description?, ...}],
     *     mcps:    [{label, ...}],
     *     cores:   [{label, ...}],
     *     agents:  [{label, name?, role?, description?, ...}],
     *     interfaces:[{title?, description?, name?, label, ...}],
     *     tables:  [{label, name?, dataSourceId, ...}] }
     * </pre>
     * Each list optional. Null-safe.
     */
    public SearchTextBuilder fromPlanSnapshot(Map<String, Object> plan) {
        if (plan == null || plan.isEmpty()) return this;
        extractListField(plan, "interfaces", List.of("title", "description", "name", "label"));
        extractListField(plan, "mcps",       List.of("label"));
        extractListField(plan, "triggers",   List.of("label", "description"));
        extractListField(plan, "agents",     List.of("label", "name", "role", "description"));
        extractListField(plan, "cores",      List.of("label"));
        extractListField(plan, "tables",     List.of("label", "name"));
        return this;
    }

    /**
     * Extracts indexable text from an agent snapshot.
     * Agent structure (from {@code AgentPublicationService.buildAgentSnapshot}):
     * <pre>
     *   { id, name?, description?, role?, title?,
     *     skills:   [{name?, description?}],
     *     subAgents:[<recursive>],
     *     landingInterface?: {title?, description?} }
     * </pre>
     * Cycle-safe: visited set keyed on agent id, with identityHashCode
     * fallback when id is missing. Depth-capped at {@link #MAX_AGENT_DEPTH}.
     */
    public SearchTextBuilder fromAgentSnapshot(Map<String, Object> agent) {
        return fromAgentSnapshotInternal(agent, new HashSet<>(), 0);
    }

    @SuppressWarnings("unchecked")
    private SearchTextBuilder fromAgentSnapshotInternal(Map<String, Object> agent,
                                                         Set<String> visited, int depth) {
        if (agent == null || agent.isEmpty() || depth > MAX_AGENT_DEPTH) return this;
        Object idObj = agent.get("id");
        String key = idObj != null ? idObj.toString() : "anon-" + System.identityHashCode(agent);
        if (!visited.add(key)) return this;

        addStringField(agent, "name");
        addStringField(agent, "description");
        addStringField(agent, "role");
        addStringField(agent, "title");

        forEachMap(agent.get("skills"), m -> {
            addStringField(m, "name");
            addStringField(m, "description");
        });
        forEachMap(agent.get("subAgents"),
                m -> fromAgentSnapshotInternal(m, visited, depth + 1));

        Object landing = agent.get("landingInterface");
        if (landing instanceof Map<?, ?> lm) {
            addStringField((Map<String, Object>) lm, "title");
            addStringField((Map<String, Object>) lm, "description");
        }
        return this;
    }

    /**
     * Extracts indexable text from a standalone resource snapshot
     * (TABLE / INTERFACE / SKILL). Generic top-level keys plus columns and
     * landing-interface metadata. Null-safe.
     */
    @SuppressWarnings("unchecked")
    public SearchTextBuilder fromResourceSnapshot(Map<String, Object> resource) {
        if (resource == null) return this;
        addStringField(resource, "name");
        addStringField(resource, "description");
        addStringField(resource, "title");
        addStringField(resource, "label");

        forEachMap(resource.get("columns"), m -> {
            addStringField(m, "name");
            addStringField(m, "label");
        });

        Object landing = resource.get("landingInterface");
        if (landing instanceof Map<?, ?> lm) {
            addStringField((Map<String, Object>) lm, "title");
            addStringField((Map<String, Object>) lm, "description");
        }
        return this;
    }

    public String build(UUID publicationId, String publicationType) {
        if (truncated) {
            log.warn("[SearchTextBuilder] truncated at {} chars (publicationId={}, type={}, tokens={})",
                    MAX_LENGTH, publicationId, publicationType, tokens.size());
        }
        return String.join(" ", tokens);
    }

    @SuppressWarnings("unchecked")
    private void extractListField(Map<String, Object> plan, String listKey, List<String> stringKeys) {
        Object raw = plan.get(listKey);
        if (!(raw instanceof List<?> list)) return;
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                for (String k : stringKeys) addStringField((Map<String, Object>) m, k);
            }
        }
    }

    private void addStringField(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof String s) add(s);
    }

    @SuppressWarnings("unchecked")
    private void forEachMap(Object listOrNull, Consumer<Map<String, Object>> fn) {
        if (!(listOrNull instanceof List<?> list)) return;
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) fn.accept((Map<String, Object>) m);
        }
    }
}
