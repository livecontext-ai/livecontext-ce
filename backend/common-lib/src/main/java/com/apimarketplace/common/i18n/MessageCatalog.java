package com.apimarketplace.common.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side texts in the six app locales, for messages that leave the app (emails, chat
 * notices) and are therefore rendered by a backend service rather than by next-intl.
 *
 * <p>One UTF-8 properties file per locale, {@code <baseName>_<locale>.properties} on the
 * classpath. Placeholders are named, {@code {name}}, and substituted in ONE pass over the
 * template, so a value that itself contains {@code {name}} stays literal text. No
 * {@link java.text.MessageFormat}: its quote rules would silently eat the apostrophe of every
 * French sentence.
 *
 * <p>Plurals: a key ending in {@code .one} / {@code .other}, chosen by the CLDR cardinal rule of
 * the locale for the integer counts these messages use ({@link #pluralCategory}).
 *
 * <p>A key missing from a locale falls back to English, then to the key itself: a message is
 * never refused over a missing translation. {@link #parityProblems()} is what keeps that
 * fallback from ever being needed, and every catalog pins it in a test.
 */
public final class MessageCatalog {

    /** The app locales, English first (the reference and the fallback). Mirrors frontend i18n/routing.ts. */
    public static final List<String> LOCALES = List.of("en", "fr", "de", "es", "pt", "zh");
    public static final String DEFAULT_LOCALE = "en";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)\\}");

    private final String baseName;
    private final Map<String, Map<String, String>> byLocale;

    private MessageCatalog(String baseName, Map<String, Map<String, String>> byLocale) {
        this.baseName = baseName;
        this.byLocale = byLocale;
    }

    /**
     * Loads every locale of {@code baseName} (for example {@code "i18n/notification-messages"}).
     *
     * @throws IllegalStateException when a locale file is missing or unreadable: a catalog
     *                               without one of its locales is a build defect, not a runtime case
     */
    public static MessageCatalog load(String baseName) {
        Map<String, Map<String, String>> byLocale = new LinkedHashMap<>();
        ClassLoader loader = MessageCatalog.class.getClassLoader();
        for (String locale : LOCALES) {
            String resource = baseName + "_" + locale + ".properties";
            try (InputStream in = loader.getResourceAsStream(resource)) {
                if (in == null) throw new IllegalStateException("Missing message catalog " + resource);
                Properties props = new Properties();
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                Map<String, String> entries = new LinkedHashMap<>();
                for (String key : new TreeSet<>(props.stringPropertyNames())) {
                    entries.put(key, props.getProperty(key));
                }
                byLocale.put(locale, Collections.unmodifiableMap(entries));
            } catch (IOException e) {
                throw new IllegalStateException("Unreadable message catalog " + resource, e);
            }
        }
        return new MessageCatalog(baseName, Collections.unmodifiableMap(byLocale));
    }

    /** A catalog from in-memory entries, one map per locale of {@link #LOCALES} (tests). */
    static MessageCatalog of(String baseName, Map<String, Map<String, String>> byLocale) {
        Map<String, Map<String, String>> copy = new LinkedHashMap<>();
        for (String locale : LOCALES) copy.put(locale, Map.copyOf(byLocale.getOrDefault(locale, Map.of())));
        return new MessageCatalog(baseName, Collections.unmodifiableMap(copy));
    }

    /** One of {@link #LOCALES} (case-insensitive, region dropped: {@code pt-BR} is {@code pt}), else English. */
    public static String normalizeLocale(String raw) {
        if (raw == null) return DEFAULT_LOCALE;
        String v = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        int dash = v.indexOf('-');
        if (dash > 0) v = v.substring(0, dash);
        return LOCALES.contains(v) ? v : DEFAULT_LOCALE;
    }

    /**
     * An in-app path as the frontend routes it for this locale: English has no prefix
     * ({@code localePrefix: 'as-needed'}), every other locale gets {@code /<locale>}.
     */
    public static String localizedPath(String locale, String path) {
        if (path == null) return null;
        String l = normalizeLocale(locale);
        return DEFAULT_LOCALE.equals(l) ? path : "/" + l + path;
    }

    /**
     * CLDR cardinal category, {@code "one"} or {@code "other"}, for a non-negative integer:
     * English, German and Spanish say "one" for 1 only; French and Portuguese for 0 and 1;
     * Chinese has no grammatical plural.
     */
    public static String pluralCategory(String locale, long n) {
        return switch (normalizeLocale(locale)) {
            case "zh" -> "other";
            case "fr", "pt" -> (n == 0 || n == 1) ? "one" : "other";
            default -> n == 1 ? "one" : "other";
        };
    }

    /** The text of {@code key} in {@code locale}, placeholders substituted from {@code args}. */
    public String text(String locale, String key, Map<String, ?> args) {
        return substitute(raw(locale, key), args);
    }

    public String text(String locale, String key) {
        return text(locale, key, Map.of());
    }

    /** {@code key.one} or {@code key.other} for {@code count}; {@code args} carries the placeholders (count included). */
    public String plural(String locale, String key, long count, Map<String, ?> args) {
        return text(locale, key + "." + pluralCategory(locale, count), args);
    }

    /** The template, without substitution: the locale's, else English's, else the key. */
    public String raw(String locale, String key) {
        String v = byLocale.get(normalizeLocale(locale)).get(key);
        if (v == null || v.isEmpty()) v = byLocale.get(DEFAULT_LOCALE).get(key);
        return v != null ? v : key;
    }

    /** Every key of {@code locale}, sorted. */
    public Set<String> keys(String locale) {
        return byLocale.get(normalizeLocale(locale)).keySet();
    }

    /**
     * Everything that would make a locale fall back or read wrong, empty when the catalog is
     * sound: a key missing from or extra in a locale (against English), an empty value, a
     * placeholder set that differs from English's, an em or en dash (banned product-wide), a
     * plural key without both of its {@code .one} and {@code .other} forms.
     */
    public List<String> parityProblems() {
        List<String> problems = new ArrayList<>();
        Map<String, String> reference = byLocale.get(DEFAULT_LOCALE);
        for (String key : reference.keySet()) {
            if (key.endsWith(".one") && !reference.containsKey(key.substring(0, key.length() - 4) + ".other")) {
                problems.add(baseName + ": " + key + " has no .other form");
            }
            if (key.endsWith(".other") && !reference.containsKey(key.substring(0, key.length() - 6) + ".one")) {
                problems.add(baseName + ": " + key + " has no .one form");
            }
        }
        for (String locale : LOCALES) {
            Map<String, String> entries = byLocale.get(locale);
            for (String key : reference.keySet()) {
                if (!entries.containsKey(key)) problems.add(baseName + "_" + locale + ": missing " + key);
            }
            for (Map.Entry<String, String> e : entries.entrySet()) {
                String key = e.getKey();
                String value = e.getValue();
                if (!reference.containsKey(key)) problems.add(baseName + "_" + locale + ": extra " + key);
                if (value == null || value.isBlank()) problems.add(baseName + "_" + locale + ": empty " + key);
                if (value != null && (value.indexOf('\u2014') >= 0 || value.indexOf('\u2013') >= 0)) {
                    problems.add(baseName + "_" + locale + ": dash character in " + key);
                }
                if (value != null && reference.containsKey(key)
                        && !placeholders(value).equals(placeholders(reference.get(key)))) {
                    problems.add(baseName + "_" + locale + ": placeholders of " + key + " " + placeholders(value)
                            + " differ from en " + placeholders(reference.get(key)));
                }
            }
        }
        return problems;
    }

    static Set<String> placeholders(String template) {
        Set<String> out = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static String substitute(String template, Map<String, ?> args) {
        if (args == null || args.isEmpty() || template.indexOf('{') < 0) return template;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object value = args.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? String.valueOf(value) : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }
}
