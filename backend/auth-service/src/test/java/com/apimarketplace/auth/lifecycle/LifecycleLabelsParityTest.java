package com.apimarketplace.auth.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trophy names an email shows are copies of the app's own. This pins both ends: every
 * badge of the orchestrator {@code BadgeCatalog} (read from its source, auth-service cannot
 * depend on orchestrator) has a name in every locale, and every name is exactly the one
 * {@code frontend/messages/<locale>.json} shows for the same code.
 */
@DisplayName("Lifecycle labels - parity with the badge catalog and the app's translations")
class LifecycleLabelsParityTest {

    static final List<String> LOCALES = List.of("en", "fr", "es", "de", "pt", "zh");
    private static final String BADGE_CATALOG =
            "orchestrator-service/src/main/java/com/apimarketplace/orchestrator/services/badge/BadgeCatalog.java";
    private static final Pattern DEF = Pattern.compile("def\\(\"([a-z0-9_]+)\",\\s*BadgeFamily\\.(\\w+),\\s*BadgeTier\\.(\\w+)");

    private final LifecycleLabels labels = new LifecycleLabels();

    /** Walks up from the module to the repo root, like the migration parity tests. */
    private static Path repoFile(String relative) {
        for (Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve(relative))) return p.resolve(relative);
            if (Files.isRegularFile(p.resolve("backend").resolve(relative))) return p.resolve("backend").resolve(relative);
        }
        throw new IllegalStateException("cannot locate " + relative);
    }

    /** code -> {family, tier}, parsed from the catalog's {@code def(...)} lines. */
    private static Map<String, String[]> catalogBadges() throws Exception {
        String source = Files.readString(repoFile(BADGE_CATALOG), StandardCharsets.UTF_8);
        Map<String, String[]> out = new java.util.LinkedHashMap<>();
        Matcher m = DEF.matcher(source);
        while (m.find()) out.put(m.group(1), new String[]{m.group(2), m.group(3)});
        return out;
    }

    @Test
    @DisplayName("the six locales are declared, no more, no less")
    void sixLocales() {
        assertThat(labels.locales()).containsExactlyInAnyOrderElementsOf(LOCALES);
    }

    @Test
    @DisplayName("every badge of the orchestrator catalog has a non-empty name in every locale")
    void everyCatalogBadgeIsNamedEverywhere() throws Exception {
        Map<String, String[]> catalog = catalogBadges();
        assertThat(catalog).as("badge definitions parsed from BadgeCatalog.java").hasSizeGreaterThan(40);
        for (String locale : LOCALES) {
            Map<String, String> names = labels.badgeNames(locale);
            assertThat(names.keySet()).as("badge codes in %s", locale).containsExactlyInAnyOrderElementsOf(catalog.keySet());
            names.forEach((code, name) -> assertThat(name).as("%s name of %s", locale, code).isNotBlank());
        }
    }

    @Test
    @DisplayName("every tier and family used by the catalog has a name in every locale")
    void everyTierAndFamilyIsNamedEverywhere() throws Exception {
        Set<String> families = new LinkedHashSet<>();
        Set<String> tiers = new LinkedHashSet<>();
        catalogBadges().values().forEach(ft -> {
            families.add(ft[0]);
            tiers.add(ft[1]);
        });
        for (String locale : LOCALES) {
            assertThat(labels.tierNames(locale).keySet()).as("tiers in %s", locale).containsAll(tiers);
            assertThat(labels.familyNames(locale).keySet()).as("families in %s", locale).containsAll(families);
            labels.familyNames(locale).forEach((f, name) -> assertThat(name).as("%s %s", locale, f).isNotBlank());
        }
    }

    @Test
    @DisplayName("names are exactly the app's (frontend/messages), in every locale")
    void namesMatchTheApp() throws Exception {
        ObjectMapper json = new ObjectMapper();
        for (String locale : LOCALES) {
            JsonNode badges = json.readTree(Files.readString(
                    repoFile("frontend/messages/" + locale + ".json"), StandardCharsets.UTF_8)).path("badges");
            labels.badgeNames(locale).forEach((code, name) ->
                    assertThat(name).as("%s %s", locale, code).isEqualTo(badges.path("item").path(code).path("name").asText()));
            labels.tierNames(locale).forEach((tier, name) ->
                    assertThat(name).as("%s %s", locale, tier).isEqualTo(badges.path("tier").path(tier).asText()));
            labels.familyNames(locale).forEach((family, name) ->
                    assertThat(name).as("%s %s", locale, family).isEqualTo(badges.path("family").path(family).asText()));
        }
    }

    @Test
    @DisplayName("no em-dash or en-dash anywhere in the labels file")
    void noDashes() throws Exception {
        String raw = new String(LifecycleLabels.class.getClassLoader().getResourceAsStream(LifecycleLabels.RESOURCE)
                .readAllBytes(), StandardCharsets.UTF_8);
        assertThat(raw).doesNotContain(String.valueOf((char) 0x2014)).doesNotContain(String.valueOf((char) 0x2013));
    }
}
