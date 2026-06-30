package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("InterfaceDef")
class InterfaceDefTest {

    @Test
    @DisplayName("Should compute normalized key from label")
    void shouldComputeNormalizedKey() {
        InterfaceDef def = new InterfaceDef("uuid-1", "My Form", Map.of(), Map.of(), true, Map.of(), false);

        assertEquals("interface:my_form", def.getNormalizedKey());
    }

    @Test
    @DisplayName("Should handle accented characters in label normalization")
    void shouldHandleAccentedCharacters() {
        InterfaceDef def = new InterfaceDef("uuid-2", "Formulaire Entrée", Map.of(), Map.of(), true, Map.of(), false);

        assertEquals("interface:formulaire_entree", def.getNormalizedKey());
    }

    @Test
    @DisplayName("Should preserve all record fields")
    void shouldPreserveAllFields() {
        Map<String, String> actionMapping = Map.of("#btn", "trigger:submit");
        Map<String, String> variableMapping = Map.of("name", "{{trigger:start.name}}");
        Map<String, Object> position = Map.of("x", 100, "y", 200);

        InterfaceDef def = new InterfaceDef("uuid-3", "Contact Form",
            actionMapping, variableMapping, false, position, true);

        assertEquals("uuid-3", def.id());
        assertEquals("Contact Form", def.label());
        assertEquals(actionMapping, def.actionMapping());
        assertEquals(variableMapping, def.variableMapping());
        assertFalse(def.showPreview());
        assertEquals(position, def.position());
        assertTrue(def.isEntryInterface());
    }

    @Test
    @DisplayName("Should default isEntryInterface to false")
    void shouldDefaultIsEntryInterfaceToFalse() {
        InterfaceDef def = new InterfaceDef("uuid-4", "Simple", Map.of(), Map.of(), true, Map.of(), false);

        assertFalse(def.isEntryInterface());
    }

    @Test
    @DisplayName("Backward-compat 7-arg constructor defaults generateScreenshot AND exposeRenderedSource to false")
    void sevenArgConstructorDefaultsBothTogglesFalse() {
        InterfaceDef def = new InterfaceDef("uuid-5", "Legacy", Map.of(), Map.of(), true, Map.of(), false);

        assertEquals(Boolean.FALSE, def.generateScreenshot(),
            "All pre-PR2 call sites land here and must keep the screenshot toggle off by default");
        assertEquals(Boolean.FALSE, def.exposeRenderedSource(),
            "All pre-PR2 call sites must keep the rendered-source toggle off by default");
    }

    @Test
    @DisplayName("8-arg constructor propagates generateScreenshot and defaults exposeRenderedSource to false")
    void eightArgConstructorPropagatesScreenshotAndDefaultsRenderedSource() {
        InterfaceDef def = new InterfaceDef("uuid-6", "With capture",
            Map.of(), Map.of(), true, Map.of(), false, true);

        assertEquals(Boolean.TRUE, def.generateScreenshot());
        assertEquals(Boolean.FALSE, def.exposeRenderedSource(),
            "PR2 call sites that did not yet know about exposeRenderedSource must land here with default false");
    }

    @Test
    @DisplayName("Canonical 9-arg constructor carries both toggles through to the record")
    void canonicalConstructorPropagatesBothToggles() {
        InterfaceDef def = new InterfaceDef("uuid-7", "Full",
            Map.of(), Map.of(), true, Map.of(), false, true, true);

        assertEquals(Boolean.TRUE, def.generateScreenshot());
        assertEquals(Boolean.TRUE, def.exposeRenderedSource());
    }
}
