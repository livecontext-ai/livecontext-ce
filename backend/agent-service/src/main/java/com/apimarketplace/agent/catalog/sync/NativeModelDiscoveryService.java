package com.apimarketplace.agent.catalog.sync;

import com.apimarketplace.agent.cloud.CloudRelaySupport;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.provider.LLMProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Third catalog source: asks each configured provider WHICH MODELS IT SERVES,
 * through its own OpenAI-compatible {@code GET <base>/models} endpoint, and
 * emits rows for the ids no feed knows about.
 *
 * <p><b>Why this exists.</b> Both feeds are third-party mirrors, and they lag
 * per vendor. Measured against the live feeds on 2026-08-19: LiteLLM's
 * {@code zai} block ended at glm-5.1 while Z.AI was already serving glm-5.2,
 * glm-5.3 (released 5 days earlier) and glm-5v-turbo; its {@code moonshot}
 * block ended at kimi-k2.6 while Kimi K3 had shipped a month before; MiniMax
 * M2.7 and DeepSeek V4 Pro were missing the same way. No amount of syncing
 * produces a model the source does not publish, so a mirror-only catalog is
 * permanently behind for exactly the vendors that ship fastest.
 *
 * <p><b>Authority split - the rule that keeps this safe.</b>
 * <ul>
 *   <li>The vendor endpoint is authoritative for EXISTENCE. If Z.AI's own API
 *       lists {@code glm-5.3}, the model is real and callable on the endpoint
 *       we have configured for that provider. This is strictly better than
 *       inferring existence from an aggregator's catalogue, which also lists
 *       third-party re-hosts the vendor's own API would 404 on.</li>
 *   <li>OpenRouter is the PRICE source. The vendor {@code /models} endpoint
 *       publishes no rates, so the row is priced from OpenRouter's entry for
 *       the same vendor id, exactly like a feed row: one pricing pathway, and
 *       no field the rest of the catalog does not already use.
 *       <p>Known and accepted consequence: OpenRouter quotes a RESALE rate,
 *       which is not always the vendor's direct one. Measured 2026-08-19,
 *       glm-5.3 (1.40/4.40), glm-5v-turbo (1.20/4.00) and minimax-m2.7
 *       (0.30/1.20) match the vendor list price exactly, while glm-5.1 and
 *       glm-5.2 carry a uniform 31% discount (0.966/3.036 against 1.40/4.40
 *       direct). A direct call on such a row is billed under the vendor rate
 *       until an admin edits it, which the review gate keeps a deliberate
 *       step since a feed insert lands disabled.</li>
 *   <li>Capabilities come from that same donor row (tools / vision /
 *       reasoning / context window).</li>
 *   <li>A model OpenRouter does NOT carry lands with no price at all, and the
 *       unpriced-enable guard in {@code ModelCatalogService} keeps it out of
 *       the picker until an admin prices it. Unpriced is not free:
 *       {@code ModelPricingService} would otherwise bill it at the platform
 *       default rate.</li>
 * </ul>
 *
 * <p><b>Auditing where a price came from.</b> Every discovered row stamps
 * {@code priceFrom} and the exact {@code openRouterId} it was taken from into
 * {@code feed_metadata}, so a rate can be traced back to a specific aggregator
 * entry long after the sync ran. The id match is exact and namespace-mapped
 * ({@code zai} to {@code z-ai}); no fuzzy matching can attach one model's
 * price to another.
 *
 * <p><b>Gap-fill only.</b> A row already carried by LiteLLM (or already in the
 * catalog) is never emitted: the feeds carry real prices and richer metadata,
 * so they stay in charge of everything they cover. Discovery only speaks for
 * what they miss.
 *
 * <p><b>Who gets asked: the capability, not the Java class.</b> Every
 * relay-executable provider is asked, and answering is decided by
 * {@link LLMProvider#listRemoteModelIds()}. This used to be gated on
 * {@code instanceof OpenAICompatibleProvider}, which silently excluded every
 * vendor that speaks the same dialect while having its own provider class:
 * DeepSeek shipped {@code deepseek-flash} and no refresh could ever surface it,
 * with no skip entry and no warning to say the vendor had never been asked.
 * A vendor that cannot answer now reports itself in {@code skippedProviders},
 * so "found nothing" and "never asked" stop looking the same.
 *
 * <p><b>Chat models only.</b> A vendor listing states no mode and mixes
 * embedding, speech and image endpoints in with chat models. The LiteLLM feed
 * declares a mode for most known ids, including the ones it rejects, so those
 * declarations filter the listing: no {@code text-embedding-3-large} or
 * {@code dall-e-3} enters the catalog as a chat row. An id NO source knows
 * about is still emitted - unpriced, disabled, visible to an admin who can
 * delete it, and never callable in the meantime.
 *
 * <p><b>Fails soft, per provider.</b> An unconfigured provider is skipped
 * (no key, nothing to ask). A provider whose endpoint errors is skipped with a
 * WARN. Neither can fail the enclosing sync - a catalog refresh must not break
 * because one vendor's status page is having a bad day.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NativeModelDiscoveryService {

    /**
     * OpenRouter namespaces the same vendor's models under its own slug, which
     * does not always match our provider name. Used ONLY to find the
     * donor row (price AND capabilities) for an id discovered from the vendor -
     * never to decide that a model exists.
     */
    static final Map<String, String> OPENROUTER_VENDOR_NAMESPACE = Map.ofEntries(
            Map.entry("zai",        "z-ai"),
            Map.entry("moonshot",   "moonshotai"),
            Map.entry("qwen",       "qwen"),
            Map.entry("minimax",    "minimax"),
            Map.entry("deepseek",   "deepseek"),
            Map.entry("xai",        "x-ai"),
            Map.entry("mistral",    "mistralai"),
            // Added when discovery stopped being gated on the provider's Java
            // class: these vendors all expose a model listing, so their rows
            // need a donor namespace too.
            Map.entry("openai",     "openai"),
            Map.entry("anthropic",  "anthropic"),
            Map.entry("google",     "google"),
            Map.entry("perplexity", "perplexity"),
            Map.entry("cohere",     "cohere")
    );

    /**
     * Providers this pass must never probe, even though they are
     * relay-executable.
     *
     * <p>OpenRouter is an AGGREGATOR, and its {@code /models} listing is not a
     * third source: it is the very feed {@link OpenRouterFeedParser} already
     * fetches and filters. Probing it here would re-import the same catalogue
     * through a path with none of that parser's filters, re-admitting the
     * {@code :free} / {@code :nitro} duplicate variants it drops and every
     * unpriced row it rejects. Discovery exists to ask VENDORS what they
     * serve; for the aggregator the question is already answered upstream.
     */
    static final Set<String> AGGREGATOR_PROVIDERS = Set.of("openrouter");

    /**
     * Default ceiling on one whole discovery pass. Per-provider timeouts
     * (5s connect / 10s read) bound a single vendor; this bounds the sum,
     * because the pass runs inside the sync transaction and every vendor
     * added to it lengthens the sequential worst case.
     *
     * <p>Checked BEFORE each provider, so the true ceiling is this plus one
     * provider's own timeout. Bounding mid-call would mean abandoning an
     * open connection, which buys nothing here.
     */
    static final long DEFAULT_DISCOVERY_BUDGET_NANOS =
            java.util.concurrent.TimeUnit.SECONDS.toNanos(60);

    /**
     * Effective budget. Package-private and non-final purely so a test can
     * exercise the exhaustion branch without sleeping: a constant read
     * straight from {@code System.nanoTime()} leaves that branch untestable,
     * and an untestable branch in a path that decides which vendors get asked
     * is not one to ship blind.
     */
    long discoveryBudgetNanos = DEFAULT_DISCOVERY_BUDGET_NANOS;

    /**
     * Providers the previous pass ran out of budget before reaching. They go
     * FIRST next time.
     *
     * <p>Without this the order is alphabetical and exhaustion always drops
     * the same tail (perplexity, qwen, xai, zai) - which is precisely the set
     * this feature exists for, since Z.AI and Qwen are the vendors whose
     * models the mirrors lag on. A starved tail that stays starved would aim
     * the budget at the feature's own purpose.
     *
     * <p>This bean is a singleton and two admins can trigger a sync at the
     * same time, so the field is {@code volatile} and REPLACED wholesale
     * rather than mutated in place. A reader then sees either the previous
     * pass's debt or this one's, never a half-built collection. Losing one
     * pass's debt to a race costs a re-ordering, which the next pass fixes;
     * iterating a set while another thread clears it would not be so kind.
     */
    volatile List<String> owedFromLastPass = List.of();

    private final LLMProviderFactory providerFactory;

    /**
     * What one discovery pass found, for the sync log + admin UI.
     *
     * @param skippedProviders providers that WERE asked and could not answer:
     *                         no usable key, or the endpoint errored.
     * @param notAskedProviders providers the pass never got to, because the
     *                         time budget ran out first. Deliberately a
     *                         separate field: folding it into
     *                         {@code skippedProviders} would report a vendor
     *                         as having failed when nobody ever called it,
     *                         which is the exact confusion this class was
     *                         changed to eliminate.
     */
    public record DiscoveryResult(List<Map<String, Object>> models,
                                  Map<String, Integer> discoveredByProvider,
                                  List<String> skippedProviders,
                                  List<String> notAskedProviders) {
        public static DiscoveryResult empty() {
            return new DiscoveryResult(List.of(), Map.of(), List.of(), List.of());
        }
        public int total() {
            return models.size();
        }
    }

    /**
     * Run one discovery pass.
     *
     * @param feedModels      every row the two feeds accepted this run, in the
     *                        parsers' canonical shape. Used to suppress ids a
     *                        feed already covers.
     * @param existingKeys    {@code provider + '\0' + modelId} for every row
     *                        already in {@code model_config_overrides}, so a
     *                        model an admin has already curated is left alone.
     * @param openRouterRows  the parsed OpenRouter rows, used as the price and
     *                        capability donors for the ids they carry.
     * @param declinedIds  the ids the LiteLLM feed SAW and refused to publish
     *                     (wrong mode, no tool-calling, no real price), keyed
     *                     by {@link #key}. A vendor listing states none of
     *                     those three things, so without this a model the feed
     *                     already rejected walks back in through the discovery
     *                     door. Every entry is a policy decision taken
     *                     elsewhere; re-emitting one here would contradict it
     *                     silently. An id NO source knows about is still
     *                     discovered - it lands unpriced and disabled, visible
     *                     to an admin, never callable.
     */
    public DiscoveryResult discover(List<Map<String, Object>> feedModels,
                                    Set<String> existingKeys,
                                    List<Map<String, Object>> openRouterRows,
                                    Set<String> declinedIds) {
        Set<String> declined = declinedIds == null ? Set.of() : declinedIds;
        Set<String> covered = new HashSet<>(existingKeys == null ? Set.of() : existingKeys);
        if (feedModels != null) {
            for (Map<String, Object> m : feedModels) {
                String p = strOf(m.get("provider"));
                String id = strOf(m.get("modelId"));
                if (p != null && id != null) covered.add(key(p, id));
            }
        }

        Map<String, Map<String, Object>> donorsByVendorId = indexOpenRouterDonors(openRouterRows);

        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Integer> perProvider = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();

        List<String> notAsked = new ArrayList<>();
        long deadline = System.nanoTime() + discoveryBudgetNanos;

        for (String providerName : probeOrder()) {
            // Per-call timeouts bound one vendor; this bounds the PASS, which
            // runs inside the sync transaction. "Not asked" is kept OUT of the
            // skip list on purpose: skipped means asked and unable to answer,
            // and collapsing the two would rebuild exactly the ambiguity this
            // class was changed to remove.
            if (System.nanoTime() > deadline) {
                notAsked.add(providerName);
                continue;
            }
            LLMProvider provider = providerFactory.findProvider(providerName).orElse(null);
            if (provider == null) {
                skipped.add(providerName);
                continue;
            }
            // Ask the capability, never the Java class. Gating on
            // `instanceof OpenAICompatibleProvider` excluded every vendor that
            // has its own provider class while speaking the same dialect
            // (DeepSeek, Mistral, OpenAI), and it did so SILENTLY - no skip
            // entry, no warning, so "discovery found nothing for deepseek" and
            // "discovery never asked deepseek" looked identical from outside.
            Optional<List<String>> listed = provider.listRemoteModelIds();
            if (listed.isEmpty()) {
                skipped.add(providerName);
                continue;
            }

            int added = 0;
            for (String modelId : listed.get()) {
                if (modelId == null || modelId.isBlank()) continue;
                String id = modelId.trim();
                if (!isPublishableVendorId(id)) continue;
                if (covered.contains(key(providerName, id))) continue;
                if (declined.contains(key(providerName, id))) continue;
                covered.add(key(providerName, id));  // a vendor listing the same id twice
                out.add(buildRow(providerName, id, donorsByVendorId));
                added++;
            }
            if (added > 0) perProvider.put(providerName, added);
        }

        // Owe the unasked ones a turn at the front of the next pass, so the
        // alphabetical tail cannot starve run after run. One assignment of an
        // immutable list, never a clear-then-fill another thread could observe
        // mid-way.
        owedFromLastPass = List.copyOf(notAsked);

        log.info("Native discovery: {} new model(s) across {} provider(s); skipped (asked, could not answer): {}",
                out.size(), perProvider.size(), skipped);
        if (!notAsked.isEmpty()) {
            // One line, after the loop, counting what the loop actually left
            // behind. Emitted per provider from inside the loop, this said a
            // different and mostly wrong number each time.
            log.warn("Native discovery: {}s budget spent, {} provider(s) not asked this run ({}); they go first next run",
                    discoveryBudgetNanos / 1_000_000_000L, notAsked.size(), notAsked);
        }

        return new DiscoveryResult(out, perProvider, skipped, List.copyOf(notAsked));
    }

    /**
     * Index OpenRouter rows by {@code <ourProvider>\0<bareId>} so a discovered
     * id can find the donor that supplies its price. OpenRouter ids are
     * {@code <vendor>/<id>}, sometimes with a {@code :variant} suffix; the
     * suffixed variants are dropped by the parser already, so a plain split is
     * enough.
     */
    private static Map<String, Map<String, Object>> indexOpenRouterDonors(
            List<Map<String, Object>> openRouterRows) {
        if (openRouterRows == null || openRouterRows.isEmpty()) return Map.of();

        Map<String, String> namespaceToProvider = new HashMap<>();
        OPENROUTER_VENDOR_NAMESPACE.forEach((ours, ns) -> namespaceToProvider.put(ns, ours));

        Map<String, Map<String, Object>> donors = new HashMap<>();
        for (Map<String, Object> row : openRouterRows) {
            String fullId = strOf(row.get("modelId"));
            if (fullId == null) continue;
            int slash = fullId.indexOf('/');
            if (slash <= 0 || slash == fullId.length() - 1) continue;
            String ourProvider = namespaceToProvider.get(fullId.substring(0, slash));
            if (ourProvider == null) continue;
            donors.putIfAbsent(key(ourProvider, fullId.substring(slash + 1)), row);
        }
        return donors;
    }

    /**
     * Build the canonical merge row for a discovered model. Deliberately
     * price-free: {@code priceInput} / {@code priceOutput} stay absent so the
     * row lands unpriced and un-enableable rather than carrying a number
     * nobody verified.
     */
    private static Map<String, Object> buildRow(String provider, String modelId,
                                                Map<String, Map<String, Object>> donors) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("provider", provider);
        row.put("modelId", modelId);
        row.put("displayName", modelId);
        row.put("source", "discovery");
        row.put("mode", "chat");

        Map<String, Object> donor = donors.get(key(provider, modelId));
        if (donor != null) {
            // Pricing, taken verbatim from the donor - same fields the
            // OpenRouter parser fills on its own rows, so a discovered row is
            // indistinguishable from a feed row downstream.
            copyIfPresent(donor, row, "priceInput");
            copyIfPresent(donor, row, "priceOutput");
            copyIfPresent(donor, row, "priceFloorInput");
            copyIfPresent(donor, row, "priceFloorOutput");
            copyIfPresent(donor, row, "priceCacheRead");
            copyIfPresent(donor, row, "priceCacheWrite");
            copyIfPresent(donor, row, "supportsPromptCaching");
            // tier is a deterministic function of the output price, so it must
            // travel with it or the row lands in the wrong bucket.
            copyIfPresent(donor, row, "tier");

            copyIfPresent(donor, row, "contextWindow");
            copyIfPresent(donor, row, "maxOutputTokens");
            copyIfPresent(donor, row, "supportsTools");
            copyIfPresent(donor, row, "supportsVision");
            copyIfPresent(donor, row, "supportsReasoning");
            copyIfPresent(donor, row, "supportsResponseSchema");
            copyIfPresent(donor, row, "supportedModalities");
            copyIfPresent(donor, row, "supportedOutputModalities");
        }

        // Provenance. This is what makes "is that really OpenRouter's price?"
        // an answerable question months later, per row, without re-deriving
        // anything: the donor's exact aggregator id is recorded next to the
        // rate it supplied.
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("source", "provider-models-endpoint");
        meta.put("provider", provider);
        if (donor != null) {
            meta.put("priceFrom", "openrouter");
            meta.put("openRouterId", donor.get("modelId"));
            meta.put("capabilitiesFrom", "openrouter");
        } else {
            meta.put("priceFrom", "none");
            meta.put("capabilitiesFrom", "none");
            meta.put("pricing", "unpriced - OpenRouter carries no entry for this vendor id");
        }
        row.put("feedMetadata", meta);

        return row;
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String field) {
        Object v = from.get(field);
        if (v != null) to.put(field, v);
    }

    /**
     * Two id shapes a vendor listing returns that must never become catalog
     * rows, and that no feed can tell us about because the feed has never
     * seen them.
     *
     * <p>{@code ft:} is a TENANT'S OWN fine-tune. OpenAI's listing returns the
     * calling organisation's fine-tunes ({@code ft:gpt-4o-mini:acme::abc}) next
     * to the base models. They are real and callable, but they belong to one
     * tenant's account, so publishing one into a shared catalog advertises a
     * model every other tenant would 404 on. The feed takes the same position
     * on its {@code ft:} pricing templates; adding a fine-tune stays the
     * deliberate {@code is_custom=true} path.
     *
     * <p>A slash marks an aggregator-namespaced id. Every provider reaching
     * this method is a native vendor (the one aggregator is excluded before
     * the call, see {@link #AGGREGATOR_PROVIDERS}), and for a native vendor a
     * slashed id would collide with the row identity contract that separates
     * a native row from an aggregator one.
     */
    static boolean isPublishableVendorId(String modelId) {
        return !modelId.startsWith("ft:") && !modelId.contains("/");
    }

    /** Native vendors, alphabetical, so logs and tests are deterministic. */
    static List<String> sortedRelayProviders() {
        List<String> names = new ArrayList<>(CloudRelaySupport.supportedProviders());
        names.removeAll(AGGREGATOR_PROVIDERS);
        Collections.sort(names);
        return names;
    }

    /**
     * Alphabetical, except anything the previous pass ran out of budget before
     * reaching comes first. Still fully deterministic for a given prior state.
     */
    List<String> probeOrder() {
        // Read the volatile once: a concurrent pass could replace it midway
        // through, and an order built from two different debts is nobody's.
        List<String> owed = owedFromLastPass;
        List<String> all = sortedRelayProviders();
        if (owed.isEmpty()) {
            return all;
        }
        List<String> ordered = new ArrayList<>(all.size());
        for (String name : owed) {
            if (all.contains(name) && !ordered.contains(name)) ordered.add(name);
        }
        for (String name : all) {
            if (!ordered.contains(name)) ordered.add(name);
        }
        return ordered;
    }

    /**
     * The {@code existingKeys} key format, shared with
     * {@link ModelCatalogSyncService} so caller and callee cannot drift on the
     * separator. Package-private rather than private so tests build the key
     * through it instead of hard-coding the delimiter.
     */
    static String key(String provider, String modelId) {
        return provider + '\0' + modelId;
    }

    private static String strOf(Object v) {
        return v == null ? null : v.toString();
    }
}
