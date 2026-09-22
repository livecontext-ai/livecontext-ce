package com.apimarketplace.catalog.service.generation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the platform's unified generation parameters into the request shape one
 * specific provider expects.
 *
 * <p>This is the reason a single tool can front every provider. The surfaces
 * only ever speak {@code prompt}, {@code duration_seconds}, {@code voice} and
 * friends; this class projects them onto whatever the provider actually wants,
 * including nested arrays ({@code content[0].text}) and foreign units
 * (milliseconds), using only what the descriptor declares. No provider-specific
 * branch exists here, and adding one would defeat the point.
 *
 * <p><b>Validation happens before the request is built, not after it fails.</b>
 * An unknown parameter, a value outside a model's constraints or a missing
 * prompt are all caught here, with a message naming what the model actually
 * accepts. That matters more than usual: past this point the call costs the
 * customer real money, and an agent that gets told the accepted values can
 * recover on its next turn instead of guessing.
 */
public final class GenerationRequestBuilder {

    /** {@code name} or {@code name[3]} - one segment of a dotted upstream path. */
    private static final Pattern SEGMENT = Pattern.compile("^([^\\[\\]]+)(?:\\[(\\d+)\\])?$");

    private GenerationRequestBuilder() {}

    /**
     * Outcome of building a request.
     *
     * @param params       upstream parameters, ready to hand to the catalog executor
     * @param errors       validation failures; when non-empty {@code params} must be ignored
     * @param quantity     size of the call in PLATFORM units, so the caller can
     *                     price it without re-reading the parameters
     * @param quantityUnit platform unit {@code quantity} is counted in
     *                     ({@code second}, {@code image}, {@code character}, or
     *                     {@code call}), carried alongside so the number is
     *                     never reported under a unit it was not measured in
     * @param priceMultiplier what the CHOICES in this call do to its price,
     *                     from the model's declared modifiers. Always 1 for a
     *                     model that declares none, which is most of them, and
     *                     the arithmetic downstream is then unchanged.
     *                     <p>Kept SEPARATE from {@code quantity} on purpose. A
     *                     ten second clip is ten seconds whatever it costs, and
     *                     folding a resolution surcharge into the measurement
     *                     would have the run report twenty seconds of video
     *                     that nobody asked for and no player would show.
     * @param priceFactors one line per modifier that moved the price, so a
     *                     surface can say WHY rather than only how much
     */
    public record Built(Map<String, Object> params, List<String> errors,
                        BigDecimal quantity, String quantityUnit,
                        BigDecimal priceMultiplier, List<String> priceFactors) {

        public Built {
            priceMultiplier = priceMultiplier == null ? BigDecimal.ONE : priceMultiplier;
            priceFactors = priceFactors == null ? List.of() : List.copyOf(priceFactors);
        }

        /** The shape every caller spoke before price modifiers existed. */
        public Built(Map<String, Object> params, List<String> errors,
                     BigDecimal quantity, String quantityUnit) {
            this(params, errors, quantity, quantityUnit, BigDecimal.ONE, List.of());
        }

        public boolean ok() {
            return errors.isEmpty();
        }

        /** True when the choices in this call leave its price exactly at the published rate. */
        public boolean pricedAtBaseRate() {
            return priceMultiplier.compareTo(BigDecimal.ONE) == 0;
        }
    }

    /**
     * Validate the unified parameters against a model and project them onto the
     * provider's request.
     *
     * <p>A model sold by the size of the call ends up with that size stated one
     * way or another: the caller supplied it, the model's own default filled it
     * in and was sent upstream with it, or the call is refused naming the
     * parameter. It never leaves here with a size nobody stated.
     *
     * @param spec    descriptor of the endpoint being called
     * @param model   model selected within it
     * @param unified caller-supplied unified parameters
     */
    public static Built build(GenerationSpec spec, GenerationSpec.Model model,
                               Map<String, Object> unified) {
        List<String> errors = new ArrayList<>();
        Map<String, Object> request = new LinkedHashMap<>();

        // Scaffolding the provider expects around the dynamic values.
        for (Map.Entry<String, Object> c : spec.constants().entrySet()) {
            setByPath(request, c.getKey(), c.getValue(), errors);
        }

        // The model selector, when the endpoint has one. A single-model endpoint
        // must not receive a stray model parameter it does not understand.
        if (spec.sendsModelParam()) {
            setByPath(request, spec.modelParam(), model.upstream(), errors);
        }

        // Values this particular model always sends, typically the quality tier
        // its price is quoted for. Parsing refuses a pin the same model also
        // accepts as a parameter, so nothing written below can overwrite one.
        for (Map.Entry<String, Object> c : model.constants().entrySet()) {
            setByPath(request, c.getKey(), c.getValue(), errors);
        }

        Map<String, Object> given = unified == null ? Map.of() : unified;
        for (Map.Entry<String, Object> e : given.entrySet()) {
            String name = e.getKey();
            Object value = e.getValue();
            if (value == null) continue;

            if (!GenerationSpec.UNIFIED_PARAMS.contains(name)) {
                errors.add("'" + name + "' is not a generation parameter. This model accepts: "
                        + accepted(model));
                continue;
            }
            if (!model.accepts(name)) {
                errors.add("model '" + model.id() + "' does not accept '" + name + "'. It accepts: "
                        + accepted(model));
                continue;
            }
            GenerationSpec.Constraint constraint = model.constraints().get(name);
            if (constraint != null) {
                String violation = constraint.violation(value);
                if (violation != null) {
                    errors.add("'" + name + "' " + violation);
                    continue;
                }
            }
            GenerationSpec.ParamBinding binding = spec.paramMap().get(name);
            if (binding == null) {
                // Unreachable for a descriptor that passed import validation
                // (a capability without a mapping is refused there), so this is
                // a guard against a hand-edited row rather than normal input.
                errors.add("'" + name + "' has no upstream mapping on this endpoint");
                continue;
            }
            setByPath(request, binding.path(), binding.adapt(value), errors);
        }

        // Everything the provider will refuse the call without, checked here so
        // it costs nothing. A parameter that is optional to the platform but
        // required by the provider (ElevenLabs needs a voice) would otherwise
        // pass every check, dispatch, and fail upstream on a call already paid
        // for.
        for (String requiredParam : model.required()) {
            if (isBlank(given.get(requiredParam))) {
                errors.add("'" + requiredParam + "' is required for model '" + model.id() + "'");
            }
        }

        // Slots that only work as a PAIR, and slots that cannot travel together.
        // Seedance brought in both: its first-and-last-frame mode takes two images
        // and refuses a call carrying only the closing one, and pinning a frame
        // cannot be mixed with lending a reference. Checked here, so the reader is
        // told while it is still free instead of by an invoice - or, for the second
        // one, by a finished clip that ignored half the files it was given.
        //
        // Only for parameters this model actually takes: one it does not is already
        // reported above, and adding a sentence about how to pair a slot the model
        // has no place for sends the reader to fix the wrong thing.
        for (Map.Entry<String, Object> e : given.entrySet()) {
            if (!supplied(e.getValue()) || !model.accepts(e.getKey())) continue;
            GenerationSpec.ParamBinding binding = spec.paramMap().get(e.getKey());
            if (binding == null) continue;
            for (String companion : binding.requires()) {
                if (!supplied(given.get(companion))) {
                    errors.add("'" + e.getKey() + "' only works together with '" + companion
                            + "' on model '" + model.id() + "': send both, or neither");
                }
            }
            // Read symmetrically, so the pair is refused whichever side declared it
            // and whichever side the caller happens to be iterating past.
            //
            // BOTH halves have to be parameters this model takes. One it does not is
            // already reported above, and "pick one" about a slot the model has no
            // place for asks the reader to choose between something they can use and
            // something they cannot - which is the same mistake as explaining how to
            // pair a slot that does not exist, one loop lower. Whether it surfaced at
            // all depended on alphabetical order, since only one direction of each
            // pair is reported.
            for (String forbidden : forbiddenWith(spec, e.getKey())) {
                if (!model.accepts(forbidden)) continue;
                if (supplied(given.get(forbidden)) && e.getKey().compareTo(forbidden) < 0) {
                    errors.add("'" + e.getKey() + "' and '" + forbidden + "' cannot be sent in the "
                            + "same call on model '" + model.id() + "': this provider treats them as "
                            + "different kinds of request, so pick one");
                }
            }
        }

        BigDecimal quantity = measure(spec, model, given, request, errors);

        // ONE call produces ONE stored asset: the resolver reads a single
        // assetPath and stores a single file, while a price unit of 'image'
        // multiplies the count. A call billed for more is therefore charged for
        // assets it is handed none of, and the only trace is a billed_quantity
        // the reader has no reason to distrust.
        //
        // Checked on the MEASURED quantity, after `measure`, and not on what
        // the caller sent. A model can supply the number itself: a constraint
        // of {"min": 4} with no ceiling makes 4 the default, so a call that
        // never mentions a count is billed for four. Reading the caller's input
        // would have missed exactly the route this guard exists for, since the
        // seed gate already refuses such a model and the point here is to cover
        // descriptors that arrive by another road (an API submitted over HTTP,
        // a signed catalog bundle, a row edited by hand).
        //
        // Only when the model accepts the parameter at all: otherwise the
        // accepts check above has already refused the call, and a second
        // sentence telling the caller to retry with 1 would send it back into
        // the first refusal.
        if (errors.isEmpty() && "image".equals(platformUnitFor(model))
                && quantity.compareTo(BigDecimal.ONE) > 0) {
            errors.add("'n' must be 1: one call produces and stores exactly one asset, so a call "
                    + "measured at " + quantity.toPlainString() + " would be charged for assets that "
                    + "are never returned. Run the call again to produce another.");
        }
        // What the CHOICES in this call do to its price, read from the model's
        // own declared modifiers and from the parameters that have just been
        // validated against it. Computed here, next to the quantity and from the
        // same map, because both are facts about the call that only this side
        // can establish: a factor supplied by a caller is a factor a caller can
        // send as 1.
        GenerationSpec.Price price = model.price();
        BigDecimal multiplier = price == null ? BigDecimal.ONE : price.factorFor(given);
        List<String> factors = price == null ? List.of() : price.explainFactor(given);
        return new Built(request, errors, quantity, platformUnitFor(model), multiplier, factors);
    }

    /**
     * Size of the call, in the platform's own unit, both for the price and for
     * the provider.
     *
     * <p><b>A size is never assumed, only stated.</b> When the caller does not
     * supply the measuring parameter, the model's own default is written INTO
     * the request as well as reported as the quantity, so the provider is asked
     * for exactly what the customer is charged for. Billing a default the
     * provider was never told about is how a five second clip gets charged as a
     * ten second one, or the reverse.
     *
     * <p>When the model has no default to fall back on, the call is REFUSED
     * here, naming the parameter that is missing. That refusal costs nothing and
     * the agent can fix it on its next turn. What it must never become is a
     * quantity of zero: a zero quantity prices the call at its base, which for a
     * pure per-unit rate is nothing, and the refusal that follows blames an
     * administrator for a price that is published and correct.
     */
    private static BigDecimal measure(GenerationSpec spec, GenerationSpec.Model model,
                                       Map<String, Object> given, Map<String, Object> request,
                                       List<String> errors) {
        String measuring = model.measuringParam();
        if (measuring == null) return BigDecimal.ONE;   // sold flat: one call is one call

        if ("prompt".equals(measuring)) {
            // Speech is sold by the length of what it says, and the prompt is
            // already required, so this is always available.
            Object prompt = given.get("prompt");
            return prompt == null ? BigDecimal.ZERO
                    : BigDecimal.valueOf(String.valueOf(prompt).length());
        }

        Object raw = given.get(measuring);
        if (!isBlank(raw)) {
            return parseQuantity(raw);
        }

        if (model.requires(measuring)) {
            // The required-parameter check above already reported it; saying it
            // twice would make one mistake look like two.
            return BigDecimal.ZERO;
        }

        BigDecimal fallback = model.defaultMeasurement();
        if (fallback == null) {
            errors.add("'" + measuring + "' is required for model '" + model.id()
                    + "': it is what this model is priced by, so a call cannot be sized without it");
            return BigDecimal.ZERO;
        }

        GenerationSpec.ParamBinding binding = spec.paramMap().get(measuring);
        if (binding == null) {
            errors.add("'" + measuring + "' is required for model '" + model.id()
                    + "': it is what this model is priced by, and this endpoint has no way to send it");
            return BigDecimal.ZERO;
        }
        setByPath(request, binding.path(), binding.adapt(naturalNumber(fallback)), errors);
        return fallback;
    }

    /** Render a derived size the way a hand-written one arrives: 5, not 5.0. */
    private static Object naturalNumber(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() <= 0 ? (Object) stripped.longValueExact() : stripped;
    }

    private static BigDecimal parseQuantity(Object raw) {
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
        return parsed.signum() < 0 ? BigDecimal.ZERO : parsed;
    }

    /**
     * Platform unit a call on this model is measured in.
     *
     * <p>Lives on the model itself, next to the price it is derived from, so the
     * measurement and the parameter that carries it cannot be decided in two
     * places. Kept here as the name the projection code calls it by.
     *
     * <p><b>The measurement is never pre-converted into the price unit, and that
     * is the whole point.</b> The rate that multiplies it lives on the PUBLISHED
     * price row, which an administrator can re-express at any time (the same
     * money per second can be published as 480 credits per minute). Converting
     * here, against the unit the SEED happened to declare, is how a one-minute
     * clip came to be billed 60 times the per-minute rate: the quantity was
     * scaled by one unit and the rate applied in another.
     */
    static String platformUnitFor(GenerationSpec.Model model) {
        return model.platformUnit();
    }

    /**
     * Write a value at a dotted, optionally indexed path, creating the
     * intermediate maps and lists on the way.
     *
     * <p>Array indices are supported because real request bodies use them
     * ({@code content[0].text}); gaps are filled with empty maps so an
     * out-of-order descriptor still produces a well-formed body.
     */
    @SuppressWarnings("unchecked")
    static void setByPath(Map<String, Object> root, String path, Object value, List<String> errors) {
        String[] segments = path.split("\\.");
        Object cursor = root;

        for (int i = 0; i < segments.length; i++) {
            Matcher m = SEGMENT.matcher(segments[i]);
            if (!m.matches()) {
                errors.add("malformed upstream path '" + path + "' at segment '" + segments[i] + "'");
                return;
            }
            String key = m.group(1);
            Integer index = m.group(2) == null ? null : Integer.parseInt(m.group(2));
            boolean last = i == segments.length - 1;

            if (!(cursor instanceof Map)) {
                errors.add("upstream path '" + path + "' collides with a non-object value");
                return;
            }
            Map<String, Object> node = (Map<String, Object>) cursor;

            if (index == null) {
                if (last) {
                    node.put(key, value);
                    return;
                }
                cursor = node.computeIfAbsent(key, k -> new LinkedHashMap<String, Object>());
            } else {
                // A colliding descriptor is refused at parse, so this is
                // normally unreachable. GenerationSpec is a public record
                // though, so a directly constructed one can still land an
                // indexed write on a key that already holds a scalar. Report it
                // like every other path problem instead of throwing a
                // ClassCastException the caller cannot interpret.
                Object existing = node.get(key);
                if (existing != null && !(existing instanceof List)) {
                    errors.add("upstream path '" + path + "' writes an array element over a non-array value");
                    return;
                }
                List<Object> list = (List<Object>) node.computeIfAbsent(key, k -> new ArrayList<>());
                while (list.size() <= index) {
                    list.add(new LinkedHashMap<String, Object>());
                }
                if (last) {
                    list.set(index, value);
                    return;
                }
                Object slot = list.get(index);
                if (!(slot instanceof Map)) {
                    slot = new LinkedHashMap<String, Object>();
                    list.set(index, slot);
                }
                cursor = slot;
            }
        }
    }

    /**
     * Read the value at a dotted, optionally indexed path. The mirror of
     * {@link #setByPath}, and deliberately its neighbour: the two share one
     * notion of what a segment is, and a reader that drifted from the writer
     * would silently find nothing where the writer had put something.
     *
     * <p>Returns null when any segment is missing, malformed, or lands on a
     * value of the wrong shape. Absence is the only failure mode a caller has
     * to handle, because "the path is not there" and "the path is not readable"
     * lead to the same decision.
     */
    @SuppressWarnings("unchecked")
    static Object getByPath(Map<String, Object> root, String path) {
        if (root == null || path == null || path.isBlank()) return null;
        Object cursor = root;

        for (String segment : path.split("\\.")) {
            Matcher m = SEGMENT.matcher(segment);
            if (!m.matches()) return null;
            String key = m.group(1);
            Integer index = m.group(2) == null ? null : Integer.parseInt(m.group(2));

            if (!(cursor instanceof Map)) return null;
            Object next = ((Map<String, Object>) cursor).get(key);

            if (index != null) {
                if (!(next instanceof List<?> list) || index >= list.size()) return null;
                next = list.get(index);
            }
            cursor = next;
            if (cursor == null) return null;
        }
        return cursor;
    }

    private static boolean isBlank(Object v) {
        return v == null || String.valueOf(v).trim().isEmpty();
    }

    /**
     * Everything one parameter may not travel with, from either side of the pair.
     *
     * <p>The descriptor states an exclusion once, on whichever binding its author was
     * writing. Reading only that side would let the same forbidden pair pass or fail
     * depending on which half the seed happened to name.
     */
    static Set<String> forbiddenWith(GenerationSpec spec, String param) {
        Set<String> forbidden = new LinkedHashSet<>(
                spec.paramMap().containsKey(param) ? spec.paramMap().get(param).excludes() : Set.of());
        spec.paramMap().forEach((other, binding) -> {
            if (binding.excludes().contains(param)) forbidden.add(other);
        });
        return forbidden;
    }

    /**
     * True when the caller actually put something in this parameter.
     *
     * <p>Not {@link #isBlank}: a file arrives as a map and a multi-file slot as a
     * list, and both render as non-blank text whatever they hold. An empty list is
     * how every surface says "no file here" for a slot that takes several, so
     * reading it as a value present would make a pair look complete when one half
     * of it is missing.
     */
    private static boolean supplied(Object value) {
        if (value == null) return false;
        if (value instanceof Collection<?> collection) return !collection.isEmpty();
        if (value instanceof Map<?, ?> map) return !map.isEmpty();
        return !String.valueOf(value).trim().isEmpty();
    }

    /**
     * Drop everything the caller left empty out of a built request.
     *
     * <p>{@link #setByPath} fills the slots below an index with empty objects, so
     * that a descriptor writing {@code content[2]} still produces a well-formed
     * body. That is right while one array belongs to one slot, and wrong as soon as
     * an endpoint gives each of its file slots its own index in a SHARED array:
     * Seedance's first frame, last frame and reference images all live in
     * {@code content}, so a caller who attaches only the closing frame leaves an
     * empty {@code {}} where the opening one would have gone, and the provider
     * reads an item with no type and no value. The same holds one level up: a slot
     * that takes several files and got none leaves the array itself empty.
     *
     * <p><b>Position carries no meaning in these arrays</b>, which is what makes
     * closing the gap safe: every provider that addresses one this way labels the
     * element itself (Seedance's {@code role}, Gemini's part type), so nothing
     * anyone reads moves. Emptiness is recursive - a container holding only empty
     * things is empty - and any scalar, however small, is content.
     */
    static void pruneEmpty(Map<String, Object> root) {
        if (root == null) return;
        removeEmpty(root.values());
    }

    /** Prune inside a value, then say whether what is left carries nothing. */
    private static boolean pruneAndTestEmpty(Object value) {
        if (value == null) return true;
        if (value instanceof Map<?, ?> map) {
            removeEmpty(map.values());
            return map.isEmpty();
        }
        if (value instanceof List<?> list) {
            removeEmpty(list);
            return list.isEmpty();
        }
        return false;
    }

    /**
     * Prune each member, then drop the ones that turned out to hold nothing.
     *
     * <p>Both halves are attempted even on a collection that refuses to be
     * modified. Not every container in a built request is the builder's own: a
     * caller can hand a whole list of files over as {@code List.of(...)}, and it
     * survives into the request untouched whenever the slot is refused before
     * conversion. Rewriting one of those is not this method's business - it only
     * ever removes things nobody put there - so a refusal is left alone rather
     * than thrown at a caller who did nothing wrong, which is what turned an
     * over-the-cap refusal into an UnsupportedOperationException.
     */
    private static void removeEmpty(Collection<?> members) {
        boolean anyEmpty = false;
        for (Object member : members) {
            if (pruneAndTestEmpty(member)) anyEmpty = true;
        }
        if (!anyEmpty) return;
        try {
            members.removeIf(GenerationRequestBuilder::isEmpty);
        } catch (UnsupportedOperationException immutable) {
            // Somebody else's collection. Everything inside it has still been
            // pruned where that was possible.
        }
    }

    /** Whether a value that has ALREADY been pruned holds anything. */
    private static boolean isEmpty(Object value) {
        if (value == null) return true;
        if (value instanceof Map<?, ?> map) return map.isEmpty();
        if (value instanceof List<?> list) return list.isEmpty();
        return false;
    }

    private static String accepted(GenerationSpec.Model model) {
        List<String> caps = new ArrayList<>(model.capabilities());
        java.util.Collections.sort(caps);
        return String.join(", ", caps);
    }

    /** Normalise a caller-supplied model id the same way the registry does. */
    public static String normalizeModelId(String modelId) {
        return modelId == null ? null : modelId.trim().toLowerCase(Locale.ROOT);
    }
}
