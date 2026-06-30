package com.apimarketplace.common.scaling.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryBudgetCacheTest {

    private InMemoryBudgetCache cache;

    @BeforeEach
    void setUp() {
        cache = new InMemoryBudgetCache();
    }

    @Test
    void get_nonexistent_key_returns_null() {
        assertNull(cache.get("nonexistent"));
    }

    @Test
    void set_and_get() {
        cache.set("budget-1", new BigDecimal("100.50"));
        assertEquals(new BigDecimal("100.50"), cache.get("budget-1"));
    }

    @Test
    void set_overwrites_existing_value() {
        cache.set("budget-1", new BigDecimal("100"));
        cache.set("budget-1", new BigDecimal("200"));
        assertEquals(new BigDecimal("200"), cache.get("budget-1"));
    }

    @Test
    void decrementAndGet_from_existing_value() {
        cache.set("budget-1", new BigDecimal("100"));

        BigDecimal result = cache.decrementAndGet("budget-1", new BigDecimal("30"));
        assertEquals(new BigDecimal("70"), result);
        assertEquals(new BigDecimal("70"), cache.get("budget-1"));
    }

    @Test
    void decrementAndGet_from_nonexistent_key_returns_null() {
        BigDecimal result = cache.decrementAndGet("budget-1", new BigDecimal("10"));
        assertNull(result);
    }

    @Test
    void decrementAndGet_insufficient_budget_returns_current_unchanged() {
        cache.set("budget-1", new BigDecimal("5"));
        BigDecimal result = cache.decrementAndGet("budget-1", new BigDecimal("10"));
        assertEquals(new BigDecimal("5"), result);
        // Verify budget was NOT decremented
        assertEquals(new BigDecimal("5"), cache.get("budget-1"));
    }

    @Test
    void decrementAndGet_multiple_decrements() {
        cache.set("budget-1", new BigDecimal("100"));

        cache.decrementAndGet("budget-1", new BigDecimal("30"));
        cache.decrementAndGet("budget-1", new BigDecimal("25"));
        BigDecimal result = cache.decrementAndGet("budget-1", new BigDecimal("10"));

        assertEquals(new BigDecimal("35"), result);
    }

    @Test
    void remove_deletes_entry() {
        cache.set("budget-1", new BigDecimal("100"));
        assertTrue(cache.exists("budget-1"));

        cache.remove("budget-1");

        assertFalse(cache.exists("budget-1"));
        assertNull(cache.get("budget-1"));
    }

    @Test
    void remove_nonexistent_does_not_throw() {
        assertDoesNotThrow(() -> cache.remove("nonexistent"));
    }

    @Test
    void exists_returns_false_for_missing_key() {
        assertFalse(cache.exists("nonexistent"));
    }

    @Test
    void exists_returns_true_for_present_key() {
        cache.set("budget-1", BigDecimal.ZERO);
        assertTrue(cache.exists("budget-1"));
    }

    @Test
    void multiple_keys_are_independent() {
        cache.set("budget-1", new BigDecimal("100"));
        cache.set("budget-2", new BigDecimal("200"));

        cache.decrementAndGet("budget-1", new BigDecimal("50"));

        assertEquals(new BigDecimal("50"), cache.get("budget-1"));
        assertEquals(new BigDecimal("200"), cache.get("budget-2"));
    }

    @Test
    void setIfAbsent_returns_true_when_absent() {
        assertTrue(cache.setIfAbsent("budget-1", new BigDecimal("100")));
        assertEquals(new BigDecimal("100"), cache.get("budget-1"));
    }

    @Test
    void setIfAbsent_returns_false_when_present() {
        cache.set("budget-1", new BigDecimal("100"));
        assertFalse(cache.setIfAbsent("budget-1", new BigDecimal("200")));
        assertEquals(new BigDecimal("100"), cache.get("budget-1"));
    }

    @Test
    void compareAndSet_succeeds_with_matching_value() {
        cache.set("budget-1", new BigDecimal("100.00"));
        assertTrue(cache.compareAndSet("budget-1", new BigDecimal("100.00"), new BigDecimal("80.00")));
        assertEquals(new BigDecimal("80.00"), cache.get("budget-1"));
    }

    @Test
    void compareAndSet_succeeds_with_different_scale() {
        // BigDecimal("100") and BigDecimal("100.00") are different by equals() but same by compareTo()
        cache.set("budget-1", new BigDecimal("100"));
        assertTrue(cache.compareAndSet("budget-1", new BigDecimal("100.00"), new BigDecimal("80")));
        assertEquals(new BigDecimal("80"), cache.get("budget-1"));
    }

    @Test
    void compareAndSet_fails_with_wrong_expected() {
        cache.set("budget-1", new BigDecimal("100"));
        assertFalse(cache.compareAndSet("budget-1", new BigDecimal("999"), new BigDecimal("80")));
        assertEquals(new BigDecimal("100"), cache.get("budget-1"));
    }

    @Test
    void compareAndSet_fails_for_nonexistent_key() {
        assertFalse(cache.compareAndSet("nonexistent", new BigDecimal("0"), new BigDecimal("100")));
    }
}
