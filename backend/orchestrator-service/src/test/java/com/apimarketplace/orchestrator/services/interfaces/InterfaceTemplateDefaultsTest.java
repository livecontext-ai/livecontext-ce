package com.apimarketplace.orchestrator.services.interfaces;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InterfaceTemplateDefaults - {{var|default}} substitution shared by screenshot + InterfaceNode")
class InterfaceTemplateDefaultsTest {

    @Nested
    @DisplayName("apply()")
    class Apply {

        @Test
        @DisplayName("substitutes when the var is present in the map")
        void substitutesWhenVarPresent() {
            Map<String, Object> vars = Map.of("name", "Alice");
            assertEquals("Hello Alice", InterfaceTemplateDefaults.apply("Hello {{name}}", vars));
        }

        @Test
        @DisplayName("uses the inline default when the var is missing")
        void usesDefaultWhenVarMissing() {
            assertEquals("Hello Stranger",
                InterfaceTemplateDefaults.apply("Hello {{name|Stranger}}", Map.of()));
        }

        @Test
        @DisplayName("empty default when var missing and no default declared")
        void emptyDefaultWhenNoDefault() {
            assertEquals("Hello ", InterfaceTemplateDefaults.apply("Hello {{name}}", Map.of()));
        }

        @Test
        @DisplayName("null template returns empty string (defensive - callers can always concatenate)")
        void nullTemplateReturnsEmpty() {
            assertEquals("", InterfaceTemplateDefaults.apply(null, Map.of()));
        }

        @Test
        @DisplayName("empty template returns empty string")
        void emptyTemplateReturnsEmpty() {
            assertEquals("", InterfaceTemplateDefaults.apply("", Map.of()));
        }

        @Test
        @DisplayName("null vars map → treats every var as missing, falls back to defaults")
        void nullVarsTreatsAsMissing() {
            assertEquals("hi Stranger",
                InterfaceTemplateDefaults.apply("hi {{name|Stranger}}", null));
        }

        @Test
        @DisplayName("multiple placeholders in the same string substitute independently")
        void multiplePlaceholders() {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("first", "Alice");
            vars.put("count", 3);
            assertEquals("Alice has 3 items",
                InterfaceTemplateDefaults.apply("{{first}} has {{count}} items", vars));
        }

        @Test
        @DisplayName("non-identifier placeholders are left untouched")
        void leavesNonIdentifierUntouched() {
            // {{ trigger:start.value }} is not a simple identifier name → no substitution
            String template = "{{ trigger:start.value }}";
            assertEquals(template, InterfaceTemplateDefaults.apply(template, Map.of()));
        }

        @Test
        @DisplayName("special regex chars in value do not corrupt the replacement (Matcher.quoteReplacement guard)")
        void quoteReplacementGuard() {
            Map<String, Object> vars = new HashMap<>();
            vars.put("payload", "$1 and \\\\backslash");
            // Without Matcher.quoteReplacement the $1 would be interpreted as a backref → corruption
            assertEquals("seen: $1 and \\\\backslash",
                InterfaceTemplateDefaults.apply("seen: {{payload}}", vars));
        }

        @Test
        @DisplayName("FileRef-shaped map renders the storage path (so <img src=…> gets a valid value)")
        void fileRefMapRendersPath() {
            Map<String, Object> fileRef = new LinkedHashMap<>();
            fileRef.put("type", "FILE");
            fileRef.put("path", "tenant-1/wf/run/file.png");
            fileRef.put("name", "file.png");
            fileRef.put("mimeType", "image/png");
            fileRef.put("size", 1024L);
            Map<String, Object> vars = Map.of("photo", fileRef);

            assertEquals("<img src=\"tenant-1/wf/run/file.png\"/>",
                InterfaceTemplateDefaults.apply("<img src=\"{{photo}}\"/>", vars));
        }

        @Test
        @DisplayName("Map without path+mimeType keys falls through to Map.toString (not FileRef-shaped)")
        void nonFileRefMapStringified() {
            Map<String, Object> opaque = Map.of("foo", "bar");
            String result = InterfaceTemplateDefaults.apply("{{x}}", Map.of("x", opaque));
            assertTrue(result.contains("foo"), "Non-FileRef map should fall back to Map.toString()");
        }
    }
}
