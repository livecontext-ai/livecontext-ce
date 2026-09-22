package com.apimarketplace.catalog.service.generation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What each FILE a model takes actually IS, for the surfaces that offer it.
 *
 * <p>The slot name alone ({@code input_image}) says an image goes here; it does not
 * say whether the image comes back changed, opens a clip, closes one, or only lends
 * its style. A surface cannot label the field without that, and labelling it wrong
 * costs a paid call.
 *
 * <p><b>One builder, two callers.</b> The agent's {@code action='models'} and the
 * app's model listing describe the same model, and they used to shape this block
 * twice from two copies of the same loop: the app then showed a limit the tool did
 * not enforce, or hid one it did. Everything a reader needs in order to AVOID a
 * refusal belongs here, next to the role, rather than in prose one of the two
 * surfaces happens to carry.
 */
public final class GenerationInputs {

    private GenerationInputs() {
    }

    /**
     * The file slots of one model: role, how many files, and what each one cannot be
     * sent without or sent with.
     *
     * <p>Narrowed to what THIS model accepts. A pairing rule naming a slot the model
     * does not have describes a call nobody can make, and sends the reader to add a
     * parameter that would be refused on its own terms.
     */
    public static Map<String, Object> describe(GenerationSpec spec, GenerationSpec.Model model) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        if (spec == null || model == null) return inputs;

        model.capabilities().stream()
                .filter(GenerationSpec.ASSET_PARAMS::contains)
                .forEach(param -> {
                    GenerationSpec.ParamBinding binding = spec.paramMap().get(param);
                    if (binding == null || binding.role() == null) return;
                    Map<String, Object> shape = new LinkedHashMap<>();
                    shape.put("role", binding.role().wire());
                    shape.put("maxItems", binding.maxItems());

                    // The two rules a caller can only otherwise learn from a refusal.
                    // Sorted so two readings of the same model are the same bytes.
                    List<String> requires = binding.requires().stream()
                            .filter(model::accepts).sorted().toList();
                    if (!requires.isEmpty()) shape.put("requires", requires);

                    // Symmetric, read from both sides of the pair: the descriptor states
                    // an exclusion once, on whichever binding its author was writing.
                    List<String> excludes = GenerationRequestBuilder.forbiddenWith(spec, param).stream()
                            .filter(model::accepts).sorted().toList();
                    if (!excludes.isEmpty()) shape.put("excludes", excludes);

                    inputs.put(param, shape);
                });
        return inputs;
    }
}
