package com.apimarketplace.auth.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.text.NumberFormat;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The words a lifecycle email shows that the app owns: trophy, tier and family names, month names.
 * Resend cannot look them up, so auth-service puts them, already in the recipient's language,
 * into the event payload.
 *
 * <p>Read once from {@code lifecycle/lifecycle-labels.json}. Badge, tier and family names are copies
 * of {@code frontend/messages/<locale>.json}, so an email names a trophy exactly as the app
 * does; {@code LifecycleLabelsParityTest} fails when the two drift, or when a badge of the
 * orchestrator catalog has no name here. An unknown locale reads English.
 */
@Component
public class LifecycleLabels {

    static final String RESOURCE = "lifecycle/lifecycle-labels.json";
    static final String DEFAULT_LOCALE = "en";

    record LocaleLabels(String monthPattern, List<String> months, Map<String, String> tiers,
                        Map<String, String> families, Map<String, String> badges) {
    }

    private final Map<String, LocaleLabels> byLocale;

    public LifecycleLabels() {
        this(load());
    }

    LifecycleLabels(Map<String, LocaleLabels> byLocale) {
        if (!byLocale.containsKey(DEFAULT_LOCALE)) {
            throw new IllegalStateException(RESOURCE + " has no " + DEFAULT_LOCALE + " labels");
        }
        this.byLocale = Map.copyOf(byLocale);
    }

    /** The locales the file declares. */
    public Set<String> locales() {
        return byLocale.keySet();
    }

    public boolean isKnownBadge(String code) {
        return code != null && labels(DEFAULT_LOCALE).badges().containsKey(code);
    }

    public boolean isKnownTier(String tier) {
        return tier != null && labels(DEFAULT_LOCALE).tiers().containsKey(tier);
    }

    public boolean isKnownFamily(String family) {
        return family != null && labels(DEFAULT_LOCALE).families().containsKey(family);
    }

    /** The trophy family's display name in {@code locale} ("Audience", "Reach"); the family itself when unknown. */
    public String familyName(String family, String locale) {
        String name = labels(locale).families().get(family);
        if (name == null) name = labels(DEFAULT_LOCALE).families().get(family);
        return name != null ? name : family;
    }

    /** The trophy's display name in {@code locale}; the code itself when unknown. */
    public String badgeName(String code, String locale) {
        String name = labels(locale).badges().get(code);
        if (name == null) name = labels(DEFAULT_LOCALE).badges().get(code);
        return name != null ? name : code;
    }

    /** The tier's display name in {@code locale} ("Or", "Gold"); the tier itself when unknown. */
    public String tierName(String tier, String locale) {
        String name = labels(locale).tiers().get(tier);
        if (name == null) name = labels(DEFAULT_LOCALE).tiers().get(tier);
        return name != null ? name : tier;
    }

    /** "septembre 2026", "September 2026", "septiembre de 2026", "2026年9月". */
    public String monthLabel(YearMonth month, String locale) {
        LocaleLabels l = labels(locale);
        return l.monthPattern()
                .replace("{month}", l.months().get(month.getMonthValue() - 1))
                .replace("{year}", String.valueOf(month.getYear()));
    }

    /** A count with the locale's digit grouping ("1 234" in French, "1,234" in English). */
    public String count(long value, String locale) {
        return NumberFormat.getIntegerInstance(Locale.forLanguageTag(resolve(locale))).format(value);
    }

    Map<String, String> badgeNames(String locale) {
        return labels(locale).badges();
    }

    Map<String, String> tierNames(String locale) {
        return labels(locale).tiers();
    }

    Map<String, String> familyNames(String locale) {
        return labels(locale).families();
    }

    private String resolve(String locale) {
        return locale != null && byLocale.containsKey(locale) ? locale : DEFAULT_LOCALE;
    }

    private LocaleLabels labels(String locale) {
        return byLocale.get(resolve(locale));
    }

    private static Map<String, LocaleLabels> load() {
        try (InputStream in = LifecycleLabels.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("missing classpath resource " + RESOURCE);
            return parse(new ObjectMapper().readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }

    static Map<String, LocaleLabels> parse(JsonNode root) {
        Map<String, LocaleLabels> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> locales = root.path("locales").fields();
        while (locales.hasNext()) {
            Map.Entry<String, JsonNode> e = locales.next();
            JsonNode node = e.getValue();
            List<String> months = new ArrayList<>();
            node.path("months").forEach(m -> months.add(m.asText()));
            if (months.size() != 12) {
                throw new IllegalStateException(RESOURCE + ": locale " + e.getKey() + " needs 12 month names");
            }
            out.put(e.getKey(), new LocaleLabels(node.path("month_pattern").asText(),
                    Collections.unmodifiableList(months), strings(node.path("tiers")), strings(node.path("families")),
                    strings(node.path("badges"))));
        }
        return out;
    }

    private static Map<String, String> strings(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(f -> out.put(f.getKey(), f.getValue().asText()));
        return Collections.unmodifiableMap(out);
    }
}
