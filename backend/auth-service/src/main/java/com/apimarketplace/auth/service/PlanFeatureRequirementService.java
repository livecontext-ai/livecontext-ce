package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.PlanFeatureRequirement;
import com.apimarketplace.auth.repository.PlanFeatureRequirementRepository;
import com.apimarketplace.common.plan.PlanTier;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads and edits the "which plan does this node need?" table.
 *
 * <p>The map is read on a hot path (every gated node of every workflow run, plus
 * every builder page load), so it is served from a single 60 s cache of the WHOLE
 * table rather than a query per key. The table holds only the exceptions, so it
 * is small by construction, and an edit invalidates the cache immediately - the
 * 60 s only bounds staleness across service instances.
 *
 * <p><b>Setting a feature back to FREE deletes its row.</b> A stored {@code FREE}
 * and a missing row mean the same thing, and keeping both spellings would let the
 * table grow to catalogue size while saying nothing.
 */
@Service
public class PlanFeatureRequirementService {

    private static final Logger log = LoggerFactory.getLogger(PlanFeatureRequirementService.class);

    /** Accepted key shapes. Anything else is rejected at write time. */
    private static final Pattern KEY_PATTERN =
            Pattern.compile("^(node|api|tool|feature):[a-z0-9][a-z0-9._/-]{0,180}$");

    private static final String CACHE_KEY = "all";

    private final PlanFeatureRequirementRepository repository;

    private final Cache<String, Map<String, String>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .maximumSize(1)
            .build();

    public PlanFeatureRequirementService(PlanFeatureRequirementRepository repository) {
        this.repository = repository;
    }

    /**
     * Every requirement, as {@code featureKey -> minPlan}. Never null; empty when
     * nothing is gated.
     */
    public Map<String, String> requirements() {
        return cache.get(CACHE_KEY, k -> load());
    }

    /**
     * Whether {@code planCode} clears the bar stored for {@code featureKey}.
     *
     * <p>No row, or a row this build cannot read as a plan, means every plan may use it: the
     * table holds only the exceptions, and the same contract is what auth-client's
     * {@code PlanFeatureGate} applies to the map it fetches from here. Callers that must not
     * diverge from a run's own verdict ask this rather than re-deriving it from
     * {@link #requirements()}.
     */
    public boolean allows(String planCode, String featureKey) {
        return PlanTier.meets(planCode, requirements().get(featureKey));
    }

    /** Full rows for the admin list, cheapest plan first then key. */
    @Transactional(readOnly = true)
    public List<PlanFeatureRequirement> list() {
        return repository.findAll().stream()
                .sorted((a, b) -> {
                    int byRank = Integer.compare(
                            PlanTier.requiredRank(a.getMinPlan()),
                            PlanTier.requiredRank(b.getMinPlan()));
                    return byRank != 0 ? byRank : a.getFeatureKey().compareTo(b.getFeatureKey());
                })
                .toList();
    }

    /**
     * Sets (or clears) the requirement for one feature.
     *
     * @param minPlan the required plan; {@code FREE}, blank or null CLEARS the
     *                requirement by deleting the row
     * @return the stored requirement, or {@code null} when it was cleared
     * @throws IllegalArgumentException on a malformed key or an unknown plan code
     */
    @Transactional
    public PlanFeatureRequirement set(String featureKey, String minPlan, String label, String updatedBy) {
        String key = normalizeKey(featureKey);
        String plan = minPlan == null ? "" : minPlan.trim().toUpperCase(Locale.ROOT);

        if (plan.isEmpty() || PlanTier.FREE.equals(plan)) {
            if (repository.existsById(key)) {
                repository.deleteById(key);
                cache.invalidateAll();
                log.info("[PlanFeatures] Cleared requirement for '{}' (by {})", key, updatedBy);
            }
            return null;
        }
        if (!PlanTier.isSelectable(plan)) {
            throw new IllegalArgumentException(
                    "Unknown plan code '" + minPlan + "'. Expected one of " + PlanTier.selectableCodes());
        }

        PlanFeatureRequirement existing = repository.findById(key).orElse(null);
        PlanFeatureRequirement saved;
        if (existing == null) {
            saved = repository.save(new PlanFeatureRequirement(key, plan, trimLabel(label), updatedBy));
        } else {
            existing.setMinPlan(plan);
            // A blank label must not erase a name that is already there: the admin
            // list is the only place some of these keys are ever spelled out.
            if (trimLabel(label) != null) {
                existing.setLabel(trimLabel(label));
            }
            existing.setUpdatedBy(updatedBy);
            existing.setUpdatedAt(java.time.Instant.now());
            saved = repository.save(existing);
        }
        cache.invalidateAll();
        log.info("[PlanFeatures] '{}' now requires {} (by {})", key, plan, updatedBy);
        return saved;
    }

    private Map<String, String> load() {
        Map<String, String> out = new LinkedHashMap<>();
        for (PlanFeatureRequirement row : repository.findAll()) {
            if (row.getFeatureKey() == null || row.getMinPlan() == null) {
                continue;
            }
            out.put(row.getFeatureKey(), row.getMinPlan());
        }
        return Map.copyOf(out);
    }

    private static String normalizeKey(String featureKey) {
        String key = featureKey == null ? "" : featureKey.trim().toLowerCase(Locale.ROOT);
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "Invalid feature key '" + featureKey + "'. Expected node:<type>, api:<slug>, "
                        + "tool:<slug> or feature:<capability>.");
        }
        return key;
    }

    private static String trimLabel(String label) {
        if (label == null) {
            return null;
        }
        String trimmed = label.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
    }
}
