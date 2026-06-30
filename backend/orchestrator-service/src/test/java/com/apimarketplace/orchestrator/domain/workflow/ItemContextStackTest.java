package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ItemContextStack")
class ItemContextStackTest {

    private ItemContextStack stack;

    @BeforeEach
    void setUp() {
        stack = new ItemContextStack();
    }

    @AfterEach
    void tearDown() {
        stack.clear();
    }

    @Nested
    @DisplayName("Item context push/pop")
    class ItemContextPushPopTests {

        @Test
        @DisplayName("Should return empty when no context pushed")
        void shouldReturnEmptyWhenNoPush() {
            Optional<ItemContextStack.ItemContext> ctx = stack.getCurrentItemContext();
            assertTrue(ctx.isEmpty());
        }

        @Test
        @DisplayName("Should push and retrieve item context")
        void shouldPushAndRetrieve() {
            stack.pushItemContext("step1", "trigger:start", 0, Map.of("key", "val"));

            Optional<ItemContextStack.ItemContext> ctx = stack.getCurrentItemContext();
            assertTrue(ctx.isPresent());
            assertEquals("step1", ctx.get().stepId());
            assertEquals("trigger:start", ctx.get().triggerKey());
            assertEquals(0, ctx.get().index());
            assertEquals(Map.of("key", "val"), ctx.get().data());
        }

        @Test
        @DisplayName("Should pop item context")
        void shouldPopItemContext() {
            stack.pushItemContext("step1", "trigger:start", 0, Map.of());
            stack.popItemContext();

            assertTrue(stack.getCurrentItemContext().isEmpty());
        }

        @Test
        @DisplayName("Should support stack behavior (LIFO)")
        void shouldSupportStackBehavior() {
            stack.pushItemContext("step1", "trigger:start", 0, Map.of());
            stack.pushItemContext("step2", "trigger:start", 1, Map.of());

            assertEquals("step2", stack.getCurrentItemContext().get().stepId());
            stack.popItemContext();
            assertEquals("step1", stack.getCurrentItemContext().get().stepId());
        }

        @Test
        @DisplayName("Should handle pop on empty stack gracefully")
        void shouldHandlePopOnEmpty() {
            stack.popItemContext(); // Should not throw
            assertTrue(stack.getCurrentItemContext().isEmpty());
        }

        @Test
        @DisplayName("Should push ItemContext object directly")
        void shouldPushItemContextObject() {
            ItemContextStack.ItemContext ctx = new ItemContextStack.ItemContext(
                "step1", "trigger:start", 0, Map.of("k", "v"), null);
            stack.pushItemContext(ctx);

            assertTrue(stack.getCurrentItemContext().isPresent());
            assertEquals("step1", stack.getCurrentItemContext().get().stepId());
        }

        @Test
        @DisplayName("Should ignore null context push")
        void shouldIgnoreNullContextPush() {
            stack.pushItemContext((ItemContextStack.ItemContext) null);
            assertTrue(stack.getCurrentItemContext().isEmpty());
        }
    }

    @Nested
    @DisplayName("ItemContext record")
    class ItemContextRecordTests {

        @Test
        @DisplayName("data should be immutable")
        void dataShouldBeImmutable() {
            ItemContextStack.ItemContext ctx = new ItemContextStack.ItemContext(
                "step1", "trigger:start", 0, Map.of("key", "val"), null);
            assertThrows(UnsupportedOperationException.class, () ->
                ctx.data().put("new", "value"));
        }

        @Test
        @DisplayName("data should default to empty map when null")
        void dataShouldDefaultToEmptyMap() {
            ItemContextStack.ItemContext ctx = new ItemContextStack.ItemContext(
                "step1", "trigger:start", 0, null, null);
            assertNotNull(ctx.data());
            assertTrue(ctx.data().isEmpty());
        }
    }

    @Nested
    @DisplayName("clear()")
    class ClearTests {

        @Test
        @DisplayName("Should clear all contexts")
        void shouldClearAll() {
            stack.pushItemContext("step1", "trigger:start", 0, Map.of());

            stack.clear();

            assertTrue(stack.getCurrentItemContext().isEmpty());
        }
    }
}
