package com.apimarketplace.catalog.tools.generation;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.arrayParam;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.generateInputSchema;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.intParam;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.objectParam;
import static com.apimarketplace.agent.registry.ToolSchemaGenerator.stringParam;

/**
 * The single generation tool.
 *
 * <p>One tool for every format the platform resells: image, video, audio,
 * voice, music, and whatever a future seed adds. The format is not in the tool
 * name and never will be, because the tool that used to be called
 * {@code image_generation} is the same tool that now produces video.
 *
 * <p><b>This tool answers to {@code generation} and to nothing else.</b> The
 * older {@code image_generation} tool has been deleted, and this one did not
 * inherit its name. Re-claiming that name would be a mistake even now that it
 * is free: a tool name resolves to exactly one provider, and both the tool
 * registry and the dispatch cache are keyed by name, so a second provider
 * answering to it would silently overwrite the first with no warning and no
 * failure. More importantly the old name is retired vocabulary: it promises
 * images to an agent reading the tool list, while this tool also produces
 * video, audio, voice and music at very different rates.
 *
 * <p><b>Spend gate.</b> Every {@code create} debits the customer's credits, so
 * exposure is gated twice: the provider
 * is only registered where {@code generation.enabled=true}, and an agent only
 * receives the tool when its toolsConfig opts in ({@code generation: true}).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "generation.enabled", havingValue = "true", matchIfMissing = false)
public class GenerationToolsProvider implements ToolsProvider {

    /** Canonical tool name. Format-neutral on purpose. */
    public static final String TOOL_NAME = "generation";

    private static final List<String> VALID_ACTIONS = List.of("create", "models", "options", "help");

    private final GenerationModule module;

    public GenerationToolsProvider(GenerationModule module) {
        this.module = module;
    }

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.GENERATION;
    }

    @Override
    public List<AgentToolDefinition> getTools() {
        return List.of(buildTool());
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> parameters,
                                        ToolExecutionContext context) {
        if (!TOOL_NAME.equals(toolName)) {
            return ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown tool: " + toolName);
        }
        String action = parameters == null ? null : String.valueOf(parameters.get("action"));
        if (action == null || action.isBlank() || "null".equals(action)) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                    "action is required. Valid actions: " + String.join(", ", VALID_ACTIONS));
        }
        try {
            if ("help".equals(action)) {
                return ToolExecutionResult.success(help());
            }
            if (module.canHandle(action)) {
                return module.execute(action, parameters, context == null ? null : context.tenantId(), context)
                        .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                                "Generation failed"));
            }
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                    "Invalid action: " + action + ". Valid actions: " + String.join(", ", VALID_ACTIONS));
        } catch (Exception e) {
            log.error("Error executing generation action {}: {}", action, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Error: " + e.getMessage());
        }
    }

    private AgentToolDefinition buildTool() {
        var params = List.of(
                ToolParameter.builder()
                        .name("action")
                        .type("string")
                        .description("create | models | options | help")
                        .required(true)
                        .enumValues(VALID_ACTIONS)
                        .build(),
                stringParam("model", "Model id to generate with, from action='models' "
                        + "(create). Determines the format, the accepted parameters and the price.", false),
                stringParam("kind", "Narrow action='models' to one format: image, video, audio, "
                        + "voice, music. Omit to list everything.", false),
                stringParam("provider", "Narrow action='models' to one provider, matched against the "
                        + "name each row shows in 'provider'. Use it when you are choosing between one "
                        + "provider's tiers: a provider that charges differently for a quality or an "
                        + "output size sells each as its own model id, and this is how you see them "
                        + "together without reading every model of that format. A name nothing matches "
                        + "answers with an empty list, which means your filter matched nothing, not "
                        + "that the provider sells nothing: re-read it from a 'provider' field rather "
                        + "than guessing it.", false),
                // Reachable, not only documented. This is the one argument
                // action='options' cannot work without, and a schema that omits
                // it leaves an agent unable to call an action it can see: the
                // same gap that once made image-to-video unreachable from chat.
                stringParam("parameter", "Which parameter to ask the provider about (options). "
                        + "One of the names action='models' marked optionsAvailable in that "
                        + "model's limits. Pass the value you get back as that same parameter to "
                        + "action='create'.", false),
                stringParam("prompt", "What to generate (create). Required by every model.", false),
                stringParam("negative_prompt", "What to keep OUT of the result (create). Only "
                        + "models listing it in 'accepts' take it.", false),
                // THE FOUR THE AGENT COULD NOT REACH. The platform understands
                // them, the builder offers them and the validator accepts them,
                // but they were missing from this schema, so an agent could not
                // discover them and image-to-video was reachable from a workflow
                // and not from chat. A tool that accepts less than the system it
                // fronts makes the agent the weakest caller of its own platform.
                // OBJECTS, not strings. Declared as strings, the schema told the
                // agent to send text, and the only text it could reasonably send
                // is a path or a URL: neither lets the platform read the bytes,
                // so every attempt was refused. The type is the instruction.
                objectParam("input_image", "A file to start from or refer to (create): pass the "
                        + "whole file object another tool returned, exactly as it came, not its "
                        + "path and not a URL. This is how a video continues from an image, or an "
                        + "image is edited rather than made from nothing. Only models listing "
                        + "input_image in 'accepts' take it.", false),
                objectParam("input_audio", "A file to use as the reference voice or track "
                        + "(create). Same shape as input_image: the whole file object.", false),
                objectParam("input_video", "A file to continue or restyle (create). Same shape as "
                        + "input_image: the whole file object.", false),
                // THE SLOTS A MODEL TAKES AT THE SAME TIME. One image field could
                // only ever carry one image, so a model that pins both ends of a
                // clip and takes references besides was reachable only in part,
                // whatever it accepted: the second file had nowhere to go. Which
                // of these a model takes is in 'inputs' from action='models',
                // beside how many files each one holds.
                objectParam("first_frame_image", "The still the clip OPENS on (create). Same shape "
                        + "as input_image: the whole file object. Only models listing "
                        + "first_frame_image in 'accepts' take it.", false),
                objectParam("last_frame_image", "The still the clip LANDS on (create). Same shape "
                        + "as input_image. Which slots it must travel with, and which it cannot "
                        + "travel with, are the 'requires' and 'excludes' of its row in 'inputs' "
                        + "from action='models'; breaking either is refused at no cost.", false),
                arrayParam("reference_image", "Files that guide the result without appearing in it "
                        + "- a face, a product, a style (create). A LIST of whole file objects, even "
                        + "when you have one. How many this model takes is in 'inputs' from "
                        + "action='models'.", false, "object"),
                intParam("duration_seconds", "Length in seconds for video, music and sound "
                        + "(create). Models priced per second bill on this value. Omit it and the "
                        + "model's own default_duration_seconds from action='models' is used and "
                        + "sent to the provider; a model that lists it as required refuses instead, "
                        + "at no cost.", false, 0),
                // No DEFAULT on this one, deliberately. A client that
                // materialises schema defaults would send n on every call, and
                // since no model accepts it every generation would be refused
                // for a parameter the platform itself had supplied.
                intParam("n", "How many assets to produce (create). Leave it out: ONE call "
                        + "produces and stores one asset, so a larger number is refused rather than "
                        + "charged for assets that never come back. No model currently lists it in "
                        + "its accepts either.", false, null),
                stringParam("aspect_ratio", "Framing, e.g. '16:9' (create). See the model's limits.", false),
                stringParam("resolution", "Output size, e.g. '720p' (create). See the model's limits.", false),
                stringParam("voice", "Voice id for speech synthesis (create).", false),
                stringParam("language", "Language code for speech synthesis (create).", false),
                stringParam("quality", "Provider quality tier (create). See the model's limits.", false),
                stringParam("style", "Provider style preset (create).", false),
                intParam("seed", "Seed for reproducible output (create).", false, 0),
                stringParam("credential_source", "'platform' to use the platform's key and be "
                        + "billed the platform price, 'user' to use your own key and be billed "
                        + "nothing by the platform (create). Omit it and your own key is tried "
                        + "first, the platform's second, so state it when you need to be sure "
                        + "which one runs.", false));

        String description = """
                Generate an asset from a prompt: image, video, audio, voice or music.
                - models: the model ids you can use, what each accepts, and what each costs.
                - create: generate. Returns the produced file, plus the size it was billed on.
                Model ids cannot be guessed. Call action='models' once before the first create.
                Models priced per second or per character cost more for a longer request.
                Some choices are made by picking a MODEL, not by passing a parameter: several
                providers sell a quality, a size or an image-to-image mode as its own model id
                because each costs a different amount. If 'accepts' does not list what you want to
                set, read the other ids from the same provider before deciding it cannot be done.""";

        return AgentToolDefinition.builder()
                .name(TOOL_NAME)
                .description(description)
                .category(ToolCategory.GENERATION)
                .parameters(params)
                .requiredParameters(List.of("action"))
                .inputSchema(generateInputSchema(params, List.of("action")))
                .helpText("Call generation(action='models') for the model ids, their accepted "
                        + "parameters and their price in credits.")
                .requiresAuth(false)
                .tags(List.of("generation", "image", "video", "audio", "voice", "music", "tts"))
                // Sized on what catalog can legitimately take, not on a round
                // number: giving up first does not cancel the generation, it
                // only stops the caller hearing about one it has been charged
                // for.
                .timeoutMs(com.apimarketplace.catalog.service.ToolExecutionManager
                        .GENERATION_CALLER_BUDGET_MS)
                .build();
    }

    private Map<String, Object> help() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("description",
                "GENERATION TOOL - produces an asset of any kind from a prompt. One tool covers "
                + "every format the platform offers; the model you pick decides the format, the "
                + "parameters it accepts and the price. Returns a file reference, never inline bytes.");

        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("models", Map.of(
                "summary", "List the model ids you can generate with. Call this first. Ids from one "
                        + "provider often differ only by a tier that is priced differently (a quality, "
                        + "an output size, an image-to-image mode), so what one id does not accept "
                        + "another one may: read a provider's ids together before concluding a "
                        + "setting is unavailable.",
                "params", Map.of(
                        "kind", "optional - image | video | audio | voice | music. Without it the "
                                + "answer carries every model of every format, which is the longest "
                                + "answer this tool gives.",
                        "provider", "optional - one provider, as shown in a row's 'provider' field. "
                                + "The way to compare that provider's tiers without reading the rest. "
                                + "An unmatched name answers with an empty list rather than an error."),
                "returns", "{ models[]: {model, kind, label, provider, runs_on, accepts[], required[], "
                        + "limits{}, inputs{}, fixed{}, billed_on, default_<billed_on>, price{}, async}, "
                        + "count, kinds[], price_note, size_note, size_billed_note }. 'runs_on' says WHO "
                        + "can pay for it here: platform_or_own_key; own_key_only when this "
                        + "installation has no platform key for that provider or publishes no price "
                        + "for that model, in which case a create on the platform key is refused "
                        + "however well formed it is; or unknown when that could not be determined "
                        + "just now, which is not a refusal and is worth one retry. 'fixed' is "
                        + "everything the call sends whatever you pass, this model's tier and the "
                        + "endpoint's own scaffolding both, and is what separates two ids that "
                        + "accept the same parameters: a provider charging differently for a quality "
                        + "or an output size sells each as its own model id, and 'fixed' is where that "
                        + "shows. Its keys are the PROVIDER's own field names, not parameters you may "
                        + "pass - the ones you may pass are in 'accepts'. Choosing a tier means "
                        + "choosing another model id. limits{}, inputs{} and fixed{} are absent "
                        + "rather than present and empty when there is nothing to say, so a missing "
                        + "key means no restriction, no file and no pinned value. A model's limits{} "
                        + "holds, per parameter, any of allowed[], "
                        + "min, max and maxLength. A value outside a limit is normally "
                        + "refused before the provider is called, so testing one costs nothing - "
                        + "EXCEPT where the entry carries allowedEnforced:false. Those values are "
                        + "what the provider documents rather than a rule this platform checks: a "
                        + "value outside them is dispatched and fails at the provider, and you pay "
                        + "for the attempt. Alongside it, allowedTruncated:true means allowed[] is "
                        + "a SAMPLE of allowedCount values, not the whole set, because a provider "
                        + "with a hundred voices would otherwise fill this answer. For either of "
                        + "those, ask the person which value they want rather than inventing an "
                        + "identifier or trying variations. A limit carrying "
                        + "optionsAvailable:true carries no list here at all: those values live in "
                        + "the provider account and action='options' fetches them. That is the "
                        + "only way to learn a value such as a voice id, which cannot be guessed."));
        actions.put("options", Map.of(
                "summary", "Ask the provider what ONE parameter of ONE model accepts, when only "
                        + "the account behind the key can say.",
                "params", Map.of(
                        "model", "required - an id from action='models'",
                        "parameter", "required - a parameter that action='models' marked "
                                + "optionsAvailable in its limits",
                        "credential_source", "optional - 'user' | 'platform'. Use the SAME value "
                                + "you will pass to action='create': these values belong to that "
                                + "account, and another key has different ones."),
                "returns", "{ model, parameter, options[]: {value, label}, count, credential_source, "
                        + "note }, plus truncated + total_count when the account holds more than "
                        + "the answer carries. Send a 'value' as the parameter; 'label' is what to "
                        + "show a person and is never sent. When no key is connected the call "
                        + "fails saying so: connecting one is the account owner's act, so report "
                        + "it rather than retrying. Nothing is charged either way."));
        actions.put("create", Map.of(
                "summary", "Generate one asset.",
                "params", Map.of(
                        "model", "required - an id from action='models'",
                        "prompt", "required - what to generate",
                        "size", "the parameter named by that model's 'billed_on' (duration_seconds, or "
                                + "n). Omit it only when the model shows a default_<parameter>; a model "
                                + "that lists it in 'required' refuses the call naming it, at no cost.",
                        "input_image", "a file to start from, when the model lists input_image in "
                                + "'accepts'. Pass the WHOLE file object another tool returned, "
                                + "exactly as it came. Same for input_audio and input_video.",
                        "input_files", "a model that takes SEVERAL files names one slot per meaning: "
                                + "first_frame_image, last_frame_image, reference_image. Read "
                                + "'inputs' in action='models' for the ones this model has, how "
                                + "many files each holds, and its 'requires' / 'excludes' - the "
                                + "slots it must be sent with and the ones it may never be sent "
                                + "with. Fill the one you mean: they are not interchangeable, and "
                                + "no slot is guessed for you.",
                        "others", "only the parameters listed in that model's 'accepts'. Anything else "
                                + "is refused with the accepted list, at no cost."),
                "returns", "{ model, kind, provider, file, billed_quantity, billed_unit, "
                        + "billed_credits, provider_response }. billed_credits is what the platform "
                        + "charged, present only when the platform's key answered: ABSENT MEANS NOT "
                        + "CHARGED BY THE PLATFORM, never zero."));
        actions.put("help", Map.of("summary", "This payload. No params."));
        out.put("actions", actions);

        out.put("concepts", Map.of(
                "input_assets", "Some models start from a file rather than from nothing: a video that "
                        + "continues from an image, an image edited instead of invented, a voice "
                        + "cloned from a sample. Those models list input_image, input_audio or "
                        + "input_video in their 'accepts'. A model that takes more than one file "
                        + "names each slot by what the file IS to it instead: first_frame_image is "
                        + "the still the clip opens on, last_frame_image the one it lands on, and "
                        + "reference_image a list of files it borrows a face, a product or a style "
                        + "from without showing them. 'inputs' in action='models' names the slots "
                        + "this model has, what each one means, how many files it holds, and two "
                        + "rules: 'requires' lists slots it must be sent WITH (a provider that pins "
                        + "both ends of a clip refuses one frame on its own) and 'excludes' lists "
                        + "slots it may never be sent with (pinning a frame and lending a reference "
                        + "are, for some providers, two different kinds of request, and mixing them "
                        + "can come back as an asset that used half the files). Both are refused "
                        + "before anything is charged; sending "
                        + "the right file to the wrong slot is a different video, paid for. Pass the WHOLE file object another tool "
                        + "returned, exactly as it came: not its path, not a URL, not a name. Where "
                        + "to get one: the 'file' this tool returns from an earlier create, the 'ref' "
                        + "of files(action='get') for something already in the workspace, or the "
                        + "'file' of download_file and store_file. A file_id or a link is none of "
                        + "those. The "
                        + "platform reads the bytes out of storage and hands them to the provider in "
                        + "whatever shape that provider wants, which differs per provider and is not "
                        + "your concern. Anything that is not a file object is refused before the "
                        + "call is made, at no cost, and so is a file that is empty, missing or too "
                        + "large to send inside a request.",
                "pricing", "1 credit = $0.001. A model is priced per call, per second, per image or "
                        + "per character. A per-second video model at 60 credits/second costs 600 "
                        + "credits for a 10 second clip. action='models' shows each model's rate.",
                "call_size", "A model priced per second or per image bills on the parameter its "
                        + "'billed_on' names. You state it, or the model's own 'default_<parameter>' "
                        + "states it for you and is sent to the provider with the request, so what you "
                        + "pay for is what you get. A model that can do neither lists the parameter in "
                        + "'required' and refuses a call without it, naming it, at no cost. A refusal "
                        + "reading GENERATION_SIZE_UNKNOWN means exactly this: add the size and run it "
                        + "again. Speech is measured by its own prompt and never needs a size.",
                "credential_source", "State it and it is honoured exactly: 'platform' runs on the "
                        + "platform's provider key and bills you the platform price, 'user' runs on a "
                        + "key you configured yourself, in which case the platform bills you nothing "
                        + "and you pay the provider directly. On 'user' the account's DEFAULT key for "
                        + "that provider runs. An account holding several keys for one provider can "
                        + "pin a different one, but only its owner can: the ids are theirs and no "
                        + "action here lists them, so what you get is the default and "
                        + "get_connected_services is what shows which one that is. Omit it and your "
                        + "own key is tried first "
                        + "and the platform's is used only if you have none, which is convenient but "
                        + "leaves who paid depending on what you happen to have configured; state it "
                        + "whenever that matters. A refusal reading PLAN_EXCLUDES_THIS means the account's "
                        + "credits are the monthly free grant, which funds workflow runs and free-tier chat but "
                        + "never a generation: the account owner resolves it by subscribing or topping up, and "
                        + "nothing you send changes it, so report it and move on rather than retrying. "
                        + "Passing 'user' is also the answer to a refusal reading "
                        + "PLATFORM_NOT_AVAILABLE (the platform does not sell this model) or to one "
                        + "about your credit balance: whether the platform publishes a price, and "
                        + "whether your balance is topped up, are the account owner's decisions and "
                        + "nothing you send changes them. A refusal reading CREDENTIALS_REQUIRED is "
                        + "different again: no provider key exists for the pool you asked for, and the "
                        + "message names the provider. Switching pool is worth one try when the message "
                        + "offers it; connecting a key is the account owner's act, so report it rather "
                        + "than retrying. Nothing is charged for any of these refusals.",
                "validation", "Parameters are checked BEFORE the provider is called, so a rejected "
                        + "call costs nothing. The error names what the model accepts; correct it and "
                        + "retry.",
                "long_jobs", "A model marked async in action='models' can take minutes. The tool waits for "
                        + "the provider to finish and returns the asset; it never hands back a job id "
                        + "for you to poll.",
                "file_ref", "The asset comes back as {_type:'file', path, name, mimeType, size}. Pass "
                        + "that whole object into another tool to reuse it."));

        out.put("examples", List.of(
                Map.of("action", "models", "kind", "video"),
                Map.of("action", "create", "model", "seedance-2.0-fast",
                        "prompt", "a paper boat drifting down a rain gutter, cinematic",
                        "duration_seconds", 5, "aspect_ratio", "16:9"),
                Map.of("action", "create", "model", "eleven-multilingual-v2",
                        "prompt", "Welcome aboard. Please fasten your seatbelt.",
                        "voice", "rachel"),
                // Starting from a file: the whole object, exactly as the tool
                // that produced it handed it over.
                Map.of("action", "create", "model", "runway-i2v-gen4.5",
                        "prompt", "the boat drifts forward, camera slowly rising",
                        "duration_seconds", 5, "resolution", "1280:720",
                        "input_image", Map.of("_type", "file",
                                "path", "<as returned by the tool that produced it>",
                                "name", "boat.png", "mimeType", "image/png")),
                // Both ends of the clip pinned: the model generates the movement
                // between two stills you already have. Each file goes in the slot
                // that names what it is; there is no order to rely on.
                Map.of("action", "create", "model", "grok-imagine-video-1.5",
                        "prompt", "the camera dollies from the doorway to the window",
                        "duration_seconds", 8,
                        "first_frame_image", Map.of("_type", "file",
                                "path", "<as returned by the tool that produced it>",
                                "name", "open.png", "mimeType", "image/png"),
                        "last_frame_image", Map.of("_type", "file",
                                "path", "<as returned by the tool that produced it>",
                                "name", "close.png", "mimeType", "image/png"))));

        return out;
    }
}
