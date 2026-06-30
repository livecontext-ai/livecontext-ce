package com.apimarketplace.agent.compaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 3.7.4 - pin the action-level allow/deny list behaviour. These
 * tests fence off the two invariants that the rest of the platform
 * depends on:
 *
 * <ul>
 *   <li><b>Never-mask secrets</b> ({@code catalog.execute} and the
 *       {@code credential.*} pairs stay full-length in COLD - their
 *       truncation would silently corrupt replay).</li>
 *   <li><b>Fail-safe default</b> (unknown pair → not compactable - same
 *       posture as {@link ContextCompactionTools}).</li>
 * </ul>
 */
@DisplayName("CompactionActionAllowlist - compactable pairs, never-mask invariant (Stage 3.7.4)")
class CompactionActionAllowlistTest {

    // ---- Compactable list ----

    @ParameterizedTest(name = "isCompactable({0}, {1}) == true")
    @CsvSource({
            "catalog,search",
            "web_search,search",
            "web_search,fetch",
            "workflow,list",
            "workflow,help",
            "agent,list",
            "skill,list",
            "interface,list",
            "table,query_rows"
    })
    @DisplayName("known compactable pairs are allowed to preview-collapse in COLD")
    void knownCompactablePairs(String tool, String action) {
        assertThat(CompactionActionAllowlist.isCompactable(tool, action)).isTrue();
    }

    @Test
    @DisplayName("compactable set is exactly the 9 audited read-only surfaces - pinned to prevent drift")
    void compactableMembership() {
        assertThat(CompactionActionAllowlist.COMPACTABLE_ACTIONS)
                .containsExactlyInAnyOrder(
                        "catalog.search",
                        "web_search.search",
                        "web_search.fetch",
                        "workflow.list",
                        "workflow.help",
                        "agent.list",
                        "skill.list",
                        "interface.list",
                        "table.query_rows"
                );
    }

    @ParameterizedTest(name = "isCompactable({0}, {1}) == false - unknown pair")
    @CsvSource({
            "catalog,execute",          // the canonical secrets-carrying pair
            "workflow,save",
            "credential,create",
            "publish,publish",
            "made_up_tool,search",
            "catalog,SEARCH",           // case-sensitive - action names are canonical lowercase
            "CATALOG,search"
    })
    @DisplayName("unknown / case-mismatched pairs default to NOT compactable (fail-safe)")
    void unknownPairsFailSafe(String tool, String action) {
        assertThat(CompactionActionAllowlist.isCompactable(tool, action)).isFalse();
    }

    // ---- Never-mask invariant ----

    @ParameterizedTest(name = "isNeverMask({0}, {1}) == true")
    @CsvSource({
            "credential,create",
            "credential,get",
            "credential,update",
            "credential,delete",
            "publish,publish",
            "publish,unpublish",
            "catalog,execute"
    })
    @DisplayName("never-mask pairs carry secrets or side-effects - must stay full-length in COLD")
    void neverMaskPairs(String tool, String action) {
        assertThat(CompactionActionAllowlist.isNeverMask(tool, action)).isTrue();
    }

    @Test
    @DisplayName("catalog.execute is explicitly never-mask - U9 invariant (request bodies carry api_key / PII)")
    void catalogExecuteIsNeverMask() {
        // This is the canonical failure mode the never-mask list
        // protects against: `catalog.execute` stored inputs often
        // contain api_key / access_token / PII that later reads
        // depend on. Silent preview-collapse would return wrong
        // data on replay. Pin it explicitly so a future "oh
        // catalog.execute is huge, let's mask it" refactor trips
        // this test.
        assertThat(CompactionActionAllowlist.isNeverMask("catalog", "execute")).isTrue();
        assertThat(CompactionActionAllowlist.isCompactable("catalog", "execute")).isFalse();
    }

    @Test
    @DisplayName("never-mask set is exactly the 7 dangerous pairs - pinned")
    void neverMaskMembership() {
        assertThat(CompactionActionAllowlist.NEVER_MASK_ACTIONS)
                .containsExactlyInAnyOrder(
                        "credential.create",
                        "credential.get",
                        "credential.update",
                        "credential.delete",
                        "publish.publish",
                        "publish.unpublish",
                        "catalog.execute"
                );
    }

    @Test
    @DisplayName("compactable and never-mask sets are disjoint - a pair cannot be both")
    void compactableAndNeverMaskDisjoint() {
        // Set intersection must be empty. Pin the structural
        // guarantee: a pair appearing in both would leave the
        // masker's decision ambiguous (and risk leaking secrets
        // if isCompactable is checked first).
        Set<String> intersection = new HashSet<>(CompactionActionAllowlist.COMPACTABLE_ACTIONS);
        intersection.retainAll(CompactionActionAllowlist.NEVER_MASK_ACTIONS);
        assertThat(intersection)
                .as("COMPACTABLE ∩ NEVER_MASK must be empty - overlap would be a policy bug")
                .isEmpty();
    }

    // ---- Null / blank handling ----

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    @DisplayName("isCompactable with null/blank toolName returns false")
    void isCompactableNullBlankTool(String tool) {
        assertThat(CompactionActionAllowlist.isCompactable(tool, "search")).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    @DisplayName("isCompactable with null/blank actionName returns false")
    void isCompactableNullBlankAction(String action) {
        assertThat(CompactionActionAllowlist.isCompactable("catalog", action)).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    @DisplayName("isNeverMask with null/blank toolName returns false (refuses to guess)")
    void isNeverMaskNullBlankTool(String tool) {
        // A null toolName for a never-mask check must NOT return
        // true - the caller has no signal to act on, and "refuse
        // to touch" is the safe default. Symmetric with
        // isCompactable.
        assertThat(CompactionActionAllowlist.isNeverMask(tool, "execute")).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    @DisplayName("isNeverMask with null/blank actionName returns false")
    void isNeverMaskNullBlankAction(String action) {
        assertThat(CompactionActionAllowlist.isNeverMask("credential", action)).isFalse();
    }

    // ---- Case sensitivity ----

    @Test
    @DisplayName("case matters - CATALOG.SEARCH is NOT the same key as catalog.search")
    void caseSensitivity() {
        // Tool & action names are canonical-lowercase in the
        // platform. A caller passing "CATALOG" or "Search" is
        // almost certainly bugged; treating them as equivalent
        // would let the bug slip through. Pin strict equality.
        assertThat(CompactionActionAllowlist.isCompactable("CATALOG", "search")).isFalse();
        assertThat(CompactionActionAllowlist.isCompactable("catalog", "SEARCH")).isFalse();
        assertThat(CompactionActionAllowlist.isNeverMask("CREDENTIAL", "create")).isFalse();
    }
}
