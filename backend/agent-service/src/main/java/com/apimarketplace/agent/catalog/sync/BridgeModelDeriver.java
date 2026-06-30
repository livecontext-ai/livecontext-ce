package com.apimarketplace.agent.catalog.sync;

import com.apimarketplace.agent.bridge.BridgeAllowlist;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Produces catalog rows for the 4 local-CLI bridges from the already-parsed
 * LiteLLM cloud entries. Two layers, unioned:
 *
 * <ol>
 *   <li><b>Curated floor</b> - every {@code (bridge, modelId)} pair in
 *       {@link BridgeAllowlist#MODELS}, enriched from the underlying cloud
 *       model (with the alias lookup for ids that don't match a feed id
 *       verbatim, e.g. mistral-vibe and gpt-5.3-codex).</li>
 *   <li><b>Pattern auto-discovery</b> - any parsed cloud model under a bridge's
 *       cloud provider whose id matches {@link BridgeAllowlist#DISCOVERY_PATTERNS}
 *       for that bridge, using the id verbatim. This is what makes a fresh
 *       same-family model (e.g. {@code claude-opus-4-8}) appear under
 *       {@code claude-code} on the next sync without a code change, exactly like
 *       a direct-API provider. mistral-vibe has no pattern, so it never gains a
 *       model this way.</li>
 * </ol>
 *
 * <p><b>Authority split</b>:
 * <ul>
 *   <li>{@link BridgeAllowlist} is the AUTHORITY for which model ids each
 *       bridge routes (explicit list + tight patterns). Nothing here invents
 *       an id that neither the list nor a pattern authorizes.</li>
 *   <li>This deriver copies pricing + context + capabilities from the
 *       underlying cloud model. So {@code claude-code / claude-opus-4-7}
 *       gets whatever price LiteLLM's {@code anthropic / claude-opus-4-7}
 *       currently carries.</li>
 * </ul>
 *
 * <p><b>Why not rate=0?</b> An earlier design zeroed all bridge prices on
 * the assumption that the CLI subscription makes per-token cost irrelevant.
 * In practice we still need the price for: (a) credit accounting UI so
 * admins see the "would-be" cost, (b) future markup support, (c) parity
 * across cloud/bridge variants in reporting. The auth-side pricing column
 * carries the underlying price; tenant-level credit debit behaviour is
 * a separate business-rule concern (see V117 docblock).
 *
 * <p><b>Bridge to cloud provider map</b>:
 * <pre>
 *   claude-code   to anthropic
 *   codex         to openai
 *   gemini-cli    to google   (LiteLLM litellm_provider=gemini)
 *   mistral-vibe  to mistral  (using LITELLM_LOOKUP_ALIAS for id translation)
 * </pre>
 *
 * <p>If the underlying cloud row is missing from LiteLLM (e.g. model
 * dropped from the feed), the bridge row is SKIPPED for the curated floor and
 * simply never matched for discovery. The deriver never fabricates prices.
 */
@Slf4j
@Component
public class BridgeModelDeriver {

    /** Maps each bridge provider to its underlying cloud provider in LiteLLM. */
    static final Map<String, String> BRIDGE_TO_CLOUD = Map.of(
            "claude-code",  "anthropic",
            "codex",        "openai",
            "gemini-cli",   "google",
            "mistral-vibe", "mistral"
    );

    /**
     * Derive bridge rows from a parsed LiteLLM model list. Each returned
     * map has the same shape as the parser outputs (compatible with
     * {@link com.apimarketplace.agent.catalog.bundle.CatalogMergeService#merge})
     * with {@code provider=<bridge>}, {@code modelId=<allowlist/discovered id>},
     * {@code providerKind='bridge'}, {@code source='litellm'},
     * and pricing/metadata copied from the underlying cloud row.
     */
    public List<Map<String, Object>> derive(List<Map<String, Object>> liteLlmCloudModels) {
        if (liteLlmCloudModels == null || liteLlmCloudModels.isEmpty()) {
            log.info("BridgeModelDeriver: empty LiteLLM input, no bridge rows derived");
            return List.of();
        }

        // Index cloud models by "provider:modelId" for O(1) lookup. We key
        // on the OUR provider name (anthropic/openai/google/mistral) so
        // the map builds naturally from what parsers emit.
        Map<String, Map<String, Object>> cloudIndex = new HashMap<>();
        for (Map<String, Object> cloud : liteLlmCloudModels) {
            String p = strOf(cloud.get("provider"));
            String id = strOf(cloud.get("modelId"));
            if (p == null || id == null) continue;
            cloudIndex.put(p + ":" + id, cloud);
        }

        // Preserve insertion order: curated-floor rows first (stable, matches
        // V128 ordering), then any pattern-discovered extras. Keyed by
        // bridge:modelId so a seed id that also matches its pattern is never
        // emitted twice.
        Map<String, Map<String, Object>> derivedByKey = new LinkedHashMap<>();
        int seedDerived = 0, patternDerived = 0, missingUnderlying = 0;

        // Pass 1 -- curated floor (BridgeAllowlist.MODELS). Unchanged behaviour,
        // including the alias lookup for ids that don't match a feed id verbatim
        // (mistral-vibe config aliases; gpt-5.3-codex is mode=responses).
        for (var entry : BridgeAllowlist.MODELS.entrySet()) {
            String bridge = entry.getKey();
            String cloudProvider = BRIDGE_TO_CLOUD.get(bridge);
            if (cloudProvider == null) continue;

            for (String bridgeModelId : entry.getValue()) {
                String lookupId = BridgeAllowlist.LITELLM_LOOKUP_ALIAS
                        .getOrDefault(bridgeModelId, bridgeModelId);
                Map<String, Object> cloud = cloudIndex.get(cloudProvider + ":" + lookupId);

                if (cloud == null) {
                    log.debug("BridgeModelDeriver: no LiteLLM entry for {}/{} (looked up as {}/{}), bridge row skipped",
                            bridge, bridgeModelId, cloudProvider, lookupId);
                    missingUnderlying++;
                    continue;
                }
                derivedByKey.put(bridge + ":" + bridgeModelId,
                        buildBridgeRow(cloud, bridge, bridgeModelId));
                seedDerived++;
            }
        }

        // Pass 2 -- version-flexible auto-discovery (BridgeAllowlist.DISCOVERY_PATTERNS).
        // For each pattern bridge, derive any parsed cloud model under its cloud
        // provider whose id matches the pattern, using the id verbatim. Ids
        // already produced by pass 1 are skipped. mistral-vibe has no pattern,
        // so it never enters this loop.
        for (var entry : BridgeAllowlist.DISCOVERY_PATTERNS.entrySet()) {
            String bridge = entry.getKey();
            String cloudProvider = BRIDGE_TO_CLOUD.get(bridge);
            if (cloudProvider == null) continue;

            for (Map<String, Object> cloud : liteLlmCloudModels) {
                String p = strOf(cloud.get("provider"));
                String id = strOf(cloud.get("modelId"));
                if (id == null || !cloudProvider.equals(p)) continue;
                if (!BridgeAllowlist.matchesDiscoveryPattern(bridge, id)) continue;
                String key = bridge + ":" + id;
                if (derivedByKey.containsKey(key)) continue; // already a curated-floor row
                derivedByKey.put(key, buildBridgeRow(cloud, bridge, id));
                patternDerived++;
            }
        }

        log.info("BridgeModelDeriver: derived {} bridge rows (seed={}, pattern={}, missingUnderlying={})",
                derivedByKey.size(), seedDerived, patternDerived, missingUnderlying);
        return new ArrayList<>(derivedByKey.values());
    }

    /**
     * Build one bridge catalog row from an underlying cloud model map. Inherits
     * all cloud fields (pricing, context, capabilities), then stamps the bridge
     * identity: {@code provider=<bridge>}, {@code modelId=<bridgeId>},
     * {@code providerKind='bridge'}, {@code source='litellm'} (the only
     * non-bundle value the model_config_overrides source CHECK accepts at sync
     * time; a descriptive "litellm-derived" previously tripped the CHECK), and a
     * pretty display name (see {@link #prettifyDisplayName} -- exact for
     * claude-code, approximate for codex/gemini per a pre-existing prettifier
     * quirk that this change does not alter).
     */
    private static Map<String, Object> buildBridgeRow(Map<String, Object> cloud,
                                                      String bridge, String bridgeModelId) {
        Map<String, Object> bridgeRow = new LinkedHashMap<>(cloud);
        bridgeRow.put("provider", bridge);
        bridgeRow.put("modelId", bridgeModelId);
        bridgeRow.put("providerKind", "bridge");
        bridgeRow.put("source", "litellm");
        bridgeRow.put("displayName", prettifyDisplayName(bridge, bridgeModelId));
        return bridgeRow;
    }

    /**
     * Pretty display name. Matches the convention used by V128 seed so
     * an admin syncing live doesn't see the name flip.
     */
    static String prettifyDisplayName(String bridge, String modelId) {
        return switch (bridge) {
            case "claude-code" -> prettyClaude(modelId);
            case "codex"        -> "GPT " + modelId.replaceFirst("^gpt-", "");
            case "gemini-cli"   -> prettyGemini(modelId);
            case "mistral-vibe" -> prettyMistral(modelId);
            default -> modelId;
        };
    }

    private static String prettyClaude(String id) {
        // claude-opus-4-7 -> "Claude Opus 4.7"
        String s = id.replace("claude-", "").replace("-", " ");
        return capitalize("Claude " + s)
                .replaceAll("(\\d) (\\d)", "$1.$2");
    }

    private static String prettyGemini(String id) {
        String s = id.replace("-preview", " Preview")
                .replace("gemini-", "Gemini ");
        return s.replaceAll("(\\d)-", "$1.")  // "3-1" -> "3.1"
                .replaceAll("\\bflash\\b", "Flash")
                .replaceAll("\\bpro\\b", "Pro");
    }

    private static String prettyMistral(String id) {
        // devstral-2 -> Devstral 2 ; devstral-small-2 -> Devstral Small 2
        return capitalize(id.replace("-", " "));
    }

    private static String capitalize(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean cap = true;
        for (char c : s.toCharArray()) {
            if (cap && Character.isLetter(c)) {
                out.append(Character.toUpperCase(c));
                cap = false;
            } else if (c == ' ') {
                out.append(c);
                cap = true;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String strOf(Object v) { return v == null ? null : v.toString(); }
}
