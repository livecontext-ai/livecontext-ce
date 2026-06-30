package com.apimarketplace.publication.service;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps a user's onboarding choices (interests / useCases / profession - the
 * enums collected in {@code frontend/app/[locale]/onboarding/page.tsx}) onto
 * marketplace category slugs (the taxonomy seeded by migration V300 into
 * {@code orchestrator.workflow_categories}).
 *
 * <p>This is the relevance layer behind the onboarding "suggested applications"
 * modal: the frontend forwards the onboarding profile, this mapper turns it into
 * category slugs, and {@link PublicationListQueryService#suggestApplications}
 * fetches the live public applications in those categories. The catalog of
 * applications stays dynamic - only the onboarding-token → category mapping is
 * curated here.
 *
 * <p>Ordering is stable and deterministic: interests first (most explicit
 * signal), then useCases, then profession; duplicates are dropped keeping the
 * first occurrence. Unknown tokens and {@code "other"} contribute nothing.
 */
@Component
public class OnboardingCategoryMapper {

    /** interests[] token → category slug(s). */
    private static final Map<String, List<String>> INTEREST_TO_CATEGORIES = Map.ofEntries(
            Map.entry("automation", List.of("automation")),
            Map.entry("ai-ml", List.of("ai-automation")),
            Map.entry("data-analytics", List.of("data-analytics")),
            Map.entry("integrations", List.of("automation")),
            Map.entry("productivity", List.of("productivity")),
            Map.entry("business-intelligence", List.of("data-analytics")),
            Map.entry("customer-experience", List.of("customer-support")),
            Map.entry("sales-crm", List.of("sales-crm")),
            Map.entry("marketing-automation", List.of("marketing")),
            Map.entry("ecommerce-tools", List.of("ecommerce"))
    );

    /** useCases[] token → category slug(s). */
    private static final Map<String, List<String>> USECASE_TO_CATEGORIES = Map.ofEntries(
            Map.entry("workflow-automation", List.of("automation")),
            Map.entry("chatbots-assistants", List.of("ai-automation")),
            Map.entry("data-integration", List.of("data-analytics")),
            Map.entry("content-generation", List.of("content")),
            Map.entry("lead-generation", List.of("sales-crm", "marketing")),
            Map.entry("customer-support", List.of("customer-support")),
            Map.entry("reporting-dashboards", List.of("data-analytics")),
            Map.entry("ecommerce-automation", List.of("ecommerce")),
            Map.entry("team-collaboration", List.of("productivity", "communication")),
            Map.entry("monitoring-alerts", List.of("monitoring"))
    );

    /** profession token → category slug(s). */
    private static final Map<String, List<String>> PROFESSION_TO_CATEGORIES = Map.ofEntries(
            Map.entry("sales", List.of("sales-crm")),
            Map.entry("marketing", List.of("marketing")),
            Map.entry("customer-success", List.of("customer-support")),
            Map.entry("support", List.of("customer-support")),
            Map.entry("ecommerce", List.of("ecommerce")),
            Map.entry("operations", List.of("automation")),
            Map.entry("product", List.of("productivity")),
            Map.entry("engineering", List.of("automation")),
            Map.entry("data-analytics", List.of("data-analytics")),
            Map.entry("finance", List.of("finance")),
            Map.entry("hr", List.of("productivity")),
            Map.entry("founder", List.of("sales-crm", "marketing")),
            Map.entry("freelancer", List.of("productivity"))
    );

    /**
     * Resolve onboarding choices to an ordered, deduplicated list of category
     * slugs. Returns an empty list when nothing maps (caller falls back to a
     * generic suggestion set).
     */
    public List<String> toCategorySlugs(List<String> interests,
                                        List<String> useCases,
                                        String profession) {
        Set<String> slugs = new LinkedHashSet<>();
        addAll(slugs, interests, INTEREST_TO_CATEGORIES);
        addAll(slugs, useCases, USECASE_TO_CATEGORIES);
        if (profession != null) {
            List<String> mapped = PROFESSION_TO_CATEGORIES.get(normalize(profession));
            if (mapped != null) slugs.addAll(mapped);
        }
        return List.copyOf(slugs);
    }

    private static void addAll(Set<String> out, List<String> tokens, Map<String, List<String>> table) {
        if (tokens == null) return;
        for (String token : tokens) {
            if (token == null) continue;
            List<String> mapped = table.get(normalize(token));
            if (mapped != null) out.addAll(mapped);
        }
    }

    private static String normalize(String token) {
        return token.trim().toLowerCase(Locale.ROOT);
    }
}
