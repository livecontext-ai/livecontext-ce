package com.apimarketplace.auth.lifecycle;

import com.apimarketplace.auth.domain.PersonalOfferMatrix;
import com.apimarketplace.auth.domain.PersonalOfferPolicy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Factual, localized variables for the personal-offer templates. */
public final class PersonalOfferEmailContent {

    private static final Map<String, Locale> LOCALES = Map.of(
            "en", Locale.ENGLISH, "fr", Locale.FRENCH, "es", Locale.forLanguageTag("es"),
            "de", Locale.GERMAN, "pt", Locale.forLanguageTag("pt"), "zh", Locale.CHINESE);
    private static final Map<String, String> CREDIT_UNITS = Map.of(
            "en", "credits/month", "fr", "crédits/mois", "es", "créditos/mes",
            "de", "Credits/Monat", "pt", "créditos/mês", "zh", "积分/月");
    private static final Map<String, String> BONUS_UNITS = Map.of(
            "en", "USD PAYG bonus", "fr", "USD de bonus PAYG", "es", "USD de bonificación PAYG",
            "de", "USD PAYG-Bonus", "pt", "USD de bônus PAYG", "zh", "美元 PAYG 奖励");

    private PersonalOfferEmailContent() { }

    public static String expiry(Instant expiresAt, String rawLocale) {
        if (expiresAt == null) throw new IllegalArgumentException("expiresAt required");
        Locale locale = locale(rawLocale);
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale).withZone(ZoneOffset.UTC).format(expiresAt) + " UTC";
    }

    public static String terms(PersonalOfferPolicy policy, List<PersonalOfferMatrix> rows, String rawLocale) {
        if (policy == null || policy.getPaygCreditsPerUsd() <= 0 || rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("issued policy and matrix required");
        }
        String code = rawLocale != null && LOCALES.containsKey(rawLocale) ? rawLocale : "en";
        Locale locale = LOCALES.get(code);
        var count = java.text.NumberFormat.getIntegerInstance(locale);
        var usd = java.text.NumberFormat.getNumberInstance(locale);
        usd.setMaximumFractionDigits(2);
        usd.setMinimumFractionDigits(0);
        String result = rows.stream()
                .sorted(Comparator.comparing(PersonalOfferMatrix::getPlanCode)
                        .thenComparingInt(PersonalOfferMatrix::getMonthlyCredits))
                .map(row -> {
                    BigDecimal value = BigDecimal.valueOf(row.getBonusCredits())
                            .divide(BigDecimal.valueOf(policy.getPaygCreditsPerUsd()), 2, RoundingMode.HALF_UP);
                    return row.getPlanCode() + " " + count.format(row.getMonthlyCredits()) + " "
                            + CREDIT_UNITS.get(code) + ": " + usd.format(value) + " " + BONUS_UNITS.get(code);
                })
                .collect(Collectors.joining("; "));
        if (result.isBlank() || result.length() > 3000) throw new IllegalArgumentException("email terms too long");
        return result;
    }

    private static Locale locale(String code) {
        return code == null ? Locale.ENGLISH : LOCALES.getOrDefault(code, Locale.ENGLISH);
    }
}
