package com.apimarketplace.publication.service;

import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Read the {@code showcase_snapshot} JSONB stored on a publication entity
 * and shape it into the response payloads the marketplace anonymous
 * controllers serve. By going straight to the publication's frozen JSONB
 * the marketplace stops depending on the orchestrator at request time -
 * the source run can spawn new epochs / get re-pinned without ever
 * affecting the public preview.
 *
 * <p>All accessors return an empty Optional when the snapshot is missing
 * or its sub-tree is empty, so callers can fall back to the legacy
 * orchestrator path while existing publications are being backfilled.
 */
@Service
public class ShowcaseSnapshotReader {

    private static final Logger log = LoggerFactory.getLogger(ShowcaseSnapshotReader.class);

    private final ShowcaseFileRefRewriter fileRefRewriter;

    public ShowcaseSnapshotReader(ShowcaseFileRefRewriter fileRefRewriter) {
        this.fileRefRewriter = fileRefRewriter;
    }

    /**
     * @return true when the publication has a {@code showcase_snapshot} JSONB
     *         that is non-empty - i.e. the marketplace can render fully from
     *         JSONB without falling back to orchestrator.
     */
    public boolean hasSnapshot(WorkflowPublicationEntity pub) {
        Map<String, Object> snap = pub.getShowcaseSnapshot();
        return snap != null && !snap.isEmpty();
    }

    /**
     * Return the {@code runState} sub-tree (status, steps, edges, completed
     * sets, plan…) shaped exactly like the orchestrator's
     * {@code /showcase/run-state} response so the existing frontend store
     * can ingest it without branching.
     *
     * <p>Self-heal for legacy snapshots whose {@code runState.edges} is empty:
     * captures made before the per-epoch edge sourcing fix filtered edges
     * through the JSONB epoch node view, which is empty for finished runs
     * (closed epochs are pruned from the StateSnapshot), so every edge was
     * dropped and the marketplace "All epochs" canvas showed no edge
     * statusCounts. When that happens, synthesize the edge list from the
     * snapshot's own {@code epochStates} sub-tree (durable per-epoch counts,
     * the same source the working per-epoch view reads) - no republish needed.
     */
    public Optional<Map<String, Object>> readRunState(WorkflowPublicationEntity pub) {
        Optional<Map<String, Object>> section = readSection(pub, "runState");
        if (section.isEmpty()) return section;

        Object edges = section.get().get("edges");
        if (edges instanceof List<?> list && !list.isEmpty()) {
            return section;
        }
        List<Map<String, Object>> synthesized = synthesizeEdgesFromEpochStates(pub);
        if (synthesized.isEmpty()) {
            return section;
        }
        // Copy before patching - the section map backs the entity's JSONB and
        // must stay intact for other readers / the persistence layer.
        Map<String, Object> healed = new LinkedHashMap<>(section.get());
        healed.put("edges", synthesized);
        return Optional.of(healed);
    }

    /**
     * Build a {@code runState.edges}-shaped list from the snapshot's
     * {@code epochStates} sub-tree, summing counts per edge across epochs
     * ("all epochs" semantics). Entries mirror the orchestrator's
     * {@code EdgeState} serialization ({@code from}/{@code to}/{@code status}/
     * {@code completedCount}/{@code skippedCount}/{@code totalCount}) plus a
     * {@code statusCounts} map preserving statuses EdgeState cannot carry.
     *
     * <p><b>SYNC</b>: the parse/derive logic (arrow split, case-insensitive status
     * summing, status precedence RUNNING &gt; FAILED &gt; COMPLETED &gt; SKIPPED) is
     * mirrored by {@code ShowcaseSnapshotBuilder.epochEdgeStates} in
     * orchestrator-service (the capture-time source for fresh snapshots). The two
     * services share no module, so the copy lives here - keep both sides aligned.
     */
    private static List<Map<String, Object>> synthesizeEdgesFromEpochStates(WorkflowPublicationEntity pub) {
        Map<String, Object> snap = pub.getShowcaseSnapshot();
        Object epochStates = snap != null ? snap.get("epochStates") : null;
        if (!(epochStates instanceof Map<?, ?> esMap) || esMap.isEmpty()) {
            return List.of();
        }

        // edgeKey ("from->to") → status (uppercased) → summed count
        Map<String, Map<String, Integer>> totals = new LinkedHashMap<>();
        for (Object epochEntry : esMap.values()) {
            if (!(epochEntry instanceof Map<?, ?> epochState)) continue;
            Object edgesObj = epochState.get("edges");
            if (!(edgesObj instanceof Map<?, ?> edgeMap)) continue;
            for (Map.Entry<?, ?> edge : edgeMap.entrySet()) {
                if (!(edge.getValue() instanceof Map<?, ?> counts)) continue;
                Map<String, Integer> merged = totals.computeIfAbsent(
                        String.valueOf(edge.getKey()), k -> new LinkedHashMap<>());
                for (Map.Entry<?, ?> count : counts.entrySet()) {
                    if (!(count.getValue() instanceof Number n)) continue;
                    merged.merge(String.valueOf(count.getKey()).toUpperCase(Locale.ROOT),
                            n.intValue(), Integer::sum);
                }
            }
        }

        List<Map<String, Object>> result = new ArrayList<>(totals.size());
        for (Map.Entry<String, Map<String, Integer>> entry : totals.entrySet()) {
            String key = entry.getKey();
            int arrow = key.indexOf("->");
            if (arrow <= 0 || arrow + 2 >= key.length()) continue;
            Map<String, Integer> counts = entry.getValue();
            int completed = counts.getOrDefault("COMPLETED", 0);
            int skipped = counts.getOrDefault("SKIPPED", 0);
            int failed = counts.getOrDefault("FAILED", 0);
            int running = counts.getOrDefault("RUNNING", 0);
            if (completed == 0 && skipped == 0 && failed == 0 && running == 0) continue;
            String status = running > 0 ? "RUNNING"
                    : failed > 0 ? "FAILED"
                    : completed > 0 ? "COMPLETED"
                    : "SKIPPED";
            Map<String, Object> edge = new LinkedHashMap<>();
            edge.put("from", key.substring(0, arrow));
            edge.put("to", key.substring(arrow + 2));
            edge.put("status", status);
            edge.put("completedCount", completed);
            edge.put("skippedCount", skipped);
            edge.put("totalCount", completed + skipped);
            edge.put("statusCounts", counts);
            result.add(edge);
        }
        return result;
    }

    /**
     * Return aggregated-step list, optionally per-epoch.
     * Shape mirrors {@code GET /v2/workflows/dag/instances/{runId}/steps/aggregated}.
     */
    @SuppressWarnings("unchecked")
    public Optional<List<Map<String, Object>>> readAggregatedSteps(WorkflowPublicationEntity pub, Integer epoch) {
        Map<String, Object> snap = pub.getShowcaseSnapshot();
        if (snap == null) return Optional.empty();
        Object aggregated = snap.get("aggregatedSteps");
        if (!(aggregated instanceof Map<?, ?> aggMap)) return Optional.empty();
        if (epoch == null) {
            Object all = aggMap.get("all");
            if (all instanceof List) {
                return Optional.of((List<Map<String, Object>>) all);
            }
            return Optional.empty();
        }
        Object byEpoch = aggMap.get("byEpoch");
        if (byEpoch instanceof Map<?, ?> beMap) {
            Object entry = beMap.get(String.valueOf(epoch));
            if (entry instanceof List) {
                return Optional.of((List<Map<String, Object>>) entry);
            }
        }
        return Optional.empty();
    }

    /**
     * Return active signal waits for a specific epoch.
     * Mirrors {@code GET /v2/workflows/dag/runs/{runId}/epochs/{epoch}/signals}.
     */
    @SuppressWarnings("unchecked")
    public Optional<List<Map<String, Object>>> readEpochSignals(WorkflowPublicationEntity pub, int epoch) {
        Map<String, Object> snap = pub.getShowcaseSnapshot();
        if (snap == null) return Optional.empty();
        Object epochSignals = snap.get("epochSignals");
        if (!(epochSignals instanceof Map<?, ?> esMap)) return Optional.empty();
        Object entry = esMap.get(String.valueOf(epoch));
        if (entry instanceof List) {
            return Optional.of((List<Map<String, Object>>) entry);
        }
        // No active signals for this epoch is a valid empty-list response - but
        // we only synthesise that when the snapshot itself confirms the epoch
        // existed. Return empty Optional otherwise so callers know to 404.
        Optional<Map<String, Object>> rs = readRunState(pub);
        if (rs.isPresent() && epochExistsInRunState(rs.get(), epoch)) {
            return Optional.of(List.of());
        }
        return Optional.empty();
    }

    /**
     * Per-epoch node/edge status counts - same shape as the orchestrator's
     * {@code /showcase/epoch-state}. Reads the snapshotted {@code epochStates}
     * sub-tree first (real edgeCounts from the orchestrator's
     * WorkflowEpochService); falls back to a derived nodeCounts-only view
     * for legacy snapshots that pre-date the {@code epochStates} sub-tree.
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> readEpochState(WorkflowPublicationEntity pub, int epoch) {
        Map<String, Object> snap = pub.getShowcaseSnapshot();
        if (snap != null) {
            Object epochStates = snap.get("epochStates");
            if (epochStates instanceof Map<?, ?> esMap) {
                Object entry = esMap.get(String.valueOf(epoch));
                if (entry instanceof Map<?, ?> m && !m.isEmpty()) {
                    return Optional.of((Map<String, Object>) m);
                }
            }
        }

        // Fallback for snapshots captured before V160.1: derive nodeCounts
        // from the steps array (no edge counts available in this path).
        Optional<Map<String, Object>> rsOpt = readRunState(pub);
        if (rsOpt.isEmpty()) return Optional.empty();
        Map<String, Object> rs = rsOpt.get();
        if (!epochExistsInRunState(rs, epoch)) {
            return Optional.empty();
        }
        Map<String, Integer> nodeCounts = new LinkedHashMap<>();
        Object steps = rs.get("steps");
        if (steps instanceof List<?> stepList) {
            for (Object stepObj : stepList) {
                if (!(stepObj instanceof Map<?, ?> step)) continue;
                Object stepEpochsObj = ((Map<String, Object>) step).get("epoch");
                Integer stepEpoch = (stepEpochsObj instanceof Number n) ? n.intValue() : null;
                if (stepEpoch != null && stepEpoch != epoch) continue;
                Object status = ((Map<String, Object>) step).get("status");
                if (status != null) {
                    nodeCounts.merge(status.toString(), 1, Integer::sum);
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("epoch", epoch);
        result.put("nodeCounts", nodeCounts);
        result.put("edgeCounts", Map.of());
        return Optional.of(result);
    }

    /**
     * Pre-rendered interface payload for a given (interface, page, size, epoch).
     * Mirrors {@code POST /api/internal/publication-support/showcase/render}
     * shape (htmlTemplate, cssTemplate, jsTemplate, items, pagination,
     * actionMappings).
     */
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> readInterfaceRender(WorkflowPublicationEntity pub,
                                                              String interfaceId,
                                                              int page,
                                                              int size,
                                                              Integer epoch) {
        Map<String, Object> snap = pub.getShowcaseSnapshot();
        if (snap == null) return Optional.empty();
        Object renders = snap.get("interfaceRenders");
        if (!(renders instanceof Map<?, ?> rMap)) return Optional.empty();
        Object entry = rMap.get(interfaceId);
        if (!(entry instanceof Map<?, ?> ifaceEntry)) return Optional.empty();

        // V273 - when the publisher pinned a specific epoch in the publish
        // wizard, every read of the default render is forced to that epoch.
        // The caller's explicit epoch param wins (publisher-side QA preview
        // can still browse), so we only substitute when epoch was null.
        boolean explicitEpoch = epoch != null;
        Integer effectiveEpoch = epoch;
        if (effectiveEpoch == null && pub.getShowcaseChosenEpoch() != null) {
            effectiveEpoch = pub.getShowcaseChosenEpoch();
        }
        Integer snapshotEpoch = resolveSnapshotEpoch(snap, effectiveEpoch);
        if (explicitEpoch && snapshotEpoch == null) {
            return Optional.empty();
        }

        Map<String, Object> source;
        if (snapshotEpoch != null) {
            Object byEpoch = ((Map<String, Object>) ifaceEntry).get("byEpoch");
            if (byEpoch instanceof Map<?, ?> beMap) {
                Object epochEntry = beMap.get(String.valueOf(snapshotEpoch));
                if (epochEntry instanceof Map<?, ?> epochMap) {
                    source = (Map<String, Object>) epochMap;
                } else if (epoch != null) {
                    // Caller asked for a specific epoch that isn't present -
                    // strict 404 path (preserves the legacy contract).
                    return Optional.empty();
                } else {
                    // Pinned epoch is missing from the snapshot (legacy or
                    // out-of-sync after a re-pin). Fall back to defaultRender
                    // so the marketplace card never blanks out on visitors.
                    Object def = ((Map<String, Object>) ifaceEntry).get("defaultRender");
                    if (!(def instanceof Map<?, ?> defMap)) return Optional.empty();
                    source = (Map<String, Object>) defMap;
                }
            } else if (epoch != null) {
                return Optional.empty();
            } else {
                Object def = ((Map<String, Object>) ifaceEntry).get("defaultRender");
                if (!(def instanceof Map<?, ?> defMap)) return Optional.empty();
                source = (Map<String, Object>) defMap;
            }
        } else {
            Object def = ((Map<String, Object>) ifaceEntry).get("defaultRender");
            if (!(def instanceof Map<?, ?> defMap)) return Optional.empty();
            source = (Map<String, Object>) defMap;
        }
        // Apply pagination over the captured items[] (capture stored up to 10
        // items; we slice further for the requested page/size).
        Object itemsObj = source.get("items");
        List<Map<String, Object>> allItems = (itemsObj instanceof List<?> l)
                ? (List<Map<String, Object>>) l
                : List.of();
        int from = Math.min(Math.max(0, page) * Math.max(1, size), allItems.size());
        int to = Math.min(from + Math.max(1, size), allItems.size());
        List<Map<String, Object>> sliced = allItems.subList(from, to);

        // Apply AI-generated image replacements (Wave 2b extended these to the
        // data layer). Sign each replacement key once, then swap it into BOTH:
        //   * the static HTML/CSS templates (an external URL the publisher
        //     hardcoded), and
        //   * the resolved items[].data - a raw scraped URL string OR a
        //     downloaded FileRef the publisher chose to replace.
        // The item-data swap runs BEFORE rewriteItems so a replaced FileRef
        // keeps its replacement URL instead of being signed to the original.
        Object htmlTemplate = source.get("htmlTemplate");
        Object cssTemplate = source.get("cssTemplate");
        Object replacementsRaw = snap.get("imageReplacements");
        if (replacementsRaw instanceof Map<?, ?> replacementsMap && !replacementsMap.isEmpty()) {
            Map<String, String> signedReplacements = fileRefRewriter.signReplacementUrls(
                    (Map<String, String>) replacementsMap, pub);
            if (!signedReplacements.isEmpty()) {
                if (htmlTemplate instanceof String html) {
                    for (Map.Entry<String, String> r : signedReplacements.entrySet()) {
                        html = html.replace(r.getKey(), r.getValue());
                    }
                    htmlTemplate = html;
                }
                if (cssTemplate instanceof String css) {
                    for (Map.Entry<String, String> r : signedReplacements.entrySet()) {
                        css = css.replace(r.getKey(), r.getValue());
                    }
                    cssTemplate = css;
                }
                sliced = applyDataReplacements(sliced, signedReplacements);
            }
        }

        // Rewrite any remaining FileRefs in items[*].data → HMAC-signed proxy
        // URLs so the anonymous marketplace render gets
        // <img src="/api/files/proxy-signed?…"> instead of a raw FileRef Map.
        // Scope is items[*].data only - never triggerData.
        sliced = fileRefRewriter.rewriteItems(sliced, pub);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("htmlTemplate", htmlTemplate);
        result.put("cssTemplate", cssTemplate);
        result.put("jsTemplate", source.get("jsTemplate"));
        result.put("actionMappings", source.get("actionMappings"));
        result.put("items", sliced);

        Map<String, Object> pagination = new LinkedHashMap<>();
        pagination.put("page", page);
        pagination.put("size", size);
        pagination.put("totalItems", allItems.size());
        pagination.put("totalPages", allItems.isEmpty() ? 0 : ((allItems.size() + size - 1) / Math.max(1, size)));
        result.put("pagination", pagination);
        return Optional.of(result);
    }

    private Integer resolveSnapshotEpoch(Map<String, Object> snapshot, Integer requestedEpoch) {
        if (requestedEpoch == null) {
            return null;
        }
        Object sourceEpoch = snapshot.get("sourceEpoch");
        Integer parsedSourceEpoch = parseEpoch(sourceEpoch);
        if (parsedSourceEpoch == null) {
            return requestedEpoch;
        }
        if (requestedEpoch == 1 || parsedSourceEpoch.equals(requestedEpoch)) {
            return 1;
        }
        return null;
    }

    private Integer parseEpoch(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private boolean epochExistsInRunState(Map<String, Object> runState, int epoch) {
        Object timestamps = runState.get("epochTimestamps");
        if (!(timestamps instanceof List<?> list) || list.isEmpty()) {
            return epoch == 0;
        }
        for (Object row : list) {
            if (row instanceof Map<?, ?> m) {
                Object ep = m.get("epoch");
                if (ep instanceof Number n && n.intValue() == epoch) return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> readSection(WorkflowPublicationEntity pub, String key) {
        Map<String, Object> snap = pub.getShowcaseSnapshot();
        if (snap == null) return Optional.empty();
        Object section = snap.get(key);
        if (section instanceof Map<?, ?> m && !m.isEmpty()) {
            return Optional.of((Map<String, Object>) m);
        }
        return Optional.empty();
    }

    // ===================== Wave 2b - item-data replacements =====================

    /**
     * Swap AI-screening replacements into {@code items[*].data}. Returns a new
     * list with rewritten data maps; the input is not mutated (the snapshot's
     * backing maps must stay intact for other readers / cache).
     *
     * <p>{@code signedReplacements} maps the original identifier (a raw URL or
     * a FileRef path the publisher saw at screening time) to a freshly signed
     * replacement proxy URL.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> applyDataReplacements(List<Map<String, Object>> items,
                                                                   Map<String, String> signedReplacements) {
        if (items == null || items.isEmpty() || signedReplacements == null || signedReplacements.isEmpty()) {
            return items;
        }
        List<Map<String, Object>> out = new ArrayList<>(items.size());
        for (Map<String, Object> item : items) {
            if (item == null) {
                out.add(null);
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(item);
            if (copy.get("data") != null) {
                copy.put("data", replaceInValue(copy.get("data"), signedReplacements));
            }
            out.add(copy);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object replaceInValue(Object value, Map<String, String> repl) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> m = (Map<String, Object>) map;
            if (isFileRef(m)) {
                String matchedKey = matchFileRef((String) m.get("path"), repl);
                if (matchedKey != null) {
                    // Replace the FileRef object with its signed replacement URL
                    // string; rewriteItems will then leave it untouched.
                    return repl.get(matchedKey);
                }
                return m; // no replacement → rewriteItems signs the original FileRef
            }
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                result.put(e.getKey(), replaceInValue(e.getValue(), repl));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(replaceInValue(item, repl));
            return out;
        }
        if (value instanceof String s) {
            // Raw URL strings (plain value, or embedded inside a JSON blob like
            // a js_template's postsJson) - string-replace each http(s) original.
            String result = s;
            for (Map.Entry<String, String> e : repl.entrySet()) {
                String key = e.getKey();
                if (key.startsWith("http") && result.contains(key)) {
                    result = result.replace(key, e.getValue());
                }
            }
            return result;
        }
        return value;
    }

    private static boolean isFileRef(Map<String, Object> m) {
        return "file".equals(m.get("_type"))
                && m.get("path") instanceof String s
                && !s.isEmpty();
    }

    /**
     * Find the replacement key matching a FileRef path. The path stored in the
     * snapshot has been re-namespaced into {@code _publications/{pubId}/…}
     * (with a {@code <hash>_} filename prefix), while the replacement key is
     * the path the publisher saw at screening time. Match on exact path, then
     * fall back to the original basename being a suffix of the namespaced
     * basename (the deterministic {@code <hash>_<originalName>} shape produced
     * by the snapshot file copy).
     */
    private static String matchFileRef(String fileRefPath, Map<String, String> repl) {
        if (fileRefPath == null || fileRefPath.isEmpty()) return null;
        if (repl.containsKey(fileRefPath)) return fileRefPath;
        String refBase = basename(fileRefPath);
        for (String key : repl.keySet()) {
            if (key.startsWith("http")) continue; // http keys are raw-URL replacements, not FileRefs
            String keyBase = basename(key);
            if (keyBase.isEmpty()) continue;
            if (refBase.equals(keyBase) || refBase.endsWith("_" + keyBase)) {
                return key;
            }
        }
        return null;
    }

    private static String basename(String path) {
        if (path == null) return "";
        int idx = path.lastIndexOf('/');
        return idx >= 0 && idx < path.length() - 1 ? path.substring(idx + 1) : path;
    }
}
