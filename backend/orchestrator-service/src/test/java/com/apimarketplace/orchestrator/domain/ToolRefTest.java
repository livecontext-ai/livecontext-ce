package com.apimarketplace.orchestrator.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ToolRef")
class ToolRefTest {

    @Nested
    @DisplayName("Constructor validation")
    class ConstructorTests {

        @Test
        @DisplayName("Should create valid ToolRef")
        void shouldCreateValid() {
            ToolRef ref = new ToolRef("tool-123", 1);
            assertEquals("tool-123", ref.toolId());
            assertEquals(1, ref.version());
        }

        @Test
        @DisplayName("Should throw on null toolId")
        void shouldThrowOnNullToolId() {
            assertThrows(IllegalArgumentException.class, () -> new ToolRef(null, 1));
        }

        @Test
        @DisplayName("Should throw on empty toolId")
        void shouldThrowOnEmptyToolId() {
            assertThrows(IllegalArgumentException.class, () -> new ToolRef("", 1));
        }

        @Test
        @DisplayName("Should throw on blank toolId")
        void shouldThrowOnBlankToolId() {
            assertThrows(IllegalArgumentException.class, () -> new ToolRef("   ", 1));
        }

        @Test
        @DisplayName("Should throw on zero version")
        void shouldThrowOnZeroVersion() {
            assertThrows(IllegalArgumentException.class, () -> new ToolRef("tool-1", 0));
        }

        @Test
        @DisplayName("Should throw on negative version")
        void shouldThrowOnNegativeVersion() {
            assertThrows(IllegalArgumentException.class, () -> new ToolRef("tool-1", -1));
        }
    }

    @Nested
    @DisplayName("Getters")
    class GetterTests {

        @Test
        @DisplayName("getToolId should return toolId")
        void getToolIdShouldReturn() {
            ToolRef ref = new ToolRef("tool-1", 2);
            assertEquals("tool-1", ref.getToolId());
        }

        @Test
        @DisplayName("getVersion should return version")
        void getVersionShouldReturn() {
            ToolRef ref = new ToolRef("tool-1", 2);
            assertEquals(2, ref.getVersion());
        }
    }

    @Nested
    @DisplayName("isSameTool()")
    class IsSameToolTests {

        @Test
        @DisplayName("Should return true for same tool and version")
        void shouldReturnTrueForSameToolAndVersion() {
            ToolRef ref1 = new ToolRef("tool-1", 1);
            ToolRef ref2 = new ToolRef("tool-1", 1);
            assertTrue(ref1.isSameTool(ref2));
        }

        @Test
        @DisplayName("Should return false for different version")
        void shouldReturnFalseForDifferentVersion() {
            ToolRef ref1 = new ToolRef("tool-1", 1);
            ToolRef ref2 = new ToolRef("tool-1", 2);
            assertFalse(ref1.isSameTool(ref2));
        }

        @Test
        @DisplayName("Should return false for different tool")
        void shouldReturnFalseForDifferentTool() {
            ToolRef ref1 = new ToolRef("tool-1", 1);
            ToolRef ref2 = new ToolRef("tool-2", 1);
            assertFalse(ref1.isSameTool(ref2));
        }

        @Test
        @DisplayName("Should return false for null")
        void shouldReturnFalseForNull() {
            ToolRef ref = new ToolRef("tool-1", 1);
            assertFalse(ref.isSameTool(null));
        }
    }

    @Nested
    @DisplayName("isSameToolId()")
    class IsSameToolIdTests {

        @Test
        @DisplayName("Should return true for same toolId regardless of version")
        void shouldReturnTrueForSameId() {
            ToolRef ref1 = new ToolRef("tool-1", 1);
            ToolRef ref2 = new ToolRef("tool-1", 5);
            assertTrue(ref1.isSameToolId(ref2));
        }

        @Test
        @DisplayName("Should return false for different toolId")
        void shouldReturnFalseForDifferentId() {
            ToolRef ref1 = new ToolRef("tool-1", 1);
            ToolRef ref2 = new ToolRef("tool-2", 1);
            assertFalse(ref1.isSameToolId(ref2));
        }

        @Test
        @DisplayName("Should return false for null")
        void shouldReturnFalseForNull() {
            ToolRef ref = new ToolRef("tool-1", 1);
            assertFalse(ref.isSameToolId(null));
        }
    }

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("Should include toolId and version")
        void shouldIncludeFields() {
            ToolRef ref = new ToolRef("tool-1", 3);
            String str = ref.toString();
            assertTrue(str.contains("tool-1"));
            assertTrue(str.contains("3"));
        }
    }
}
