package com.apimarketplace.publication.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural regression guard for the PR5 marketplace search query.
 *
 * <p>The first PR5 cut used {@code similarity(search_text, :q) > 0.1} for the
 * pg_trgm fuzzy branch. Prod verification showed this never matches: when
 * {@code search_text} is a long concatenation (~250+ chars) and {@code :q} is
 * a short token (1-3 words), plain {@code similarity()} compares whole strings
 * and the ratio saturates near 0. Typo tolerance (the headline PR5 feature -
 * {@code "gmial" → Gmail}) silently failed in production.
 *
 * <p>The fix is {@code catalog.word_similarity(:q, search_text) > 0.3}, which
 * scores the query against the best-matching word boundary in the haystack.
 * This test pins that shape so a future refactor cannot regress to plain
 * {@code similarity()} without breaking the test loudly.
 *
 * <p>Note: catalog's {@code LexicalSearchIndexRepository} uses plain
 * {@code similarity()} legitimately because it scores against SHORT fields
 * (provider, tool_name, action). The function is not categorically wrong - it's
 * wrong for long-haystack matching specifically.
 */
@DisplayName("WorkflowPublicationRepository.searchMarketplace - query shape regression")
class WorkflowPublicationRepositorySearchQueryShapeTest {

    @Test
    @DisplayName("uses catalog.word_similarity (not plain similarity) for the fuzzy branch against search_text")
    void usesWordSimilarityForLongHaystackFuzzyBranch() throws NoSuchMethodException {
        Method m = WorkflowPublicationRepository.class.getMethod(
            "searchMarketplace", String.class, String.class,
            org.springframework.data.domain.Pageable.class);
        Query q = m.getAnnotation(Query.class);
        assertThat(q).as("@Query annotation must be present").isNotNull();

        String value = q.value();
        String count = q.countQuery();

        assertThat(value)
            .as("WHERE clause must use catalog.word_similarity for the fuzzy branch")
            .contains("catalog.word_similarity(cast(:q AS text), p.search_text) > 0.3");

        assertThat(value)
            .as("ORDER BY ranking must use catalog.word_similarity, not plain similarity")
            .contains("catalog.word_similarity(cast(:q AS text), p.search_text)");

        assertThat(count)
            .as("countQuery must mirror the WHERE clause with word_similarity")
            .contains("catalog.word_similarity(cast(:q AS text), p.search_text) > 0.3");

        // Negative guard: the bare `similarity(...search_text...)` shape is the bug.
        boolean hasBareSimilarityOnSearchText = Arrays.stream(value.split("\\R"))
            .anyMatch(line -> line.contains("similarity(p.search_text") || line.contains("similarity(search_text"));
        assertThat(hasBareSimilarityOnSearchText)
            .as("Regression: plain similarity() on search_text saturates near 0 for long haystacks - must not return")
            .isFalse();
    }
}
