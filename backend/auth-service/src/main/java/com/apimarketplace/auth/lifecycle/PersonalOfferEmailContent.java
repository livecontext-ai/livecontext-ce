package com.apimarketplace.auth.lifecycle;

import com.apimarketplace.auth.domain.PersonalOfferMatrix;
import com.apimarketplace.auth.domain.PersonalOfferPolicy;
import com.apimarketplace.auth.service.PersonalOfferSteps;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The variables of the personal-offer emails, written for a reader, in the recipient's language.
 *
 * <p>{@link #terms} gives the offer in broad strokes: from which monthly pack each bonus starts
 * ("From 50,000 credits a month: +8,000 bonus credits. From 250,000: +40,000."), not the matrix
 * cell by cell. Each step holds on every plan that sells the pack, so the email never promises a
 * bonus one of them does not give; a plan with no cell at a pack it sells gets none there (the
 * offer page shows that pack as unavailable with the offer), whatever the admin left out. The
 * largest packs, which the offer page does not list by default, count only where the matrix
 * prices them: left out, they empty nothing; priced lower, they lower the steps.
 *
 * <p>The plan-by-plan detail is on the offer's page the email links to; an email that listed
 * every plan, pack and dollar value read as a wall of figures.
 *
 * <p>{@link #headline} is the subject line: the top step's bonus ("Up to 80,000 bonus credits with
 * your first plan"), the figure the offer page leads with.
 */
public final class PersonalOfferEmailContent {

    private static final Map<String, Locale> LOCALES = Map.of(
            "en", Locale.ENGLISH, "fr", Locale.FRENCH, "es", Locale.forLanguageTag("es"),
            "de", Locale.GERMAN, "pt", Locale.forLanguageTag("pt"), "zh", Locale.CHINESE);
    private static final char NBSP = '\u00A0';

    /** The first step of each sentence: "From {pack} credits a month: +{bonus} bonus credits." */
    private static final Map<String, String> FIRST = Map.of(
            "en", "From %s credits a month: +%s bonus credits.",
            "fr", "Dès %s crédits par mois" + NBSP + ": +%s crédits offerts.",
            "es", "Desde %s créditos al mes: +%s créditos de regalo.",
            "de", "Ab %s Credits pro Monat: +%s Bonus-Credits.",
            "pt", "A partir de %s créditos por mês: +%s créditos de presente.",
            "zh", "每月 %s 积分起：赠送 %s 积分。");
    /** Every further step, shorter: "From {pack}: +{bonus}." */
    private static final Map<String, String> NEXT = Map.of(
            "en", "From %s: +%s.",
            "fr", "Dès %s" + NBSP + ": +%s.",
            "es", "Desde %s: +%s.",
            "de", "Ab %s: +%s.",
            "pt", "A partir de %s: +%s.",
            "zh", "%s 起：赠送 %s。");
    /** An offer whose matrix gives no bonus at all (not expected from an issued policy). */
    private static final Map<String, String> NONE = Map.of(
            "en", "See the plans on the offer page.",
            "fr", "Consultez les formules sur la page de l'offre.",
            "es", "Consulte los planes en la página de la oferta.",
            "de", "Die Tarife finden Sie auf der Angebotsseite.",
            "pt", "Veja os planos na página da oferta.",
            "zh", "请在优惠页面查看套餐。");

    /** The subject line: the most the offer gives, as the offer page's headline says it. */
    private static final Map<String, String> HEADLINE = Map.of(
            "en", "Up to %s bonus credits with your first plan",
            "fr", "Jusqu'à %s crédits offerts avec votre premier abonnement",
            "es", "Hasta %s créditos de regalo con su primer plan",
            "de", "Bis zu %s Bonus-Credits zu Ihrem ersten Tarif",
            "pt", "Até %s créditos de presente com seu primeiro plano",
            "zh", "首次订阅最多赠送 %s 积分");
    /**
     * The subject line of an offer with no bonus step (not expected from an issued policy): the
     * same words as the template's fallback for the variable, used when no headline arrives.
     */
    private static final Map<String, String> HEADLINE_NONE = Map.of(
            "en", "Your personal offer: bonus credits with your first plan",
            "fr", "Votre offre personnelle" + NBSP + ": des crédits offerts avec votre premier abonnement",
            "es", "Su oferta personal: créditos de regalo con su primer plan",
            "de", "Ihr persönliches Angebot: Bonus-Credits zu Ihrem ersten Tarif",
            "pt", "Sua oferta pessoal: créditos de presente com seu primeiro plano",
            "zh", "您的专属优惠：首次订阅赠送积分");

    /** The largest pack the offer page lists by default (see {@link PersonalOfferSteps}). */
    static final int OFFERED_MAX_TIER_INDEX = PersonalOfferSteps.OFFERED_MAX_TIER_INDEX;

    private PersonalOfferEmailContent() { }

    /** The offer's deadline, as a reader writes a date: "October 4, 2026 at 11:31 PM UTC", "4 octobre 2026 à 23:31 UTC". */
    public static String expiry(Instant expiresAt, String rawLocale) {
        if (expiresAt == null) throw new IllegalArgumentException("expiresAt required");
        Locale locale = locale(rawLocale);
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT)
                .withLocale(locale).withZone(ZoneOffset.UTC).format(expiresAt) + " UTC";
    }

    /**
     * The offer in broad strokes, smallest pack first: "From {pack}: +{bonus}" for each pack where
     * the bonus every plan and every bigger pack gives goes up. A step is a promise ("from this
     * pack, at least this"), so it takes the smallest bonus of the plans selling that pack and of
     * all the packs above it: a plan or a bigger pack that gives less lowers it, never the reverse.
     * Packs with no bonus are left out: the email says what the offer gives, not where it gives
     * nothing.
     */
    public static String terms(PersonalOfferPolicy policy, List<PersonalOfferMatrix> rows, String rawLocale) {
        if (policy == null || rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("issued policy and matrix required");
        }
        String code = rawLocale != null && LOCALES.containsKey(rawLocale) ? rawLocale : "en";
        NumberFormat count = NumberFormat.getIntegerInstance(LOCALES.get(code));
        // The same steps the offer page draws as its ladder.
        List<String> steps = new ArrayList<>();
        for (PersonalOfferSteps.Step step : PersonalOfferSteps.of(rows)) {
            String template = steps.isEmpty() ? FIRST.get(code) : NEXT.get(code);
            steps.add(String.format(template, count.format(step.monthlyCredits()), count.format(step.bonusCredits())));
        }
        return steps.isEmpty() ? NONE.get(code) : String.join(" ", steps);
    }

    /**
     * The email's subject: "Up to {bonus} bonus credits with your first plan", where the bonus is
     * the top step's, the most the offer promises (the headline of the offer page, which draws the
     * same steps). Without a step it names the offer without a figure.
     */
    public static String headline(List<PersonalOfferMatrix> rows, String rawLocale) {
        String code = rawLocale != null && LOCALES.containsKey(rawLocale) ? rawLocale : "en";
        List<PersonalOfferSteps.Step> steps = PersonalOfferSteps.of(rows);
        if (steps.isEmpty()) return HEADLINE_NONE.get(code);
        int top = steps.get(steps.size() - 1).bonusCredits();
        return String.format(HEADLINE.get(code), NumberFormat.getIntegerInstance(LOCALES.get(code)).format(top));
    }

    private static Locale locale(String code) {
        return code == null ? Locale.ENGLISH : LOCALES.getOrDefault(code, Locale.ENGLISH);
    }
}
