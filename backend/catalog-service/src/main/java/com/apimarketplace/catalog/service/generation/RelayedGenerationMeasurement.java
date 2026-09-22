package com.apimarketplace.catalog.service.generation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a relayed generation is, and how big it is, measured from the request
 * body the cloud already received.
 *
 * <p><b>Why the cloud measures instead of being told.</b> A self-hosted install
 * relaying a call is a customer's own server. If it declared the size of its own
 * generation, it could declare a ten second video as one second and be billed
 * for one, which is the same class of hole the gateway closes by stripping the
 * generation billing headers off every inbound request. The cloud owns the
 * catalog, so it can read both facts out of the provider-shaped parameters it
 * was sent, using the same descriptor that produced them. Nothing extra travels
 * over the wire, and there is nothing for a caller to under-report.
 *
 * <p>The descriptor is read in the direction opposite to
 * {@link GenerationRequestBuilder}: {@code modelParam} names the upstream field
 * that selects the model, and the measuring parameter's binding names the path
 * that carries the size, so both are recovered by reading those paths back.
 * A binding may also carry a {@code scale} (a model sold per minute whose
 * provider takes seconds); the writer multiplies by it, so the reader divides.
 */
public final class RelayedGenerationMeasurement {

    private RelayedGenerationMeasurement() {}

    /**
     * @param modelId  the generation model the call names, null when the
     *                 descriptor has no model selector or the body does not
     *                 carry one
     * @param quantity the PLATFORM measurement of the call (seconds, assets,
     *                 characters), null when the call cannot be measured from
     *                 this body. Null is not zero: it means the price has to be
     *                 resolved the conservative way, never that the call is free.
     */
    public record Measured(String modelId, BigDecimal quantity, String quantityUnit,
                            BigDecimal priceMultiplier) {

        public static final Measured NOTHING = new Measured(null, null, null, null);

        /** The shape callers built before a call's choices could move its price. */
        public Measured(String modelId, BigDecimal quantity, String quantityUnit) {
            this(modelId, quantity, quantityUnit, null);
        }
    }

    /**
     * Read the model and the size out of an upstream request body.
     *
     * <p>Never throws: a body that does not match the descriptor yields
     * {@link Measured#NOTHING}, and the caller then prices it as it did before
     * this existed, which refuses rather than guesses.
     */
    public static Measured measure(GenerationSpec spec, Map<String, Object> upstreamParams) {
        if (spec == null || upstreamParams == null || upstreamParams.isEmpty()) {
            return Measured.NOTHING;
        }
        String modelId = readModelId(spec, upstreamParams);
        GenerationSpec.Model model = resolveModel(spec, modelId);
        if (model == null) {
            return new Measured(modelId, null, null);
        }
        // The unit comes from the model, through the SAME accessor the direct
        // path uses (GenerationRequestBuilder.platformUnitFor), so a number and
        // the name of what it counts cannot be derived from different rules.
        // A quantity without its unit cannot be checked against a published
        // rate: a row priced per image and a call measured in seconds both
        // arrive as a bare 10.
        return new Measured(modelId,
                readQuantity(spec, model, upstreamParams),
                GenerationRequestBuilder.platformUnitFor(model),
                readMultiplier(spec, model, upstreamParams));
    }

    /**
     * What the CHOICES in this relayed call do to its price, read back out of
     * the body the install sent.
     *
     * <p>Measured here for exactly the reason the quantity is: a self-hosted
     * install relaying a call is a customer's own server, so a factor it
     * declared would be a factor it could declare as 1. The cloud owns the
     * descriptor, so it can read the resolution that was asked for and count
     * the files that were attached out of the provider-shaped body it was
     * already sent. Nothing extra travels, and there is nothing to under-report.
     *
     * <p>A model with no declared modifiers yields null, which every reader
     * treats as "at the published rate": the relay then resolves exactly the
     * amount it resolved before this existed.
     */
    private static BigDecimal readMultiplier(GenerationSpec spec, GenerationSpec.Model model,
                                              Map<String, Object> params) {
        GenerationSpec.Price price = model.price();
        if (price == null || price.modifiers().isEmpty()) return null;
        Map<String, Object> supplied = new LinkedHashMap<>();
        for (GenerationSpec.PriceModifier modifier : price.modifiers()) {
            Object value = modifier.countsAssets()
                    ? filesInSlot(spec, modifier.param(), params)
                    : readBound(spec, modifier.param(), params);
            if (value != null) supplied.put(modifier.param(), value);
        }
        // The SAME arithmetic the direct path runs, from the same declared
        // modifiers: two copies of it is how a relayed call and a direct one
        // come to charge different amounts for the same request.
        return price.factorFor(supplied);
    }

    /**
     * The files present in one slot, as a list the factor arithmetic can count.
     *
     * <p><b>Counted by the slot's own MARKER, never by position.</b> Writing
     * {@code content[3]} materialises every slot below it and the dispatcher
     * then prunes the empties and closes the gap, deliberately, because position
     * carries no meaning in these arrays. A reader that walked the index
     * therefore read three reference images as one file in the right slot and
     * two files in the two frame slots they had nothing to do with: it charged a
     * relayed Seedance call differently from the identical direct one, which is
     * the single disagreement this class exists to prevent.
     */
    private static List<Object> filesInSlot(GenerationSpec spec, String unifiedParam,
                                             Map<String, Object> params) {
        GenerationSpec.ParamBinding binding = spec.paramMap().get(unifiedParam);
        if (binding == null || binding.path() == null) return List.of();

        // A slot that is not an array element is one file or none, and its path names it exactly.
        ArraySlot slot = ArraySlot.of(binding.path());
        if (slot == null) {
            Object only = GenerationRequestBuilder.getByPath(params, binding.path());
            return only == null || String.valueOf(only).isBlank() ? List.of() : List.of(only);
        }

        Object arrayValue = GenerationRequestBuilder.getByPath(params, slot.arrayPath());
        if (!(arrayValue instanceof List<?> elements)) return List.of();

        // What marks an element as BELONGING to this slot. Seedance's content[] carries the opening
        // frame, the closing frame and the references in one array, and the only thing telling them
        // apart is the role the descriptor writes beside each file - which is exactly what
        // itemConstants are for.
        Map<String, Object> marker = slot.markerOf(binding.itemConstants());

        // COUNTED IN FULL, never clamped to the slot's maxItems.
        //
        // This stopped at maxItems once, and the clamp rounded in the install's favour: the relay
        // executes the body it is handed and does not re-validate it against the descriptor (only
        // the DIRECT path refuses more files than a slot takes, in GenerationInputResolver). So an
        // install that built the body itself with ten reference images on a four-slot model had the
        // provider process ten and the cloud charge for four. That is an install declaring its own
        // price, which is the single thing this class exists to stop.
        //
        // Charging what was actually sent is also the honest reading: the provider was given those
        // files and billed the cloud for conditioning on them.
        List<Object> found = new ArrayList<>();
        for (Object element : elements) {
            Object leaf = GenerationRequestBuilder.getByPath(asMap(element), slot.leafPath());
            if (leaf == null || String.valueOf(leaf).isBlank()) continue;
            if (!matchesMarker(element, marker)) continue;
            found.add(leaf);
        }
        return found;
    }

    /**
     * An indexed binding path, split into the array it walks, the element field it reads, and
     * nothing else.
     *
     * <p><b>Why the index itself is discarded.</b> Writing {@code content[3]} materialises every
     * slot below it, and the dispatcher then PRUNES the empties and closes the gap - deliberately,
     * because position carries no meaning in these arrays. So three reference images declared at
     * {@code content[3]} arrive at {@code content[1..3]} on a call that pinned no frame, and a
     * reader that trusted the index found one of the three. On a per-file price that is a silent
     * undercharge on every relayed call, and the direct path (which reads the caller's own map)
     * disagreed with it by up to 20%.
     */
    private record ArraySlot(String arrayPath, int index, String leafPath) {

        /** Split {@code content[3].image_url.url} into {@code content}, 3 and {@code image_url.url}. */
        static ArraySlot of(String path) {
            java.util.regex.Matcher m = LAST_INDEX.matcher(path);
            int start = -1, end = -1, index = -1;
            while (m.find()) {
                start = m.start();
                end = m.end();
                index = Integer.parseInt(m.group(1));
            }
            if (start < 0) return null;
            String arrayPath = path.substring(0, start);
            String leaf = path.substring(end);
            if (leaf.startsWith(".")) leaf = leaf.substring(1);
            return new ArraySlot(arrayPath, index, leaf);
        }

        /** The item constants that sit on THIS slot's element, keyed by their field inside it. */
        Map<String, Object> markerOf(Map<String, Object> itemConstants) {
            Map<String, Object> marker = new LinkedHashMap<>();
            String prefix = arrayPath + "[" + index + "].";
            for (Map.Entry<String, Object> constant : itemConstants.entrySet()) {
                if (constant.getKey().startsWith(prefix)) {
                    marker.put(constant.getKey().substring(prefix.length()), constant.getValue());
                }
            }
            return marker;
        }
    }

    /** True when the element carries every field this slot's descriptor writes beside its file. */
    private static boolean matchesMarker(Object element, Map<String, Object> marker) {
        if (marker.isEmpty()) {
            // Nothing distinguishes this slot inside its array. Parsing refuses that shape when a
            // per-file price depends on it, so reaching here means the array is this slot's alone.
            return true;
        }
        Map<String, Object> asMap = asMap(element);
        for (Map.Entry<String, Object> expected : marker.entrySet()) {
            Object actual = GenerationRequestBuilder.getByPath(asMap, expected.getKey());
            if (actual == null || !String.valueOf(actual).equals(String.valueOf(expected.getValue()))) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    /** An indexed path segment, the thing an array slot is addressed by. */
    private static final java.util.regex.Pattern LAST_INDEX =
            java.util.regex.Pattern.compile("\\[(\\d+)\\]");

    /**
     * What a call on this model is measured in, for a caller that already knows
     * the model and does not have a body to read.
     *
     * <p>Exists so the read-only quote can ask the SAME question the billing
     * path asks. A quote that skipped the unit would answer with an amount the
     * biller then refuses, which is a disagreement no response shows: both look
     * like ordinary successes on their own.
     *
     * @return the platform unit, or null when the model is unknown here, which
     *         means "cannot tell" and never "no unit"
     */
    public static String platformUnitFor(GenerationSpec spec, String modelId) {
        if (spec == null) return null;
        GenerationSpec.Model model = resolveModel(spec, modelId);
        return model == null ? null : GenerationRequestBuilder.platformUnitFor(model);
    }

    /** The model the body selects, by the upstream field the descriptor declares. */
    private static String readModelId(GenerationSpec spec, Map<String, Object> params) {
        String modelParam = spec.modelParam();
        if (modelParam == null || modelParam.isBlank()) {
            // A single-model endpoint has no selector; the one model IS the answer.
            return spec.models().size() == 1 ? spec.models().get(0).id() : null;
        }
        Object raw = GenerationRequestBuilder.getByPath(params, modelParam);
        if (raw == null) return null;
        String upstream = String.valueOf(raw).trim();
        if (upstream.isEmpty()) return null;
        // The body carries the UPSTREAM model name; the price is published
        // against the public id, so translate rather than compare the two.
        //
        // ONE upstream name can back several priced models. A tier the caller must not be able to
        // move is pinned with `constants` and sold as a model of its own (see Model.constants), so
        // seedance-2.0, -480p, -1080p and -4k are four public ids, four prices, and one upstream
        // selector. Returning the first match therefore priced every relayed call on the group at
        // whichever variant happened to be declared first: a relayed 4K render was billed at the
        // 720p rate, 152 credits a second instead of 778, and a relayed 480p one was billed at more
        // than twice what it costs. Nothing reported either, because both are valid models and the
        // amount looked ordinary.
        //
        // The pins are IN the body - the builder writes them there - so they are what tells the
        // variants apart, exactly as itemConstants tell two file slots apart further down.
        // The MOST SPECIFIC match wins, not the first.
        //
        // A model with no pins matches vacuously, so "first match" let an unpinned variant shadow a
        // pinned one declared after it: Stability's sd3.5-large and sd3.5-large-img2img share an
        // upstream name and the img2img one is told apart only by the `mode` it pins, so every
        // relayed image-to-image call was measured as the text-to-image model. Those two happen to
        // cost the same today, which is exactly why nothing would have reported it - and the
        // Seedance variants, which differ by up to five times, are the same mechanism.
        GenerationSpec.Model best = null;
        GenerationSpec.Model fallback = null;
        int candidates = 0;
        for (GenerationSpec.Model m : spec.models()) {
            if (!upstream.equals(m.upstream()) && !upstream.equals(m.id())) continue;
            candidates++;
            if (fallback == null) fallback = m;
            if (!pinsMatch(m, params)) continue;
            if (best == null || m.constants().size() > best.constants().size()) {
                best = m;
            }
        }
        if (best != null) return best.id();
        // No variant's pins match at all.
        //
        // With ONE model behind this upstream name there is nothing to be ambiguous about, and the
        // fallback is what this answered before variants were told apart - a single-variant
        // endpoint is unaffected.
        //
        // With SEVERAL, falling back names the first-declared one, and on the shipped Seedance
        // seed that is the cheapest of four: an unrecognised pin would be billed 152 credits a
        // second for a call that may be 778. Refusing instead makes the caller treat it as
        // unpriceable, which is the direction this codebase takes everywhere else that a
        // generation cannot be priced - the alternative is a silent undercharge nobody can see.
        if (candidates > 1) {
            return null;
        }
        return fallback == null ? null : fallback.id();
    }

    /**
     * True when every value this model PINS is present in the body with that value.
     *
     * <p>A model with no pins matches vacuously, which is the single-variant case and the reason
     * this cannot make an ordinary endpoint stricter: it is only ever asked to choose between
     * models that already share an upstream name.
     */
    private static boolean pinsMatch(GenerationSpec.Model model, Map<String, Object> params) {
        for (Map.Entry<String, Object> pin : model.constants().entrySet()) {
            Object actual = GenerationRequestBuilder.getByPath(params, pin.getKey());
            if (actual == null) return false;
            // Compared through the SAME normalisation a priced VALUE goes through, not by
            // String.equals.
            //
            // Two comparisons of the same kind of value under two rules is how a body written
            // `resolution: "4K"` matched no Seedance variant: the pin is "4k", equals said no, and
            // the fallback below chose the first-declared model - 152 credits a second where the
            // call was 778. The same trap holds for 1080 against "1080", and "1080.0" against
            // either, which JSON round-trips produce freely.
            if (!java.util.Objects.equals(
                    GenerationSpec.PriceModifier.normalizeKey(pin.getValue()),
                    GenerationSpec.PriceModifier.normalizeKey(actual))) {
                return false;
            }
        }
        return true;
    }

    private static GenerationSpec.Model resolveModel(GenerationSpec spec, String modelId) {
        if (modelId == null) return null;
        return spec.models().stream()
                .filter(m -> modelId.equals(m.id()))
                .findFirst()
                .orElse(null);
    }

    /**
     * The size of this call in PLATFORM units, or null when the body does not
     * carry it.
     *
     * <p>A model sold per call needs no measurement and reports one, which is
     * exactly what its price expects. Anything else is read from the path its
     * binding declares, with the writer's scale undone.
     */
    private static BigDecimal readQuantity(GenerationSpec spec, GenerationSpec.Model model,
                                            Map<String, Object> params) {
        String measuring = model.measuringParam();
        if (measuring == null) {
            // Sold per call: the quantity is one call, and it is knowable
            // without reading anything.
            return BigDecimal.ONE;
        }
        if ("prompt".equals(measuring)) {
            // Measured by its own text: the length of what was actually sent,
            // which is the same thing the direct path counts. An empty string
            // is not a measurement, for the reason given at the signum check
            // below.
            Object text = readBound(spec, measuring, params);
            if (text == null) return null;
            int length = String.valueOf(text).length();
            return length <= 0 ? null : BigDecimal.valueOf(length);
        }
        Object raw = readBound(spec, measuring, params);
        if (raw == null) return null;
        BigDecimal value;
        try {
            value = new BigDecimal(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        // NON-POSITIVE IS NOT A MEASUREMENT, and this must match the direct
        // path exactly: CatalogToolBillingService.sizeMissing treats null and
        // <= 0 as the same statement, "nobody said how big this is", because a
        // generation of zero seconds is not a call anyone meant to make.
        //
        // Reading a 0 as a real size is worse here than it looks. The resolver
        // reports unitCredits as ZERO whenever ANY quantity was supplied (a
        // measured per-unit amount is no longer the price of one unit), so
        // FrozenMarkupDto.isPricedPerUnitWithoutQuantity stays false and the
        // relay's only per-unit refusal cannot fire. The amount is then
        // base + rate x 0, clamped UP to minCredits, which is positive, so the
        // install is billed a floor for a size it never stated while the
        // provider charges the platform owner for whatever it auto-selected.
        // Returning null instead lets that refusal fire, which is the outcome
        // the direct path already produces for the same input.
        if (value.signum() <= 0) return null;

        GenerationSpec.ParamBinding binding = spec.paramMap().get(measuring);
        BigDecimal scale = binding == null ? null : binding.scale();
        if (scale == null || scale.signum() == 0) {
            return value;
        }
        // The writer multiplied by the scale on the way out, so the reader
        // divides on the way back. Kept exact where it can be, and rounded UP
        // where it cannot: a rounding that favoured the caller would be a
        // discount nobody published.
        return value.divide(scale, 6, RoundingMode.CEILING).stripTrailingZeros();
    }

    /** Value at the upstream path a unified parameter is bound to. */
    private static Object readBound(GenerationSpec spec, String unifiedParam,
                                     Map<String, Object> params) {
        GenerationSpec.ParamBinding binding = spec.paramMap().get(unifiedParam);
        if (binding == null || binding.path() == null) return null;
        return GenerationRequestBuilder.getByPath(params, binding.path());
    }
}
