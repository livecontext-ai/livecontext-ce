package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ValidationWarning")
class ValidationWarningTest {

    @Nested
    @DisplayName("Record fields")
    class RecordFieldTests {

        @Test
        @DisplayName("Should store all fields correctly")
        void shouldStoreAllFields() {
            Map<String, Object> context = Map.of("suggestion", "Use HTTPS");
            ValidationWarning warning = new ValidationWarning("INSECURE_URL", "URL uses HTTP", "step.params.url", context);

            assertEquals("INSECURE_URL", warning.type());
            assertEquals("URL uses HTTP", warning.message());
            assertEquals("step.params.url", warning.path());
            assertEquals(context, warning.context());
        }

        @Test
        @DisplayName("Should allow null fields")
        void shouldAllowNullFields() {
            ValidationWarning warning = new ValidationWarning(null, null, null, null);

            assertNull(warning.type());
            assertNull(warning.message());
            assertNull(warning.path());
            assertNull(warning.context());
        }
    }

    @Nested
    @DisplayName("Record equality")
    class EqualityTests {

        @Test
        @DisplayName("Equal records should be equal")
        void equalRecordsShouldBeEqual() {
            ValidationWarning w1 = new ValidationWarning("TYPE", "msg", "path", Map.of());
            ValidationWarning w2 = new ValidationWarning("TYPE", "msg", "path", Map.of());
            assertEquals(w1, w2);
            assertEquals(w1.hashCode(), w2.hashCode());
        }

        @Test
        @DisplayName("Different records should not be equal")
        void differentRecordsShouldNotBeEqual() {
            ValidationWarning w1 = new ValidationWarning("TYPE_A", "msg", "path", Map.of());
            ValidationWarning w2 = new ValidationWarning("TYPE_B", "msg", "path", Map.of());
            assertNotEquals(w1, w2);
        }
    }
}
