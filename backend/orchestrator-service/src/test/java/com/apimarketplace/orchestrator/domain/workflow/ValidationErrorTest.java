package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ValidationError")
class ValidationErrorTest {

    @Nested
    @DisplayName("Record fields")
    class RecordFieldTests {

        @Test
        @DisplayName("Should store all fields correctly")
        void shouldStoreAllFields() {
            Map<String, Object> context = Map.of("field", "value");
            ValidationError error = new ValidationError("MISSING_FIELD", "Field is required", "step.params.url", context);

            assertEquals("MISSING_FIELD", error.type());
            assertEquals("Field is required", error.message());
            assertEquals("step.params.url", error.path());
            assertEquals(context, error.context());
        }

        @Test
        @DisplayName("Should allow null fields")
        void shouldAllowNullFields() {
            ValidationError error = new ValidationError(null, null, null, null);

            assertNull(error.type());
            assertNull(error.message());
            assertNull(error.path());
            assertNull(error.context());
        }

        @Test
        @DisplayName("Should allow empty context")
        void shouldAllowEmptyContext() {
            ValidationError error = new ValidationError("TYPE", "msg", "path", Map.of());
            assertTrue(error.context().isEmpty());
        }
    }

    @Nested
    @DisplayName("Record equality")
    class EqualityTests {

        @Test
        @DisplayName("Equal records should be equal")
        void equalRecordsShouldBeEqual() {
            ValidationError e1 = new ValidationError("TYPE", "msg", "path", Map.of());
            ValidationError e2 = new ValidationError("TYPE", "msg", "path", Map.of());
            assertEquals(e1, e2);
            assertEquals(e1.hashCode(), e2.hashCode());
        }

        @Test
        @DisplayName("Different records should not be equal")
        void differentRecordsShouldNotBeEqual() {
            ValidationError e1 = new ValidationError("TYPE_A", "msg", "path", Map.of());
            ValidationError e2 = new ValidationError("TYPE_B", "msg", "path", Map.of());
            assertNotEquals(e1, e2);
        }
    }

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("Should include type and message")
        void shouldIncludeTypeAndMessage() {
            ValidationError error = new ValidationError("MISSING", "Required field", "path", Map.of());
            String str = error.toString();
            assertTrue(str.contains("MISSING"));
            assertTrue(str.contains("Required field"));
        }
    }
}
