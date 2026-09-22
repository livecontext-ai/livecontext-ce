package com.apimarketplace.catalog.service.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every descriptor the platform SHIPS can actually be called.
 *
 * <p>Onboarding a generation provider is a JSON block plus an import, with no
 * Java to write. The price of that is that a mistake in the JSON surfaces at
 * the worst possible moment: the authoring gate is a Python script nobody runs
 * on a whim, {@code GenerationRegistry} skips an unparseable row with a log
 * line rather than failing, and a model that cannot state the size of a call is
 * only WARNED about at snapshot time. The reader finds out when their
 * generation is refused.
 *
 * <p>So this reads the artefact a fresh install actually boots with and puts
 * every block through the same parser the import and the runtime use. It is the
 * cheapest possible guard on the whole catalogue at once, and it grows by
 * itself: a provider added to {@code scripts/api-migrations/} lands in the seed
 * and is covered here without anybody remembering to extend a list.
 */
@DisplayName("the shipped generation seed is callable, model by model")
class GenerationSeedDescriptorsAreCallableTest {

    private static final String SEED = "catalog-seeds/generation-seed.json";

    /** One shipped endpoint: where it came from, and what it declares. */
    private record SeededEndpoint(String source, String endpoint, GenerationSpec spec) {}

    private static List<SeededEndpoint> shipped() throws Exception {
        try (InputStream in = GenerationSeedDescriptorsAreCallableTest.class
                .getClassLoader().getResourceAsStream(SEED)) {
            assertThat(in).as("the CE generation seed must ship on the classpath").isNotNull();
            JsonNode root = new ObjectMapper().readTree(in);
            List<SeededEndpoint> out = new ArrayList<>();
            for (JsonNode endpoint : root.path("endpoints")) {
                String source = endpoint.path("sourceFile").asText("?")
                        + ":" + endpoint.path("endpointName").asText("?");
                // Parsing IS the assertion: a malformed block throws with the
                // offending field named, which is the same failure the import
                // would produce, minus the trip to production.
                GenerationSpec spec = GenerationSpec.parse(endpoint.path("generationSpec"), source)
                        .orElseThrow(() -> new AssertionError(
                                source + " is listed in the seed but declares no generation block"));
                out.add(new SeededEndpoint(source, endpoint.path("endpointName").asText("?"), spec));
            }
            return out;
        }
    }

    @Test
    @DisplayName("every block parses, which is what the import would refuse and the runtime would skip")
    void everyBlockParses() throws Exception {
        List<SeededEndpoint> endpoints = shipped();

        assertThat(endpoints)
                .as("the seed must ship at least the endpoints the platform advertises")
                .isNotEmpty();
        assertThat(endpoints).allSatisfy(e ->
                assertThat(e.spec().models()).as(e.source()).isNotEmpty());
    }

    @Test
    @DisplayName("every model can state the size of a call, or its price multiplies a number nobody supplied")
    void everyModelCanStateItsSize() throws Exception {
        // A model priced per second, per image or per character has to be able
        // to say how big each call is: the caller states it, the model defaults
        // it, or the model refuses the call naming it. A model that does none of
        // those is listed, quoted, and then refuses EVERY call that omits the
        // parameter - a failure the registry only writes to a log.
        List<String> unsized = new ArrayList<>();
        for (SeededEndpoint endpoint : shipped()) {
            for (GenerationSpec.Model model : endpoint.spec().models()) {
                if (!model.canAlwaysStateItsSize()) {
                    unsized.add(endpoint.source() + " -> " + model.id()
                            + " (priced per " + model.price().unit()
                            + ", measured by '" + model.measuringParam()
                            + "', which it neither defaults nor requires)");
                }
            }
        }

        assertThat(unsized)
                .as("give the measuring parameter an 'allowed' list of sizes, or list it as required")
                .isEmpty();
    }

    @Test
    @DisplayName("model ids are unique across the whole catalogue, since one shadows the other in the registry")
    void modelIdsAreGloballyUnique() throws Exception {
        // The registry indexes by model id across every endpoint and keeps the
        // FIRST registration on a clash, so a duplicate does not fail: it
        // silently makes one provider's model unreachable, and the reader sees
        // the other one's price and limits under the id they asked for.
        Map<String, String> owner = new LinkedHashMap<>();
        List<String> clashes = new ArrayList<>();
        for (SeededEndpoint endpoint : shipped()) {
            for (GenerationSpec.Model model : endpoint.spec().models()) {
                String previous = owner.putIfAbsent(model.id(), endpoint.source());
                if (previous != null) {
                    clashes.add("'" + model.id() + "' claimed by " + previous
                            + " and " + endpoint.source());
                }
            }
        }

        assertThat(clashes).isEmpty();
    }

    @Test
    @DisplayName("every model builds a real request from a plain call, and the provider gets the model it is priced for")
    void everyModelProjectsAPlainCall() throws Exception {
        // The descriptor is only half a promise until something projects it. A
        // path typo, a required parameter nobody can satisfy or a model selector
        // that never reaches the body all produce the same outcome: the call is
        // dispatched, CHARGED, and comes back with nothing usable. Building each
        // model here costs nothing and is the only check that reads the
        // descriptor the way a real call does.
        for (SeededEndpoint endpoint : shipped()) {
            for (GenerationSpec.Model model : endpoint.spec().models()) {
                Map<String, Object> unified = new LinkedHashMap<>();
                // What a caller minimally supplies: the instruction, plus
                // whatever this model refuses to run without.
                if (model.accepts("prompt")) {
                    unified.put("prompt", "a paper boat drifting down a rain gutter");
                }
                for (String required : model.required()) {
                    if (unified.containsKey(required)) continue;
                    unified.put(required, plausibleValue(model, required));
                }

                GenerationRequestBuilder.Built built =
                        GenerationRequestBuilder.build(endpoint.spec(), model, unified);

                String where = endpoint.source() + " -> " + model.id();
                assertThat(built.errors()).as(where).isEmpty();
                assertThat(built.params()).as(where).isNotEmpty();

                // The value the caller is BILLED for is the model they asked
                // for, so the selector has to be in the body the provider reads.
                // A modelParam that never lands is how a cheap model is quoted
                // and an expensive one runs.
                if (endpoint.spec().sendsModelParam()) {
                    assertThat(GenerationRequestBuilder.getByPath(
                            built.params(), endpoint.spec().modelParam()))
                            .as(where + " must send its model selector")
                            .isEqualTo(model.upstream());
                }
                if (model.accepts("prompt")) {
                    assertThat(GenerationRequestBuilder.getByPath(
                            built.params(), endpoint.spec().paramMap().get("prompt").path()))
                            .as(where + " must carry the prompt where it says it does")
                            .isEqualTo("a paper boat drifting down a rain gutter");
                }
            }
        }
    }

    @Test
    @DisplayName("every shipped model is measured the SAME by the direct path and by the relay")
    void directAndRelayedMeasurementAgreeForEveryModel() throws Exception {
        // The one guarantee that has to hold for EVERY provider, not just the one the feature was
        // written against.
        //
        // A direct call is measured from the unified parameters the caller typed. A call relayed
        // from a self-hosted install is measured by the cloud from the provider-shaped BODY it
        // receives, because an install that could state its own size or its own factor could state
        // a smaller one. Those are two different readers of two different shapes, and every
        // provider's shape is its own: Seedance carries its opening frame, its closing frame and
        // its references in ONE array told apart by markers; xAI gives each slot a path of its own;
        // Runway requires its source image; Higgsfield pins the duration so there is no size
        // parameter at all; HeyGen is measured in characters of script.
        //
        // If those two readings ever disagree, the same generation costs one amount locally and
        // another through the relay, and nothing anywhere reports it. Asserting it per shipped
        // model is what makes "all the video providers work" a checkable statement instead of a
        // claim about the one that was tested by hand.
        for (SeededEndpoint endpoint : shipped()) {
            for (GenerationSpec.Model model : endpoint.spec().models()) {
                Map<String, Object> unified = new LinkedHashMap<>();
                if (model.accepts("prompt")) {
                    unified.put("prompt", "a paper boat drifting down a rain gutter");
                }
                for (String required : model.required()) {
                    if (unified.containsKey(required)) continue;
                    unified.put(required, plausibleValue(model, required));
                }
                // NO files, deliberately. This builder defers file slots to GenerationInputResolver,
                // which needs a tenant and real storage keys to inline the bytes, so a file put in
                // the unified map here moves the DIRECT factor and never reaches the body the relay
                // reads - which looks exactly like a relay that lost the files. The file half of
                // this parity is covered where it can be exercised honestly:
                // RelayedFactorOnGappedSlotTest (a slot whose empties were pruned) and
                // RelayedGenerationMeasurementTest (markers, gaps, and an over-full slot).
                //
                // What is left is what only a per-model sweep can check: that every shipped
                // descriptor is READ the same by both sides - the right model out of a group that
                // shares one upstream name, the size, the unit it is counted in, and the factor.

                GenerationRequestBuilder.Built built =
                        GenerationRequestBuilder.build(endpoint.spec(), model, unified);
                String where = endpoint.source() + " -> " + model.id();
                assertThat(built.errors()).as(where).isEmpty();

                // The relay reads the body the direct path just produced, which is exactly what it
                // would receive from an install running this same call.
                RelayedGenerationMeasurement.Measured relayed =
                        RelayedGenerationMeasurement.measure(endpoint.spec(), built.params());

                assertThat(relayed.modelId())
                        .as(where + ": the relay must price the model the body names")
                        .isEqualTo(model.id());
                assertThat(relayed.quantityUnit())
                        .as(where + ": a size read in another unit is a rate applied to the wrong count")
                        .isEqualTo(built.quantityUnit());
                if (built.quantity() == null) {
                    assertThat(relayed.quantity()).as(where).isNull();
                } else {
                    assertThat(relayed.quantity())
                            .as(where + ": direct billed " + built.quantity()
                                    + ", relayed billed " + relayed.quantity())
                            .isEqualByComparingTo(built.quantity());
                }
                // Compared as an EFFECT, with absent read as 1, because the two paths spell "no
                // factor" differently on purpose. The direct path answers BigDecimal.ONE; the relay
                // answers null so the query parameter is omitted entirely and every lookup that
                // predates modifiers stays byte-identical on the wire. Both reach MarkupPolicy as a
                // no-op, so the charge is the same - but the two spellings are a real difference
                // between the readers, and a test that compared them literally would fail on all
                // 100-odd unmodulated models while saying nothing about money.
                java.math.BigDecimal relayedFactor = relayed.priceMultiplier() == null
                        ? java.math.BigDecimal.ONE : relayed.priceMultiplier();
                assertThat(relayedFactor)
                        .as(where + ": direct factor " + built.priceMultiplier()
                                + ", relayed factor " + relayed.priceMultiplier())
                        .isEqualByComparingTo(built.priceMultiplier());
            }
        }
    }

    /**
     * A value this model would accept for one of its required parameters.
     *
     * <p>Taken from the model's OWN declared constraint whenever it has one, so
     * the test never invents a value the descriptor would refuse and never has
     * to be updated when a provider changes its allowed sizes.
     */
    private static Object plausibleValue(GenerationSpec.Model model, String param) {
        GenerationSpec.Constraint constraint = model.constraints().get(param);
        if (constraint != null && !constraint.allowed().isEmpty()) {
            return constraint.allowed().get(0);
        }
        if (constraint != null && constraint.min() != null) {
            return constraint.min();
        }
        return "1";
    }

    @Test
    @DisplayName("no shipped model lets a caller ask for more than one asset, since only one is ever stored")
    void noModelSellsMoreAssetsThanItReturns() throws Exception {
        // One call fetches and stores exactly ONE asset, while a price unit of
        // 'image' multiplies `n`. A model that accepts a bigger `n` is charged
        // for every one of them and hands back the first, so the customer pays
        // for assets that never existed as far as they can tell.
        //
        // The authoring gate refuses this, but only for descriptors that go
        // through it. This asserts it on the artefact an install actually boots
        // with, which is also the shape a signed catalog bundle carries.
        List<String> oversold = new ArrayList<>();
        for (SeededEndpoint endpoint : shipped()) {
            for (GenerationSpec.Model model : endpoint.spec().models()) {
                if (!model.accepts("n")) continue;
                GenerationSpec.Constraint limit = model.constraints().get("n");
                boolean cappedAtOne = limit != null
                        && (List.of(1).equals(limit.allowed())
                            || (limit.max() != null && limit.max().intValueExact() == 1));
                if (!cappedAtOne) {
                    oversold.add(endpoint.source() + " -> " + model.id());
                }
            }
        }

        assertThat(oversold)
                .as("cap n at 1, or drop it and price the model per 'call'")
                .isEmpty();
    }

    @Test
    @DisplayName("every declared capability can actually be sent, so an accepted parameter is never dropped")
    void everyCapabilityHasAnUpstreamMapping() throws Exception {
        // A capability with no paramMap entry is a parameter the surfaces offer,
        // the validator accepts and the provider never sees. Parsing already
        // refuses it, so this exists to state the invariant on the SHIPPED set:
        // it is what makes "the model accepts it" mean "the model receives it".
        for (SeededEndpoint endpoint : shipped()) {
            for (GenerationSpec.Model model : endpoint.spec().models()) {
                assertThat(endpoint.spec().paramMap().keySet())
                        .as(endpoint.source() + " -> " + model.id())
                        .containsAll(model.capabilities());
            }
        }
    }

    @Test
    @DisplayName("regression: multilingual v2 does not offer a language the provider ignores")
    void multilingualV2DoesNotOfferALanguage() throws Exception {
        // ElevenLabs documents that `language_code` "is not supported for
        // multilingual_v2 models" and that an unsupported code "will be
        // ignored". Advertising it meant the field was filled in, sent, and
        // dropped in silence: no error, no effect, nothing to notice. Its two
        // siblings keep it, so the paramMap entry stays and only this model
        // stops claiming it.
        Map<String, List<String>> byModel = capabilitiesByModel();

        assertThat(byModel.get("eleven-multilingual-v2"))
                .as("multilingual v2 must not advertise a language")
                .doesNotContain("language");
        assertThat(byModel.get("eleven-flash-v2-5"))
                .as("flash v2.5 does support it")
                .contains("language");
    }

    @Test
    @DisplayName("every shipped price factor is one a call can actually reach")
    void shippedFactorsAreReachableAndBounded() throws Exception {
        // A factor is money, and the seed's own prose quotes the worst case an owner would use to
        // decide whether the surcharge is acceptable. Two of those figures were wrong on the first
        // draft (a model described with frame slots it does not have, and a six-file maximum the
        // exclusion rules make unreachable), and nothing would have caught them.
        for (SeededEndpoint e : shipped()) {
            for (GenerationSpec.Model model : e.spec().models()) {
                GenerationSpec.Price price = model.price();
                if (price == null || price.modifiers().isEmpty()) continue;

                java.math.BigDecimal reachable = java.math.BigDecimal.ONE;
                for (GenerationSpec.PriceModifier modifier : price.modifiers()) {
                    assertThat(model.accepts(modifier.param()))
                            .as("%s: %s prices '%s', which it does not accept",
                                    e.source(), model.id(), modifier.param())
                            .isTrue();
                    reachable = reachable.multiply(
                            modifier.maxFactor(e.spec().paramMap().get(modifier.param())));
                }
                assertThat(reachable)
                        .as("%s: %s can reach %sx, which a price quote will not show",
                                e.source(), model.id(), reachable.toPlainString())
                        .isLessThanOrEqualTo(GenerationSpec.PriceModifier.MAX_FACTOR);
            }
        }
    }

    @Test
    @DisplayName("the video models this release prices carry the factors their basis claims")
    void theShippedVideoFactorsAreTheOnesDocumented() throws Exception {
        Map<String, GenerationSpec.Price> prices = pricesByModel();

        // Seedance: every attached frame or reference costs a twentieth of the call, and the most
        // a call can reach is 1.2x - four references, or two frames at 1.1025x, never both.
        assertThat(factorFor(prices, "seedance-2.0", Map.of("input_image",
                List.of("a", "b", "c", "d")))).isEqualByComparingTo("1.2");
        assertThat(factorFor(prices, "seedance-2.0", Map.of(
                "first_frame_image", "a", "last_frame_image", "b"))).isEqualByComparingTo("1.1025");
        // The 2.5 family has no frame slots at all, which is what the corrected basis says.
        assertThat(prices.get("seedance-2.5").modifiers())
                .extracting(GenerationSpec.PriceModifier::param)
                .containsExactly("input_image");

        // And the resolutions this release does NOT price, which is the more important half.
        //
        // A 2x resolution factor was written on both of these and removed before shipping. xAI
        // publishes ONE per-second figure for grok-imagine-video covering 480p and 720p alike, and
        // Google prices Veo 3.1 per second regardless of resolution: with no published
        // differential behind it, the surcharge is a price rise wearing the clothes of a cost, and
        // on top of that a `multiply` forces its parameter into `required`, so every saved call
        // that omitted the resolution would have started being refused.
        //
        // Pinned as an ABSENCE on purpose. Nothing else fails if a factor creeps back: the seed
        // validates, the parser accepts it, and every other suite here stays green while calls get
        // dearer. Whoever adds one has to come to this line and say what published figure it
        // tracks.
        assertThat(factorFor(prices, "grok-imagine-video", Map.of("resolution", "720p")))
                .as("xAI publishes one per-second figure for both resolutions")
                .isEqualByComparingTo("1");
        assertThat(factorFor(prices, "hf-veo-3.1", Map.of("resolution", "1080")))
                .as("Google prices Veo 3.1 per second whatever the resolution")
                .isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("a model whose resolution is priced REQUIRES it, so no call is billed for a guess")
    void aPricedResolutionIsAlwaysStated() throws Exception {
        // A factor keyed on a value the caller may omit bills the reference tier for whatever the
        // provider renders by default. The fix is to make the parameter required, which is a
        // BEHAVIOUR CHANGE for any saved call that omitted it: those are refused, by name, at no
        // cost - which is the failure mode this codebase prefers over a silent mis-charge.
        int pricedValues = 0;
        for (SeededEndpoint e : shipped()) {
            for (GenerationSpec.Model model : e.spec().models()) {
                GenerationSpec.Price price = model.price();
                if (price == null) continue;
                for (GenerationSpec.PriceModifier modifier : price.modifiers()) {
                    if (modifier.countsAssets()) continue;
                    pricedValues++;
                    assertThat(model.requires(modifier.param()))
                            .as("%s: %s prices '%s' but does not require it, so a call that omits "
                                    + "it is billed the reference tier for whatever the provider "
                                    + "chooses", e.source(), model.id(), modifier.param())
                            .isTrue();
                }
            }
        }
        // The loop above is EMPTY today, and a test that passes because it checked nothing is worth
        // nothing, so the count is asserted too. This release ships only per-file surcharges: the
        // two value factors that were drafted had no published cost behind them and were removed
        // (see theShippedVideoFactorsAreTheOnesDocumented).
        //
        // So this line is a deliberate checkpoint rather than a nuisance. A `multiply` is a price
        // RISE and it turns its parameter into a required one, which refuses calls that run today.
        // Shipping the first one should cost somebody a line of test and a sentence saying which
        // published figure it tracks.
        assertThat(pricedValues)
                .as("this release prices no VALUE; raise this with the modifier that changes it")
                .isZero();
    }

    private static java.math.BigDecimal factorFor(Map<String, GenerationSpec.Price> prices,
                                                   String modelId, Map<String, Object> supplied) {
        GenerationSpec.Price price = prices.get(modelId);
        assertThat(price).as("%s must be in the shipped seed", modelId).isNotNull();
        return price.factorFor(supplied);
    }

    /** Every shipped model's price, keyed by model id. */
    private static Map<String, GenerationSpec.Price> pricesByModel() throws Exception {
        Map<String, GenerationSpec.Price> byModel = new LinkedHashMap<>();
        for (SeededEndpoint e : shipped()) {
            for (GenerationSpec.Model m : e.spec().models()) {
                byModel.put(m.id(), m.price());
            }
        }
        return byModel;
    }

    /** Every shipped model's capabilities, keyed by model id. */
    private static Map<String, List<String>> capabilitiesByModel() throws Exception {
        Map<String, List<String>> byModel = new LinkedHashMap<>();
        for (SeededEndpoint e : shipped()) {
            for (GenerationSpec.Model m : e.spec().models()) {
                byModel.put(m.id(), new ArrayList<>(m.capabilities()));
            }
        }
        return byModel;
    }
}
