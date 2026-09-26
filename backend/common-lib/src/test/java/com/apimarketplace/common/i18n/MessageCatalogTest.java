package com.apimarketplace.common.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MessageCatalog - server-side texts in the six app locales")
class MessageCatalogTest {

    /** A sound catalog: every locale has the same keys and placeholders. */
    private static Map<String, Map<String, String>> sound() {
        Map<String, Map<String, String>> m = new HashMap<>();
        for (String l : MessageCatalog.LOCALES) {
            m.put(l, new HashMap<>(Map.of(
                    "greet", l + " hello {name}",
                    "items.one", l + " {count} item",
                    "items.other", l + " {count} items")));
        }
        return m;
    }

    @Test
    @DisplayName("Placeholders are substituted once: a value containing {name} stays literal")
    void singlePassSubstitution() {
        MessageCatalog c = MessageCatalog.of("t", sound());

        assertThat(c.text("fr", "greet", Map.of("name", "{name} l'ami"))).isEqualTo("fr hello {name} l'ami");
    }

    @Test
    @DisplayName("An apostrophe survives (no MessageFormat quoting), a missing argument stays visible")
    void apostropheAndMissingArgument() {
        Map<String, Map<String, String>> m = sound();
        m.get("fr").put("greet", "Ouvrir l'exécution de {name}");
        MessageCatalog c = MessageCatalog.of("t", m);

        assertThat(c.text("fr", "greet", Map.of("name", "x"))).isEqualTo("Ouvrir l'exécution de x");
        assertThat(c.text("fr", "greet", Map.of())).isEqualTo("Ouvrir l'exécution de {name}");
    }

    @Test
    @DisplayName("A key missing from a locale falls back to English, then to the key itself")
    void fallbacks() {
        Map<String, Map<String, String>> m = sound();
        m.get("de").remove("greet");
        MessageCatalog c = MessageCatalog.of("t", m);

        assertThat(c.text("de", "greet", Map.of("name", "x"))).isEqualTo("en hello x");
        assertThat(c.text("de", "nope")).isEqualTo("nope");
        assertThat(c.text("it", "greet", Map.of("name", "x"))).as("unsupported locale reads English")
                .isEqualTo("en hello x");
    }

    @Test
    @DisplayName("Plural forms follow CLDR: en 1 only, fr and pt 0 and 1, zh never singular")
    void pluralRules() {
        assertThat(MessageCatalog.pluralCategory("en", 1)).isEqualTo("one");
        assertThat(MessageCatalog.pluralCategory("en", 0)).isEqualTo("other");
        assertThat(MessageCatalog.pluralCategory("de", 2)).isEqualTo("other");
        assertThat(MessageCatalog.pluralCategory("es", 1)).isEqualTo("one");
        assertThat(MessageCatalog.pluralCategory("fr", 0)).isEqualTo("one");
        assertThat(MessageCatalog.pluralCategory("fr", 1)).isEqualTo("one");
        assertThat(MessageCatalog.pluralCategory("fr", 2)).isEqualTo("other");
        assertThat(MessageCatalog.pluralCategory("pt", 0)).isEqualTo("one");
        assertThat(MessageCatalog.pluralCategory("zh", 1)).isEqualTo("other");

        MessageCatalog c = MessageCatalog.of("t", sound());
        assertThat(c.plural("en", "items", 1, Map.of("count", 1))).isEqualTo("en 1 item");
        assertThat(c.plural("en", "items", 3, Map.of("count", 3))).isEqualTo("en 3 items");
        assertThat(c.plural("fr", "items", 0, Map.of("count", 0))).isEqualTo("fr 0 item");
    }

    @Test
    @DisplayName("Locale normalization: case, region and unsupported values")
    void normalize() {
        assertThat(MessageCatalog.normalizeLocale("FR")).isEqualTo("fr");
        assertThat(MessageCatalog.normalizeLocale("pt-BR")).isEqualTo("pt");
        assertThat(MessageCatalog.normalizeLocale("zh_CN")).isEqualTo("zh");
        assertThat(MessageCatalog.normalizeLocale("it")).isEqualTo("en");
        assertThat(MessageCatalog.normalizeLocale(null)).isEqualTo("en");
        assertThat(MessageCatalog.normalizeLocale(" ")).isEqualTo("en");
    }

    @Test
    @DisplayName("Links follow the frontend routing: no prefix for English, /<locale> otherwise")
    void localizedPath() {
        assertThat(MessageCatalog.localizedPath("en", "/app/x")).isEqualTo("/app/x");
        assertThat(MessageCatalog.localizedPath("fr", "/app/x")).isEqualTo("/fr/app/x");
        assertThat(MessageCatalog.localizedPath("it", "/app/x")).isEqualTo("/app/x");
        assertThat(MessageCatalog.localizedPath("zh", null)).isNull();
    }

    @Test
    @DisplayName("A sound catalog has no parity problem")
    void soundCatalogHasNoProblem() {
        assertThat(MessageCatalog.of("t", sound()).parityProblems()).isEmpty();
    }

    @Test
    @DisplayName("Parity catches a missing key, an extra key, an empty value, a dash, a placeholder drift, a lone plural form")
    void parityCatchesEveryDefect() {
        Map<String, Map<String, String>> m = sound();
        m.get("fr").remove("greet");
        m.get("de").put("extra", "x");
        m.get("es").put("greet", " ");
        m.get("pt").put("greet", "ola \u2014 {name}");
        m.get("zh").put("greet", "hello {nom}");
        for (String l : MessageCatalog.LOCALES) m.get(l).put("lonely.one", "x");

        List<String> problems = MessageCatalog.of("t", m).parityProblems();

        assertThat(problems).anyMatch(p -> p.contains("t_fr: missing greet"));
        assertThat(problems).anyMatch(p -> p.contains("t_de: extra extra"));
        assertThat(problems).anyMatch(p -> p.contains("t_es: empty greet"));
        assertThat(problems).anyMatch(p -> p.contains("t_pt: dash character in greet"));
        assertThat(problems).anyMatch(p -> p.contains("t_zh: placeholders of greet"));
        assertThat(problems).anyMatch(p -> p.contains("lonely.one has no .other form"));
    }

    @Test
    @DisplayName("A catalog missing a locale file refuses to load (a build defect, not a runtime case)")
    void missingFileRefused() {
        assertThatThrownBy(() -> MessageCatalog.load("i18n/does-not-exist"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("i18n/does-not-exist_en.properties");
    }
}
