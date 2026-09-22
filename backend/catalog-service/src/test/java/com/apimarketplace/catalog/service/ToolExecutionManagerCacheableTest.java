package com.apimarketplace.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which endpoints may have their previous answer replayed, and the one class that may not.
 *
 * <p>The agent response cache is right for a read: asking twice costs the same answer twice.
 * It is wrong for an endpoint that DRAINS what it returns, and production showed how wrong.
 * Telegram's {@code getUpdates} takes the pending updates and moves the offset past them, so
 * the batch exists once. Cached, the first call in the window returned it and every later one
 * replayed the same empty array: 313 calls over three days, every single one
 * {@code {"ok":true,"result":[]}}, most answered in under 40ms without reaching Telegram. No
 * error was ever raised. A scheduled agent simply reported an empty inbox forever.
 */
class ToolExecutionManagerCacheableTest {

    private ToolExecutionManager manager;

    @BeforeEach
    void setUp() {
        // Only the mapper participates in the decision under test; the other collaborators
        // belong to the execution path this method deliberately sits outside of.
        manager = new ToolExecutionManager(
                null, null, new ObjectMapper(), null, null, null, null,
                null, null, null, null, null, null, null);
    }

    private ToolContextService.ToolContext contextWithSpec(String executionSpecJson) {
        ToolContextService.ToolContext context = new ToolContextService.ToolContext();
        context.setToolId("beb1c5c1-d9df-44d0-8b3b-b10f8604c4e2");
        context.setExecutionSpecJson(executionSpecJson);
        return context;
    }

    @Test
    @DisplayName("cacheable:false in the execution spec takes the endpoint out of the cache")
    void honoursCacheableFalse() {
        assertThat(manager.isCacheable(contextWithSpec(
                "{\"mode\":\"sync\",\"cacheable\":false,\"response\":{\"type\":\"json\"}}")))
                .isFalse();
    }

    @Test
    @DisplayName("cacheable:true is cacheable, like saying nothing")
    void honoursCacheableTrue() {
        assertThat(manager.isCacheable(contextWithSpec("{\"mode\":\"sync\",\"cacheable\":true}")))
                .isTrue();
    }

    /**
     * The state every one of the 32000-odd endpoints is in. If absence did not mean cacheable,
     * this change would have silently switched the cache off for the whole catalog.
     */
    @Test
    @DisplayName("an execution spec that says nothing about caching stays cacheable")
    void defaultsToCacheable() {
        assertThat(manager.isCacheable(contextWithSpec("{\"mode\":\"sync\",\"response\":{\"type\":\"json\"}}")))
                .isTrue();
        assertThat(manager.isCacheable(contextWithSpec(null))).isTrue();
        assertThat(manager.isCacheable(contextWithSpec(""))).isTrue();
        assertThat(manager.isCacheable(null)).isTrue();
    }

    /**
     * A JSON boolean, not a truthy value. {@code "false"} as a STRING is truthy in Java and in
     * JavaScript alike, so reading it loosely would leave the cache on for exactly the endpoint
     * that declared it off, which is the failure this whole rule exists to prevent. The seed
     * validator rejects the string form; this is the runtime half of the same guard.
     */
    @Test
    @DisplayName("a non-boolean cacheable is ignored rather than guessed at")
    void ignoresNonBooleanCacheable() {
        assertThat(manager.isCacheable(contextWithSpec("{\"mode\":\"sync\",\"cacheable\":\"false\"}")))
                .isTrue();
        assertThat(manager.isCacheable(contextWithSpec("{\"mode\":\"sync\",\"cacheable\":0}")))
                .isTrue();
    }

    /**
     * Fails towards the behaviour every other endpoint already has. An unreadable spec is a
     * data problem, and turning the cache off for it would make an unrelated fault look like a
     * performance regression.
     */
    @Test
    @DisplayName("an unparseable execution spec stays cacheable")
    void unparseableSpecStaysCacheable() {
        assertThat(manager.isCacheable(contextWithSpec("{not json"))).isTrue();
    }
}
