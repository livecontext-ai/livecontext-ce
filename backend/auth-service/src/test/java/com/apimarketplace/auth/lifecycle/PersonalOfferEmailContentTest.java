package com.apimarketplace.auth.lifecycle;

import com.apimarketplace.auth.domain.PersonalOfferMatrix;
import com.apimarketplace.auth.domain.PersonalOfferPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.text.NumberFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Personal offer email: the offer in broad strokes, and a date a reader writes")
class PersonalOfferEmailContentTest {

    private static PersonalOfferMatrix row(String plan, int credits, int bonus) {
        PersonalOfferMatrix m = new PersonalOfferMatrix();
        m.setPolicyId(1L);
        m.setPlanCode(plan);
        m.setMonthlyCredits(credits);
        m.setBonusCredits(bonus);
        return m;
    }

    /** The production matrix (V552): 25 cells, three plans, ten packs, Starter up to 100,000. */
    private static List<PersonalOfferMatrix> productionMatrix() {
        int[] packs = {5_000, 10_000, 25_000, 50_000, 100_000, 250_000, 500_000, 1_000_000, 5_000_000, 10_000_000};
        List<PersonalOfferMatrix> rows = new ArrayList<>();
        for (String plan : List.of("TEAM", "PRO", "STARTER")) {
            for (int pack : packs) {
                if (plan.equals("STARTER") && pack > 100_000) continue;
                int bonus = pack >= 500_000 ? 80_000 : pack >= 250_000 ? 40_000 : pack >= 50_000 ? 8_000 : 0;
                rows.add(row(plan, pack, bonus));
            }
        }
        return rows;
    }

    private static PersonalOfferPolicy policy() {
        PersonalOfferPolicy p = new PersonalOfferPolicy();
        p.setPaygCreditsPerUsd(800);
        return p;
    }

    @Test
    @DisplayName("regression: three short steps instead of 25 plan-by-pack cells with dollar values and zero rows")
    void broadStrokes() {
        String terms = PersonalOfferEmailContent.terms(policy(), productionMatrix(), "en");

        assertThat(terms).isEqualTo("From 50,000 credits a month: +8,000 bonus credits. From 250,000: +40,000. From 500,000: +80,000.");
        assertThat(terms).doesNotContain("USD", "PRO", "STARTER", "TEAM", ": 0");
    }

    @Test
    @DisplayName("every locale writes its own words and number format")
    void locales() {
        NumberFormat fr = NumberFormat.getIntegerInstance(Locale.FRENCH);
        assertThat(PersonalOfferEmailContent.terms(policy(), productionMatrix(), "fr"))
                .isEqualTo("Dès " + fr.format(50_000) + " crédits par mois : +" + fr.format(8_000) + " crédits offerts. Dès "
                        + fr.format(250_000) + " : +" + fr.format(40_000) + ". Dès " + fr.format(500_000) + " : +" + fr.format(80_000) + ".");
        assertThat(PersonalOfferEmailContent.terms(policy(), productionMatrix(), "es")).startsWith("Desde ").contains("créditos de regalo");
        assertThat(PersonalOfferEmailContent.terms(policy(), productionMatrix(), "de")).startsWith("Ab ").contains("Bonus-Credits");
        assertThat(PersonalOfferEmailContent.terms(policy(), productionMatrix(), "pt")).startsWith("A partir de ").contains("créditos de presente");
        assertThat(PersonalOfferEmailContent.terms(policy(), productionMatrix(), "zh")).startsWith("每月 ").contains("赠送");
        // An unknown or missing locale falls back to English.
        assertThat(PersonalOfferEmailContent.terms(policy(), productionMatrix(), "it")).startsWith("From 50,000");
        assertThat(PersonalOfferEmailContent.terms(policy(), productionMatrix(), null)).startsWith("From 50,000");
        for (String l : List.of("en", "fr", "es", "de", "pt", "zh")) {
            assertThat(PersonalOfferEmailContent.terms(policy(), productionMatrix(), l)).as(l).doesNotContain("\u2014", "\u2013");
        }
    }

    /** A complete matrix (every pack each plan sells), from a rule. */
    private static List<PersonalOfferMatrix> matrix(java.util.function.BiFunction<String, Integer, Integer> bonus) {
        int[] packs = {5_000, 10_000, 25_000, 50_000, 100_000, 250_000, 500_000, 1_000_000, 5_000_000, 10_000_000};
        List<PersonalOfferMatrix> rows = new ArrayList<>();
        for (String plan : List.of("STARTER", "PRO", "TEAM")) {
            for (int pack : packs) {
                if (plan.equals("STARTER") && pack > 100_000) continue;
                rows.add(row(plan, pack, bonus.apply(plan, pack)));
            }
        }
        return rows;
    }

    @Test
    @DisplayName("regression: a step holds on every plan selling the pack, so a plan that gives less at a pack never reads as getting more")
    void stepsHoldOnEveryPlan() {
        List<PersonalOfferMatrix> rows = matrix((plan, pack) ->
                pack >= 1_000_000 ? 80_000
                        : pack == 500_000 && plan.equals("PRO") ? 50_000
                        : pack >= 50_000 ? 5_000
                        : pack == 25_000 && plan.equals("TEAM") ? 5_000 : 0);

        // Not "From 25,000" (only Team gives it there), nor "From 500,000: +50,000" (only Pro).
        assertThat(PersonalOfferEmailContent.terms(policy(), rows, "en"))
                .isEqualTo("From 50,000 credits a month: +5,000 bonus credits. From 1,000,000: +80,000.");
    }

    @Test
    @DisplayName("a step holds for every bigger pack too: a pack above that gives less lowers it")
    void stepsHoldForBiggerPacks() {
        List<PersonalOfferMatrix> rows = matrix((plan, pack) ->
                pack >= 1_000_000 ? 80_000 : pack == 500_000 ? 20_000 : pack == 250_000 ? 40_000 : pack >= 50_000 ? 8_000 : 0);

        // 250,000 gives 40,000 but 500,000 only 20,000: "From 250,000: +40,000" would over-promise.
        assertThat(PersonalOfferEmailContent.terms(policy(), rows, "en"))
                .isEqualTo("From 50,000 credits a month: +8,000 bonus credits. From 250,000: +20,000. From 1,000,000: +80,000.");
    }

    @Test
    @DisplayName("regression: a sparse matrix promises nothing it leaves out: a plan with no cell at a pack it sells gives none there")
    void sparseMatrix() {
        // Pro alone has cells: Starter and Team get no bonus with the offer anywhere.
        assertThat(PersonalOfferEmailContent.terms(policy(), List.of(row("PRO", 50_000, 8_000), row("PRO", 250_000, 40_000)), "en"))
                .isEqualTo("See the plans on the offer page.");

        // Production's matrix without its 100,000 cells: 100,000 gives nothing, so the first step is 250,000.
        List<PersonalOfferMatrix> holed = productionMatrix().stream().filter(r -> r.getMonthlyCredits() != 100_000).toList();
        assertThat(PersonalOfferEmailContent.terms(policy(), holed, "en"))
                .isEqualTo("From 250,000 credits a month: +40,000 bonus credits. From 500,000: +80,000.");
    }

    @Test
    @DisplayName("regression: the packs the offer page does not list by default (5,000,000 and 10,000,000) empty nothing when left out, and lower the steps when priced lower")
    void hiddenPacksCountOnlyWherePriced() {
        List<PersonalOfferMatrix> withoutHidden = productionMatrix().stream().filter(r -> r.getMonthlyCredits() <= 1_000_000).toList();
        assertThat(PersonalOfferEmailContent.terms(policy(), withoutHidden, "en"))
                .isEqualTo("From 50,000 credits a month: +8,000 bonus credits. From 250,000: +40,000. From 500,000: +80,000.");

        List<PersonalOfferMatrix> lowerHidden = matrix((plan, pack) -> pack >= 5_000_000 ? 1_000
                : pack >= 500_000 ? 80_000 : pack >= 250_000 ? 40_000 : pack >= 50_000 ? 8_000 : 0);
        // Still reachable (a link can carry one): "From 500,000: +80,000" would be false there.
        assertThat(PersonalOfferEmailContent.terms(policy(), lowerHidden, "en"))
                .isEqualTo("From 50,000 credits a month: +1,000 bonus credits.");
        assertThat(PersonalOfferEmailContent.OFFERED_MAX_TIER_INDEX).isEqualTo(7);
    }

    @Test
    @DisplayName("a matrix with no bonus says where to look instead of nothing; no matrix at all is refused")
    void noBonus() {
        assertThat(PersonalOfferEmailContent.terms(policy(), List.of(row("PRO", 5_000, 0)), "fr"))
                .isEqualTo("Consultez les formules sur la page de l'offre.");
        // Bonuses no plan can count on everywhere: the same pointer, never an over-promise.
        assertThat(PersonalOfferEmailContent.terms(policy(), List.of(row("PRO", 5_000, 1_000), row("TEAM", 5_000, 0)), "es"))
                .isEqualTo("Consulte los planes en la página de la oferta.");
        assertThatThrownBy(() -> PersonalOfferEmailContent.terms(policy(), List.of(), "en")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PersonalOfferEmailContent.terms(null, productionMatrix(), "en")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the subject leads with the most the offer gives: the top step's bonus, in the reader's words and number format")
    void headline() {
        List<PersonalOfferMatrix> rows = productionMatrix();
        java.util.function.Function<Locale, String> top = l -> NumberFormat.getIntegerInstance(l).format(80_000);

        assertThat(PersonalOfferEmailContent.headline(rows, "en")).isEqualTo("Up to 80,000 bonus credits with your first plan");
        assertThat(PersonalOfferEmailContent.headline(rows, "fr"))
                .isEqualTo("Jusqu'à " + top.apply(Locale.FRENCH) + " crédits offerts avec votre premier abonnement");
        assertThat(PersonalOfferEmailContent.headline(rows, "es"))
                .isEqualTo("Hasta " + top.apply(Locale.forLanguageTag("es")) + " créditos de regalo con su primer plan");
        assertThat(PersonalOfferEmailContent.headline(rows, "de"))
                .isEqualTo("Bis zu " + top.apply(Locale.GERMAN) + " Bonus-Credits zu Ihrem ersten Tarif");
        assertThat(PersonalOfferEmailContent.headline(rows, "pt"))
                .isEqualTo("Até " + top.apply(Locale.forLanguageTag("pt")) + " créditos de presente com seu primeiro plano");
        assertThat(PersonalOfferEmailContent.headline(rows, "zh")).isEqualTo("首次订阅最多赠送 " + top.apply(Locale.CHINESE) + " 积分");
        // An unknown or missing locale falls back to English.
        assertThat(PersonalOfferEmailContent.headline(rows, "it")).isEqualTo("Up to 80,000 bonus credits with your first plan");
        assertThat(PersonalOfferEmailContent.headline(rows, null)).isEqualTo("Up to 80,000 bonus credits with your first plan");
    }

    @Test
    @DisplayName("the subject's figure is the top step's, never the biggest cell: a plan or a pack that gives less lowers it")
    void headlineNeverOverPromises() {
        // Team gives 80,000 from 500,000, Pro only 50,000: a Pro client reading "up to 80,000" could not get it.
        List<PersonalOfferMatrix> proGivesLess = matrix((plan, pack) -> pack >= 500_000 ? (plan.equals("PRO") ? 50_000 : 80_000)
                : pack >= 250_000 ? 40_000 : pack >= 50_000 ? 8_000 : 0);
        assertThat(PersonalOfferEmailContent.headline(proGivesLess, "en")).isEqualTo("Up to 50,000 bonus credits with your first plan");

        // Cells of 80,000 exist, but the hidden packs give 1,000: no pack can count on more.
        List<PersonalOfferMatrix> lowerHidden = matrix((plan, pack) -> pack >= 5_000_000 ? 1_000
                : pack >= 500_000 ? 80_000 : pack >= 250_000 ? 40_000 : pack >= 50_000 ? 8_000 : 0);
        assertThat(PersonalOfferEmailContent.headline(lowerHidden, "en")).isEqualTo("Up to 1,000 bonus credits with your first plan");
    }

    @Test
    @DisplayName("an offer with no step names itself without a figure, in every locale (the template's fallback is checked against these words)")
    void headlineWithoutStep() {
        // Only Starter has a cell at 50,000: Pro and Team, which sell that pack too, get nothing there.
        List<PersonalOfferMatrix> oneCell = List.of(row("STARTER", 50_000, 8_000));

        assertThat(PersonalOfferEmailContent.headline(oneCell, "en")).isEqualTo("Your personal offer: bonus credits with your first plan");
        assertThat(PersonalOfferEmailContent.headline(oneCell, "fr"))
                .isEqualTo("Votre offre personnelle\u00A0: des crédits offerts avec votre premier abonnement");
        assertThat(PersonalOfferEmailContent.headline(oneCell, "es")).isEqualTo("Su oferta personal: créditos de regalo con su primer plan");
        assertThat(PersonalOfferEmailContent.headline(oneCell, "de")).isEqualTo("Ihr persönliches Angebot: Bonus-Credits zu Ihrem ersten Tarif");
        assertThat(PersonalOfferEmailContent.headline(oneCell, "pt")).isEqualTo("Sua oferta pessoal: créditos de presente com seu primeiro plano");
        assertThat(PersonalOfferEmailContent.headline(oneCell, "zh")).isEqualTo("您的专属优惠：首次订阅赠送积分");
        // No matrix at all reads the same.
        assertThat(PersonalOfferEmailContent.headline(List.of(), "de")).isEqualTo("Ihr persönliches Angebot: Bonus-Credits zu Ihrem ersten Tarif");
        assertThat(PersonalOfferEmailContent.headline(null, "it")).isEqualTo("Your personal offer: bonus credits with your first plan");
    }

    @Test
    @DisplayName("the deadline reads as a full date and time, in UTC, in the reader's language")
    void expiry() {
        Instant at = Instant.parse("2026-10-04T23:31:00Z");

        assertThat(PersonalOfferEmailContent.expiry(at, "en")).startsWith("October 4, 2026").endsWith(" UTC").contains("11:31");
        assertThat(PersonalOfferEmailContent.expiry(at, "fr")).startsWith("4 octobre 2026").endsWith("23:31 UTC");
        assertThat(PersonalOfferEmailContent.expiry(at, "de")).startsWith("4. Oktober 2026");
        assertThatThrownBy(() -> PersonalOfferEmailContent.expiry(null, "en")).isInstanceOf(IllegalArgumentException.class);
    }
}
