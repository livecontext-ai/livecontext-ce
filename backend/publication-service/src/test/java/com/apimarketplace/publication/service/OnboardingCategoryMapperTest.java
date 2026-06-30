package com.apimarketplace.publication.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OnboardingCategoryMapper} - the curated onboarding-token
 * → marketplace-category-slug mapping behind the onboarding "suggested apps"
 * modal. Pure logic, no Spring context.
 */
@DisplayName("OnboardingCategoryMapper")
class OnboardingCategoryMapperTest {

    private final OnboardingCategoryMapper mapper = new OnboardingCategoryMapper();

    @Test
    @DisplayName("Maps a single interest token to its category slug")
    void mapsSingleInterest() {
        List<String> slugs = mapper.toCategorySlugs(List.of("sales-crm"), null, null);
        assertThat(slugs).containsExactly("sales-crm");
    }

    @Test
    @DisplayName("Maps a useCase that fans out to multiple categories (lead-generation → sales-crm + marketing)")
    void mapsMultiCategoryUseCase() {
        List<String> slugs = mapper.toCategorySlugs(null, List.of("lead-generation"), null);
        assertThat(slugs).containsExactly("sales-crm", "marketing");
    }

    @Test
    @DisplayName("Maps team-collaboration to productivity + communication")
    void mapsTeamCollaboration() {
        List<String> slugs = mapper.toCategorySlugs(null, List.of("team-collaboration"), null);
        assertThat(slugs).containsExactly("productivity", "communication");
    }

    @Test
    @DisplayName("Maps a profession token to its category slug")
    void mapsProfession() {
        List<String> slugs = mapper.toCategorySlugs(null, null, "finance");
        assertThat(slugs).containsExactly("finance");
    }

    @Test
    @DisplayName("Combines all three sources in order (interests → useCases → profession) and deduplicates")
    void combinesAndDeduplicatesPreservingOrder() {
        // interests: productivity → 'productivity'
        // useCases:  reporting-dashboards → 'data-analytics', team-collaboration → 'productivity','communication'
        // profession: sales → 'sales-crm'
        List<String> slugs = mapper.toCategorySlugs(
                List.of("productivity"),
                List.of("reporting-dashboards", "team-collaboration"),
                "sales");

        // 'productivity' appears first (from interests) and is NOT duplicated by team-collaboration.
        assertThat(slugs).containsExactly("productivity", "data-analytics", "communication", "sales-crm");
    }

    @Test
    @DisplayName("Ignores unknown tokens and the 'other' sentinel")
    void ignoresUnknownAndOther() {
        List<String> slugs = mapper.toCategorySlugs(
                List.of("other", "totally-unknown"),
                List.of("other"),
                "other");
        assertThat(slugs).isEmpty();
    }

    @Test
    @DisplayName("Null inputs yield an empty list (caller falls back to generic suggestions)")
    void nullInputsYieldEmpty() {
        assertThat(mapper.toCategorySlugs(null, null, null)).isEmpty();
    }

    @Test
    @DisplayName("Token matching is case-insensitive and trims whitespace")
    void caseInsensitiveAndTrimmed() {
        List<String> slugs = mapper.toCategorySlugs(List.of("  Sales-CRM  "), null, "  MARKETING ");
        assertThat(slugs).containsExactly("sales-crm", "marketing");
    }

    @Test
    @DisplayName("Skips null elements inside the token lists without failing")
    void skipsNullElements() {
        List<String> interests = new java.util.ArrayList<>();
        interests.add("automation");
        interests.add(null);
        List<String> slugs = mapper.toCategorySlugs(interests, null, null);
        assertThat(slugs).containsExactly("automation");
    }
}
