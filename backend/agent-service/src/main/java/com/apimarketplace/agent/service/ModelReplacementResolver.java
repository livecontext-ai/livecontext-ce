package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Swaps a model an admin DISABLED for the model that replaces it, at execution time (V515).
 *
 * <p>Disabling a model only removes it from the pickers. Every place that already stored it
 * (an agent, a workflow agent / classify / guardrail node, a sub-agent, a chat endpoint, a
 * compaction override) keeps sending it verbatim, so without this the day a model is turned
 * off every run on it fails: at the provider once the vendor retires it, or at a bridge access
 * check that {@link ModelCatalogService#resolveProvider} reroutes it into.
 *
 * <p>Every execution entry point calls {@link #substituteIfDisabled} FIRST, before provider
 * normalisation, the execution-link lookup, the budget guards and billing. The replacement
 * therefore becomes the BILLED identity of the run, and is itself resolved through the
 * execution links like any model a user picked. {@code ModelReplacementCallsiteInvariantTest}
 * fails the build on an execution-link caller that skips it.
 *
 * <p>Rules, in order:
 * <ol>
 *   <li>a pair that is not explicitly disabled ({@code enabled = false}, retired rows included)
 *       or deprecated is never touched. Deprecated counts because on a CE a bundle deprecates
 *       every model the cloud stopped shipping, and the cloud relay refuses those (V533).
 *       The mislabelled-row rule below reads the same set, so on a CE it can also match a
 *       deprecated row:
 *       a model merely absent from the catalog (bridge CLI unreachable, provider key not
 *       set, custom id) keeps running as before, so a transient catalog gap cannot move
 *       traffic. One exception, see {@link #mislabelledDisabledRow}: a pair that is neither
 *       listed nor linked, whose model id matches exactly one disabled row, is that row
 *       stored under the wrong provider;</li>
 *   <li>the admin's replacement, followed through replacements that are themselves
 *       disabled (cycle-safe), when it is runnable: listed by the catalog, or linked;</li>
 *   <li>otherwise the platform default model.</li>
 * </ol>
 *
 * <p>Execution-link TARGETS go through {@link #explicitReplacementIfDisabled} instead, which
 * applies rule 2 only: an admin commonly disables a CLI bridge row to hide it from users
 * while still routing a linked model onto it, so a disabled target with no replacement of its
 * own must keep running.
 *
 * <p>Fails open: any error reading the disabled set leaves the pair unchanged.
 *
 * <p>Known limits, deliberate for now:
 * <ul>
 *   <li>only a MODEL disabled globally is swapped. A whole provider switched off, or a model
 *       disabled for one category only (e.g. browser_agent), is not;</li>
 *   <li>"linked" means an enabled link on ANY surface. A replacement linked only for chat is
 *       accepted for a workflow node too, where no link applies and it runs on its own
 *       provider;</li>
 *   <li>the mislabel rule can move a turn during a catalog gap (a CLI briefly unreachable)
 *       when a disabled row under another provider carries the same model id: a silent model
 *       change, preferred over a failed run, and logged once per window;</li>
 *   <li>an admin edit reaches runs within this cache window, and chat within twice that
 *       (conversation-service caches the answer for the same time).</li>
 * </ul>
 */
@Service
public class ModelReplacementResolver {

    private static final Logger log = LoggerFactory.getLogger(ModelReplacementResolver.class);

    /** Pods refresh on their own clock; an admin edit reaches every pod within this window. */
    private static final long CACHE_TTL_MS = 15_000L;

    /** One WARN per substituted pair per window, so a split loop does not write one per item. */
    private static final long LOG_WINDOW_MS = 10 * 60_000L;

    /** After a failed read, how long the last snapshot is served before the DB is asked again. */
    private static final long FAILURE_BACKOFF_MS = 5_000L;

    /** Bound on the replacement chain, on top of the cycle check. */
    private static final int MAX_HOPS = 8;

    private final ModelConfigOverrideRepository repository;
    private final ModelCatalogService modelCatalogService;

    /** CLOUD-only bean: absent in the CE monolith, where no replacement can be link-only. */
    @Autowired(required = false)
    private ModelExecutionLinkService executionLinkService;

    private volatile Map<String, Pair> disabled = Map.of();
    private volatile long expiresAt = 0L;
    private final Map<String, Long> lastLogged = new ConcurrentHashMap<>();

    public ModelReplacementResolver(ModelConfigOverrideRepository repository,
                                    ModelCatalogService modelCatalogService) {
        this.repository = repository;
        this.modelCatalogService = modelCatalogService;
    }

    /** A {@code (provider, model)} pair. {@code null} halves mean "no replacement set". */
    public record Pair(String provider, String model) {
        boolean isSet() {
            return provider != null && !provider.isBlank() && model != null && !model.isBlank();
        }
    }

    /**
     * The pair a run must use instead of a disabled one.
     *
     * @param explicit true when it is the admin's replacement, false when it is the platform
     *                 default because no usable replacement was set
     */
    public record Substitution(String provider, String model,
                               String replacedProvider, String replacedModel,
                               boolean explicit) {}

    /**
     * The substitution for a pair a user or a stored configuration chose, or empty when the
     * pair is not disabled (or nothing usable can replace it).
     */
    public Optional<Substitution> substituteIfDisabled(String provider, String model) {
        if (isBlank(provider) || isBlank(model)) {
            return Optional.empty();
        }
        Map<String, Pair> snap = snapshot();
        Pair start = snap.get(key(provider, model));
        if (start == null) {
            start = mislabelledDisabledRow(snap, provider, model);
        }
        if (start == null) {
            return Optional.empty();
        }
        Pair explicit = followChain(snap, provider, model, start, true);
        if (explicit != null) {
            warnOnce(provider, model, explicit, true);
            return Optional.of(new Substitution(explicit.provider(), explicit.model(), provider, model, true));
        }
        Pair fallback = platformDefault(snap, provider, model);
        if (fallback == null) {
            warnOnce(provider, model, null, false);
            return Optional.empty();
        }
        warnOnce(provider, model, fallback, false);
        return Optional.of(new Substitution(fallback.provider(), fallback.model(), provider, model, false));
    }

    /**
     * The admin's replacement for an execution-link TARGET, or empty. No platform-default
     * fallback: see the class comment.
     */
    public Optional<Pair> explicitReplacementIfDisabled(String provider, String model) {
        if (isBlank(provider) || isBlank(model)) {
            return Optional.empty();
        }
        Map<String, Pair> snap = snapshot();
        Pair start = snap.get(key(provider, model));
        if (start == null) {
            return Optional.empty();
        }
        // A link target is not required to be in the user-facing catalog (that is the point
        // of a link), so the end of the chain is accepted as long as it is not disabled.
        return Optional.ofNullable(followChain(snap, provider, model, start, false));
    }

    /**
     * The disabled row a pair stored under the WRONG provider really means, or null.
     *
     * <p>A CLI model is routinely stored as {@code anthropic/claude-opus-4-7} (frontend
     * heuristic, LLM-authored plan) while the catalog row is {@code claude-code/claude-opus-4-7};
     * {@link ModelCatalogService#resolveProvider} repairs that for enabled models. Once the
     * real row is disabled that repair has nothing to point at, so the literal lookup misses
     * and the run would fail exactly as before. Same rule as the repair: only when the stored
     * pair is not itself served and exactly ONE disabled row carries the model id.
     *
     * <p>"Served" includes an execution link: a billed pair routed through a link is often
     * absent from the catalog (its own provider holds no key), and its link TARGET may be the
     * very CLI row the admin disabled to hide it from users. That run must keep running.
     */
    private Pair mislabelledDisabledRow(Map<String, Pair> snap, String provider, String model) {
        String suffix = "\u0000" + model.trim();
        Pair match = null;
        int matches = 0;
        for (Map.Entry<String, Pair> e : snap.entrySet()) {
            if (e.getKey().endsWith(suffix)) {
                match = e.getValue();
                matches++;
            }
        }
        if (matches != 1 || isRunnable(new Pair(provider.trim().toLowerCase(Locale.ROOT), model.trim()))) {
            return null;
        }
        return match;
    }

    /**
     * Walk the admin replacements from a disabled pair. Returns the first replacement that is
     * not itself disabled (and, when {@code requireRunnable}, runnable), or null.
     */
    private Pair followChain(Map<String, Pair> snap, String provider, String model, Pair start,
                             boolean requireRunnable) {
        Set<String> seen = new HashSet<>();
        seen.add(key(provider, model));
        Pair current = start;
        for (int hop = 0; hop < MAX_HOPS && current.isSet(); hop++) {
            String k = key(current.provider(), current.model());
            if (!seen.add(k)) {
                log.warn("Model replacement cycle through {}/{}; using the platform default",
                    current.provider(), current.model());
                return null;
            }
            Pair next = snap.get(k);
            if (next == null) {
                // Not disabled: the end of the chain.
                if (!requireRunnable || isRunnable(current)) {
                    return current;
                }
                log.warn("Replacement {}/{} of disabled {}/{} is not runnable here (not in the catalog, "
                        + "not linked); using the platform default",
                    current.provider(), current.model(), provider, model);
                return null;
            }
            current = next;
        }
        return null;
    }

    private boolean isRunnable(Pair pair) {
        if (modelCatalogService.isModelAvailable(pair.provider(), pair.model())) {
            return true;
        }
        try {
            return executionLinkService != null && executionLinkService.isLinked(pair.provider(), pair.model());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Pair platformDefault(Map<String, Pair> snap, String provider, String model) {
        String p;
        String m;
        try {
            p = modelCatalogService.getEffectiveDefaultProvider();
            m = modelCatalogService.getEffectiveDefaultModel();
        } catch (RuntimeException e) {
            log.warn("Platform default model unavailable while replacing {}/{}: {}", provider, model, e.toString());
            return null;
        }
        if (isBlank(p) || isBlank(m) || snap.containsKey(key(p, m))) {
            return null;
        }
        return new Pair(p, m);
    }

    private Map<String, Pair> snapshot() {
        long now = System.currentTimeMillis();
        if (now < expiresAt) {
            return disabled;
        }
        try {
            Map<String, Pair> rebuilt = new HashMap<>();
            for (ModelConfigOverrideEntity row : repository.findDisabledOrDeprecated()) {
                rebuilt.put(key(row.getProvider(), row.getModelId()),
                    new Pair(row.getReplacementProvider(), row.getReplacementModel()));
            }
            disabled = Map.copyOf(rebuilt);
            expiresAt = now + CACHE_TTL_MS;
        } catch (RuntimeException e) {
            // Keep serving the last snapshot (empty on a cold start = no substitution, the
            // pre-V515 behaviour). A DB blip must never be what fails a run. Backed off so an
            // outage does not turn every run into one more failing query and one more WARN.
            expiresAt = now + FAILURE_BACKOFF_MS;
            log.warn("Could not read disabled models; model replacement skipped: {}", e.toString());
        }
        return disabled;
    }

    private void warnOnce(String provider, String model, Pair target, boolean explicit) {
        long now = System.currentTimeMillis();
        String k = key(provider, model);
        Long last = lastLogged.get(k);
        if (last != null && now - last < LOG_WINDOW_MS) {
            return;
        }
        lastLogged.put(k, now);
        if (target == null) {
            log.warn("Model {}/{} is disabled and nothing can replace it (no runnable replacement, "
                + "no platform default); running it as stored", provider, model);
        } else {
            log.warn("Model {}/{} is disabled; running on {} {}/{}", provider, model,
                explicit ? "its replacement" : "the platform default", target.provider(), target.model());
        }
    }

    private static String key(String provider, String model) {
        return provider.trim().toLowerCase(Locale.ROOT) + "\u0000" + model.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
