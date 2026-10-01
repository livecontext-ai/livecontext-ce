package com.apimarketplace.orchestrator.tools.workflow.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FormFieldDefaults - the one spelling of a form field default")
class FormFieldDefaultsTest {

    private Map<String, Object> field(Object... kv) {
        Map<String, Object> f = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) f.put((String) kv[i], kv[i + 1]);
        return f;
    }

    @Test
    @DisplayName("canonicalize: a blank defaultValue does not block the alias (writer agrees with of())")
    void blankDefaultValueDoesNotBlockAlias() {
        Map<String, Object> f = field("name", "x", "defaultValue", "", "default", "X");

        assertThat(FormFieldDefaults.of(f)).isEqualTo("X");
        FormFieldDefaults.canonicalize(f);

        assertThat(f).containsEntry("defaultValue", "X").doesNotContainKey("default");
    }

    @Test
    @DisplayName("canonicalize: a null alias is dropped and writes nothing")
    void nullAliasIsDropped() {
        Map<String, Object> f = new HashMap<>();
        f.put("name", "x");
        f.put("default", null);

        FormFieldDefaults.canonicalize(f);

        assertThat(f).doesNotContainKeys("default", "defaultValue");
    }

    @Test
    @DisplayName("canonicalize: a non-string default (number, boolean) is kept as authored")
    void nonStringDefaultKept() {
        Map<String, Object> f = field("name", "n", "default", 2000);
        FormFieldDefaults.canonicalize(f);
        assertThat(f).containsEntry("defaultValue", 2000);
    }

    @Test
    @DisplayName("of: reads defaultValue first, then the aliases, and a blank everywhere is no default")
    void ofReadOrder() {
        assertThat(FormFieldDefaults.of(field("defaultValue", "a", "default", "b"))).isEqualTo("a");
        assertThat(FormFieldDefaults.of(field("default_value", "c"))).isEqualTo("c");
        assertThat(FormFieldDefaults.of(field("defaultValue", " ", "default", ""))).isNull();
        assertThat(FormFieldDefaults.of(field("name", "x"))).isNull();
    }
}
