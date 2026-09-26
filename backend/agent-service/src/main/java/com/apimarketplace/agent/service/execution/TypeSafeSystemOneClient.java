package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.client.dto.execution.ClassifyRequestDto;
import com.apimarketplace.agent.client.dto.execution.ClassifyResponseDto;
import com.apimarketplace.agent.client.dto.execution.ConversationMessageDto;
import com.apimarketplace.agent.provider.TypeSafeDecisionProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Calls TypeSafe's System One endpoint for the classify node.
 *
 * <p><b>Why this is not an {@code LLMProvider}.</b> Jev has no {@code /chat/completions}.
 * It takes a {@code state} plus a map of typed {@code questions} and answers with a
 * {@code choice} and a probability per option, so nothing in the request or the response
 * fits the message/tool/completion shape every {@code LLMProvider} is built around.
 * Forcing it into one would mean re-serialising a typed answer back into JSON text just
 * for {@code ClassifyService} to parse it again, losing the probabilities on the way.
 * {@code ClassifyService} therefore branches here before it builds an agent-loop context,
 * the same way it already branches to {@code BridgeLoopDispatcher} for the CLI providers.
 *
 * <p><b>What this engine changes for the caller.</b> The LLM path asks for JSON in a
 * prompt, parses the text that comes back, and accepts whatever category name it finds,
 * which is how a classify node can return a label that matches nothing and route nowhere.
 * Here the options are the request's own keys and the answer is one of them, so an
 * off-list label is not something the model can produce. The check in
 * {@link #readChoice} is therefore a transport guard, not a model guard: it fires when
 * the response is malformed, never because the model improvised.
 */
@Slf4j
@Component
public class TypeSafeSystemOneClient {

    /**
     * The single question asked per classify node. The response is keyed by this name, so
     * it is a protocol constant rather than a label: it never reaches the user and must
     * not be derived from the node, whose label may be empty or non-ASCII.
     */
    static final String QUESTION_KEY = "category";

    /** How many options are rendered into the synthesized reasoning line. */
    private static final int REASONING_TOP_N = 3;
    /** Stored content, so the separator is the character and never the platform's. */
    private static final String NL_CHAR = "\n";

    /**
     * Read timeout. TypeSafe publishes 70-500 ms end to end, so 30 s is roughly sixty
     * times the documented worst case: long enough that a slow day never fails a
     * classification, short enough that an unresponsive endpoint fails the node instead
     * of holding a workflow open behind it.
     */
    static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    /** Depth cap for the cause walk - see {@link #rootCauseOf}. */
    private static final int MAX_CAUSE_DEPTH = 20;

    private final String apiUrl;
    private final String apiKey;
    private final RestTemplate restTemplate;
    private com.apimarketplace.agent.resolver.LlmCredentialResolver credentialResolver;
    private TypeSafeDecisionProvider decisionProvider;

    public TypeSafeSystemOneClient(
            @Value("${ai.agent.providers.typesafe.api-url:https://api.typesafe.ai/v1/systemone}") String apiUrl,
            @Value("${ai.agent.providers.typesafe.api-key:}") String apiKey) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        // The request factory is PINNED, not detected. Left to RestTemplateBuilder, Spring
        // Boot picks the first client on the classpath (HttpComponents, Jetty, Reactor, JDK,
        // Simple), and shared-agent-lib puts reactor-netty-http there for WebFlux - so this
        // client silently ran on Reactor Netty's global connection pool, a transport nobody
        // chose for it, while every other provider pins its own (AbstractLLMProvider). Two
        // consequences, both real: the transport of a production call changed with an
        // unrelated dependency, and that pool never evicts an idle connection, so a
        // low-frequency workflow (Pro Mail polls every six hours) hands its first request a
        // socket the peer closed long ago. Measured 2026-09-22 against api.typesafe.ai:
        // reuse after 150 s still works, after 300 s the read hangs to the timeout, after
        // 600 s the peer has closed. Pinning does not remove pooling - the JDK keeps its own
        // keep-alive cache - it moves this call onto the transport the four other providers
        // already use, whose idle eviction is measured in seconds rather than never, and
        // makes the choice explicit instead of a consequence of the classpath.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(READ_TIMEOUT);
        this.restTemplate = new RestTemplateBuilder()
                .requestFactory(() -> factory)
                .build();
    }

    /** Whether {@code provider} is served by this client rather than by an LLM. */
    public boolean supports(String provider) {
        return TypeSafeDecisionProvider.PROVIDER_NAME.equalsIgnoreCase(provider);
    }

    /**
     * Whether this client serves the pair, including when the plan named only the model.
     *
     * <p><b>The model-only case is not hypothetical.</b> {@code provider} is optional on a
     * classify node, and an authored plan that sets {@code model: "jev-latest"} and omits
     * the provider resolves through the CHAT-filtered catalogue, which by design contains
     * no decision model: the lookup finds nothing, returns null, and the node would fall
     * to the LLM path and ask a chat provider for a model it has never heard of. The
     * failure surfaces three layers away from the plan that caused it. Recognising the
     * model closes that, and only when the provider is genuinely absent, so a plan that
     * names a chat provider explicitly still goes where it asked.
     */
    public boolean serves(String provider, String model) {
        if (provider != null && !provider.isBlank()) {
            return supports(provider);
        }
        return decisionProvider != null && model != null
                && decisionProvider.getSupportedModels().contains(model);
    }

    /**
     * The catalogue declaration of the decision models, used to recognise one named
     * without its provider. Optional so this client still works where the provider bean
     * is not wired.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDecisionProvider(TypeSafeDecisionProvider decisionProvider) {
        this.decisionProvider = decisionProvider;
    }

    /** Whether a key is configured; without one every call would 401. */
    public boolean isConfigured() {
        String resolved = resolveApiKey();
        return resolved != null && !resolved.isBlank();
    }

    /**
     * The key to present, resolved the way every other provider resolves one:
     * admin-configured credential first, environment second.
     *
     * <p><b>Reading only the environment was not a smaller version of this, it was broken.</b>
     * {@code ModelCatalogService.isProviderRuntimeAvailable} surfaces a provider when it is
     * env-configured OR has a stored credential, so an administrator who added the key
     * through the admin UI made the model APPEAR while this client still saw nothing: the
     * model was selectable and every classification on it failed with "no API key is
     * configured". Mirrors {@code AbstractLLMProvider.resolveApiKey}, which is how the
     * chat providers have always behaved.
     */
    private String resolveApiKey() {
        if (credentialResolver != null) {
            Optional<String> stored = credentialResolver.resolveApiKey(
                    TypeSafeDecisionProvider.PROVIDER_NAME);
            if (stored.isPresent() && !stored.get().isBlank()) {
                return stored.get();
            }
        }
        return apiKey;
    }

    /**
     * Admin-configured credential lookup. Optional so the client still works in a context
     * with no auth-service wired (tests, a service that opted out), where the environment
     * key is the only source.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCredentialResolver(
            com.apimarketplace.agent.resolver.LlmCredentialResolver credentialResolver) {
        this.credentialResolver = credentialResolver;
    }

    /**
     * Classify {@code request} and map the typed answer onto the shared response DTO.
     *
     * <p>Never throws: every failure is returned as an unsuccessful response carrying an
     * error the node surfaces, matching what {@code ClassifyService} does on the LLM path.
     */
    public ClassifyResponseDto classify(ClassifyRequestDto request, String provider, long startedAtMillis) {
        String model = request.model();
        String instructions = instructionsOf(request);
        // What the observability trace records as the USER turn: the whole request, never the
        // instructions alone. The instructions are the smallest of its three parts, so the trace
        // used to show neither the text that was judged nor the categories it was judged against.
        String sent = describeRequest(request, instructions);
        try {
            requireDeclaredModel(model);
            List<String> labels = declaredLabels(request);
            Map<String, Object> body = buildBody(request, labels, instructions);
            Map<String, Object> response = post(body);
            return readAnswer(response, labels, provider, model, sent,
                    System.currentTimeMillis() - startedAtMillis);
        } catch (ClassifyInputException e) {
            // The node was configured in a way the endpoint cannot be asked about. Reported
            // as a failure rather than a thrown exception so the message reaches the run.
            log.warn("Classify rejected before dispatch: {}", e.getMessage());
            return failure(e.getMessage(), provider, model, sent,
                    System.currentTimeMillis() - startedAtMillis);
        } catch (HttpStatusCodeException e) {
            String message = describeHttpFailure(e);
            // The STATUS is logged, never the message: a 422 carries the vendor's own
            // explanation, which can quote the request it rejected, and the request is the
            // user's content. The run's owner sees it in the returned error, where it
            // belongs; the platform log does not need it to diagnose a status code.
            log.warn("TypeSafe classify failed with status {}", e.getStatusCode().value());
            return failure(message, provider, model, sent,
                    System.currentTimeMillis() - startedAtMillis);
        } catch (org.springframework.web.client.ResourceAccessException e) {
            // TRANSPORT failure: the request never produced an HTTP response at all - the
            // connection was refused, reset, or timed out. Unlike a malformed BODY, nothing
            // here can quote the user's content: the cause chain is socket vocabulary, so it
            // is logged in full. That matters. Diagnosing the 2026-09-22 outage took hours
            // precisely because the only trace left was the exception's class name, with no
            // cause, no endpoint and no timing, and the real signature (a reset arriving at a
            // fixed ~500 ms while healthy answers took 600-1000 ms) had to be reconstructed
            // from per-item durations in the run rows instead of being read off a log line.
            log.error("TypeSafe classify transport failure after {} ms: {}: {} (cause: {})",
                    System.currentTimeMillis() - startedAtMillis,
                    e.getClass().getSimpleName(), e.getMessage(), rootCauseOf(e));
            return failure(describeTransportFailure(e), provider, model, sent,
                    System.currentTimeMillis() - startedAtMillis);
        } catch (Exception e) {
            // The TYPE only, and no stacktrace, for the same reason the branch above logs no
            // body: a malformed response surfaces as a RestClientException whose message and
            // cause chain can quote the payload, and the payload echoes the user's content.
            // Logging the class still says which failure mode this was; the detail reaches
            // the run's owner in the returned error, which is where it belongs.
            log.error("TypeSafe classify failed: {}", e.getClass().getSimpleName());
            return failure("Classification error: " + e.getMessage(), provider, model, sent,
                    System.currentTimeMillis() - startedAtMillis);
        }
    }

    // ===== Request =====

    /**
     * The categories, in declaration order, rejected when they cannot form a usable
     * option set.
     *
     * <p>Duplicate labels are refused rather than de-duplicated. A map would silently keep
     * the last one, so a node showing three branches would be classified against two, and
     * the lost branch would simply never fire. The duplicate is already ambiguous for
     * routing, which resolves a label to the FIRST category that matches it, so there is
     * no reading of the node that a de-duplication could honour.
     */
    /**
     * Refuse a model this provider does not declare, before spending a call on it.
     *
     * <p><b>The gap this closes is the mirror of {@code serves()}.</b> That method routes
     * a plan naming only a MODEL to the right engine; this one catches a plan naming this
     * provider with the wrong model, or with none. Both are reachable: {@code model} is
     * optional on the node, and the help's default model is rewritten from the CHAT
     * catalogue, so an agent that sets {@code provider} and leaves {@code model} alone
     * arrives here with a chat model or a null. Sending either produces a vendor 422 three
     * layers from the plan that caused it, which is the failure shape the other refusals
     * in this class exist to prevent.
     *
     * <p>Skipped when the provider bean is absent, where there is no declaration to check
     * against and refusing everything would be worse than trying.
     */
    private void requireDeclaredModel(String model) {
        if (decisionProvider == null) {
            return;
        }
        List<String> declared = decisionProvider.getSupportedModels();
        if (declared.isEmpty()) {
            return;
        }
        if (model == null || model.isBlank()) {
            throw new ClassifyInputException(
                    "This classify node names the '" + TypeSafeDecisionProvider.PROVIDER_NAME
                    + "' decision provider but no model. Set model to one of " + declared);
        }
        if (!declared.contains(model)) {
            throw new ClassifyInputException(
                    "'" + model + "' is not a " + TypeSafeDecisionProvider.PROVIDER_NAME
                    + " decision model. Set model to one of " + declared
                    + ", or pick a chat provider to run this node on a chat model.");
        }
    }

    private List<String> declaredLabels(ClassifyRequestDto request) {
        List<ClassifyRequestDto.CategoryDto> categories = request.categories();
        if (categories == null || categories.isEmpty()) {
            throw new ClassifyInputException("Classify requires at least one category");
        }
        List<String> labels = new ArrayList<>();
        for (ClassifyRequestDto.CategoryDto category : categories) {
            String label = category.label();
            if (label == null || label.isBlank()) {
                throw new ClassifyInputException("Every classify category needs a label");
            }
            if (labels.contains(label)) {
                throw new ClassifyInputException(
                        "Duplicate classify category label '" + label + "': labels must be distinct, "
                        + "otherwise one of the branches can never be selected");
            }
            labels.add(label);
        }
        return labels;
    }

    private Map<String, Object> buildBody(ClassifyRequestDto request, List<String> labels, String instructions) {
        Map<String, String> criteria = new LinkedHashMap<>();
        for (ClassifyRequestDto.CategoryDto category : request.categories()) {
            String description = category.description();
            // null is the documented "no description" value for a choice option; a blank
            // string would be sent as a real, empty criterion.
            criteria.put(category.label(),
                    (description == null || description.isBlank()) ? null : description);
        }

        Map<String, Object> question = new LinkedHashMap<>();
        question.put("type", "choice");
        question.put("instructions", instructions);
        question.put("criteria", criteria);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", stateOf(request));
        body.put("model", request.model());
        body.put("questions", Map.of(QUESTION_KEY, question));
        log.debug("TypeSafe classify: model={}, options={}", request.model(), labels.size());
        return body;
    }

    /** The instructions sent when the node carries no instruction of its own. */
    static final String DEFAULT_INSTRUCTIONS = "Classify this content into exactly one of the categories.";

    /**
     * The content being judged. The classify node carries {@code content} and {@code prompt}
     * separately but the LLM path concatenates them into one prompt, because a chat model
     * has nowhere else to put them. This endpoint separates the two by design, so the
     * fields are kept apart here: content is the state, the prompt is the instruction.
     * With only one of them set, that one is the state and the instruction falls back to
     * the default below.
     */
    private String stateOf(ClassifyRequestDto request) {
        String state = stateOrNull(request);
        if (state == null) {
            throw new ClassifyInputException("Classify needs content or a prompt to classify");
        }
        return state;
    }

    private static String stateOrNull(ClassifyRequestDto request) {
        if (request.content() != null && !request.content().isBlank()) {
            return request.content();
        }
        if (request.prompt() != null && !request.prompt().isBlank()) {
            return request.prompt();
        }
        return null;
    }

    private String instructionsOf(ClassifyRequestDto request) {
        boolean hasContent = request.content() != null && !request.content().isBlank();
        // distinctPrompt, not prompt: a node with no content configured arrives with its prompt
        // in BOTH fields, and sending it as the instructions too put the whole judged text in
        // front of the model a second time, billed as input on every item.
        if (hasContent && request.distinctPrompt() != null) {
            return request.distinctPrompt();
        }
        return DEFAULT_INSTRUCTIONS;
    }

    /**
     * The request as the observability trace shows it: the three fields this endpoint
     * receives, each under the name it is sent as, with nothing shortened. Built from the same
     * state and instructions the body is built from, so the trace cannot describe a request
     * other than the one that left; a category with no description is listed by its label
     * alone, as the body sends it with a null criterion. Never throws, since it runs before the
     * refusals in {@link #classify}: a node with nothing to classify, or a null category, still
     * gets a trace of what was configured.
     */
    static String describeRequest(ClassifyRequestDto request, String instructions) {
        StringBuilder sb = new StringBuilder();
        sb.append("## instructions").append(NL_CHAR).append(instructions).append(NL_CHAR).append(NL_CHAR);
        String state = stateOrNull(request);
        sb.append("## state").append(NL_CHAR).append(state != null ? state : "").append(NL_CHAR).append(NL_CHAR);
        sb.append("## criteria");
        if (request.categories() != null) {
            for (ClassifyRequestDto.CategoryDto category : request.categories()) {
                if (category == null) {
                    continue;
                }
                sb.append(NL_CHAR).append("- ").append(category.label() != null ? category.label() : "(no label)");
                String description = category.description();
                if (description != null && !description.isBlank()) {
                    sb.append(": ").append(description);
                }
            }
        }
        return sb.toString();
    }

    private Map<String, Object> post(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(resolveApiKey());
        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.postForObject(
                apiUrl, new HttpEntity<>(body, headers), Map.class);
        if (response == null) {
            throw new IllegalStateException("Empty response from TypeSafe");
        }
        return response;
    }

    // ===== Response =====

    @SuppressWarnings("unchecked")
    private ClassifyResponseDto readAnswer(Map<String, Object> response, List<String> labels,
                                            String provider, String model, String sent,
                                            long durationMs) {
        Map<String, Object> answers = (Map<String, Object>) response.get("answers");
        Object raw = answers != null ? answers.get(QUESTION_KEY) : null;
        if (!(raw instanceof Map)) {
            return failure("TypeSafe returned no answer for the classification", provider, model,
                    sent, durationMs);
        }
        Map<String, Object> answer = (Map<String, Object>) raw;

        String choice = readChoice(answer, labels);
        if (choice == null) {
            return failure("TypeSafe returned '" + answer.get("choice")
                    + "', which is not one of the declared categories", provider, model,
                    sent, durationMs);
        }

        Map<String, Double> probabilities = readProbabilities(answer, labels);
        double confidence = readConfidence(answer, probabilities, choice);
        int promptTokens = readUsage(response, "input_tokens");
        int completionTokens = readUsage(response, "output_tokens");
        // THE BILLED MODEL IS REPORTED, NOT THE SERVED ONE, and the difference is money.
        // "jev-latest" is an alias and the response names the concrete version behind it
        // ("jev-1.13"), which is tempting to report and wrong to: on the ASYNC completion
        // path the orchestrator takes the ledger's provider/model from the RESULT first,
        // falling back to the node config. Only the alias has a pricing row, so reporting
        // the concrete version keys the ledger on a pair with no price, and
        // ModelPricingService falls back to its 1.0 / 4.0 defaults - roughly 24x the real
        // input rate, plus output tokens the vendor gives away, and then multiplied by
        // this provider's lever. Every future jev-1.x would re-open it. The LLM path
        // re-stamps the billed model for exactly this reason; so does this one, by never
        // moving off it. The served version is logged instead, where it costs nothing.
        if (response.get("model") instanceof String served
                && !served.isBlank() && !served.equals(model)) {
            log.debug("TypeSafe served {} for alias {}", served, model);
        }

        return new ClassifyResponseDto(true, choice, confidence,
                describeProbabilities(probabilities), null, durationMs, provider, model,
                promptTokens + completionTokens, promptTokens, completionTokens,
                null, List.of(answerTurn(choice, confidence, probabilities)), sent,
                null, probabilities);
    }

    /**
     * The chosen label, or null when the response names something that was not offered.
     *
     * <p>The options are the keys this client sent, so the endpoint answering with one of
     * them is a property of the protocol. Verifying it anyway is cheap and closes the only
     * shape that would otherwise pass: a label that matches no branch is stored, the node
     * reports success, and the DAG stops with nothing to follow and no error to read.
     */
    private String readChoice(Map<String, Object> answer, List<String> labels) {
        Object choice = answer.get("choice");
        if (!(choice instanceof String selected) || selected.isBlank()) {
            return null;
        }
        return labels.contains(selected) ? selected : null;
    }

    /**
     * A probability per DECLARED category, or null when the response carries none.
     *
     * <p><b>Null, not an empty map, and the difference is persisted.</b> The async
     * completion path copies the worker's result map into the node output wholesale, so
     * an empty map written here reaches {@code workflow_step_data} as
     * {@code probabilities: {}} - which reads to a template as a real, unanimous result
     * rather than as "this engine reported none". Only null is dropped, by the DTO's
     * {@code NON_NULL} inclusion and by the inline path's own guard.
     *
     * <p>Keys are filtered to the labels that were offered. The choice is already checked
     * against them; the distribution was not, so a malformed response could write
     * arbitrary keys into a stored node output that a workflow then reads by name.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Double> readProbabilities(Map<String, Object> answer, List<String> labels) {
        Object raw = answer.get("probabilities");
        if (!(raw instanceof Map)) {
            return null;
        }
        Map<String, Double> probabilities = new LinkedHashMap<>();
        ((Map<String, Object>) raw).forEach((key, value) -> {
            if (value instanceof Number number && labels.contains(key)) {
                probabilities.put(key, number.doubleValue());
            }
        });
        return probabilities.isEmpty() ? null : probabilities;
    }

    /**
     * The endpoint's own confidence, which measures how concentrated the probability mass
     * is across ALL options rather than the winner's share alone. Falls back to the
     * winner's probability when the field is absent, and to 0 when nothing is reported:
     * inventing a high default would make an unknown confidence look like a certain one.
     */
    private double readConfidence(Map<String, Object> answer, Map<String, Double> probabilities,
                                   String choice) {
        Object raw = answer.get("confidence");
        // probabilities is null when the response reported none, which is the same
        // situation as an unknown confidence: fall to 0 rather than dereference it.
        double confidence = raw instanceof Number number
                ? number.doubleValue()
                : (probabilities == null ? 0.0 : probabilities.getOrDefault(choice, 0.0));
        return Math.max(0.0, Math.min(1.0, confidence));
    }

    /**
     * A token count from the response's {@code usage} block, or 0 when it is absent.
     *
     * <p><b>A zero here is a FREE call, so it is never silent.</b> These two counts are the
     * only billing input for this engine: the observability row bills a classify node from
     * them, and its own 50/50 split fallback only fires when the total is above zero. A
     * vendor renaming the field, or dropping the block on some response shape, would
     * therefore turn every classification into a zero-credit call with nothing in the logs
     * to say so. Cheap insurance against a revenue leak nobody would look for.
     *
     * <p><b>This fails toward a free call, and the configuration parser next to it fails
     * the other way.</b> Deliberate, and the difference is who pays for the mistake: a
     * mis-typed margin is the operator's to fix and costs nothing to refuse, while
     * discarding a classification the user already paid for, because a billing field
     * moved, throws away work to protect a fraction of a credit. The WARN is what turns
     * this into a bug report rather than a silent drift.
     */
    private int readUsage(Map<String, Object> response, String field) {
        if (response.get("usage") instanceof Map<?, ?> usage) {
            if (usage.get(field) instanceof Number number) {
                return number.intValue();
            }
            log.warn("TypeSafe response carries a usage block with no '{}': this classification "
                    + "bills zero credits. Check whether the vendor renamed the field.", field);
            return 0;
        }
        log.warn("TypeSafe response carries no usage block: this classification bills zero "
                + "credits. Check whether the vendor changed the response shape.");
        return 0;
    }

    /**
     * The probabilities rendered as one line, stored where the LLM path stores its
     * {@code reasoning}.
     *
     * <p>A decision model explains nothing, so that field would otherwise go empty and
     * every workflow already reading it would silently resolve to nothing on switching
     * engines. What goes there instead is not a narration invented to fill the gap: it is
     * the model's own distribution, which says more about a close call than a sentence
     * would.
     */
    /**
     * The answer as one ASSISTANT turn, because that is the only shape the execution
     * detail can show.
     *
     * <p><b>What was missing, and where.</b> agent &gt; metrics renders a CONVERSATION,
     * built from the rows the observability pipeline writes out of
     * {@code conversationMessages}. This path returned null there, so a decision
     * classification recorded the question sent to the model and nothing coming back: the
     * reader saw a USER turn alone and no answer. The verdict was never lost - it is on the
     * node output as {@code selected_category}, {@code confidence} and {@code reasoning},
     * and the run panel shows it - but the transcript is where someone looks to see what a
     * model actually replied, and there it was blank.
     *
     * <p><b>This is a transcription, not a narration.</b> A decision model returns a typed
     * choice, so there is no prose to quote and none is invented: the turn carries the label
     * it picked, the confidence it reported, and its own distribution. Writing a sentence
     * here ("I classified this as billing because...") would put words in the model's mouth
     * that it never produced, which is the one thing a transcript must not do.
     *
     * <p>No SYSTEM turn goes with it, for the same reason. The System One request has no
     * system prompt: the instructions travel per question, and the whole request (instructions,
     * state and criteria) is already reported as the USER turn. Inventing one to fill the panel
     * would be the same fabrication.
     */
    private ConversationMessageDto answerTurn(String choice, double confidence,
                                               Map<String, Double> probabilities) {
        StringBuilder content = new StringBuilder(choice);
        content.append(String.format(Locale.ROOT, " (confidence %.2f)", confidence));
        String distribution = describeProbabilities(probabilities);
        if (distribution != null) {
            content.append(NL_CHAR).append(distribution);
        }
        return new ConversationMessageDto("ASSISTANT", content.toString(), null, null);
    }

    private String describeProbabilities(Map<String, Double> probabilities) {
        if (probabilities == null || probabilities.isEmpty()) {
            return null;
        }
        return probabilities.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(REASONING_TOP_N)
                // Locale.ROOT, not the JVM default: this string is data a workflow reads and
                // may parse, and on a French-locale host the default renders 0.94 as "0,94".
                .map(e -> String.format(Locale.ROOT, "%s %.2f", e.getKey(), e.getValue()))
                .collect(Collectors.joining(", "));
    }

    // ===== Failures =====

    /**
     * The documented status codes, said in terms of what the reader can do. 529 and 429
     * are worth telling apart from a key problem: they pass on a retry, and on a classify
     * node the standing alternative is to run the same node on a chat model.
     */
    private String describeHttpFailure(HttpStatusCodeException e) {
        int status = e.getStatusCode().value();
        return switch (status) {
            case 401 -> "TypeSafe rejected the API key configured for this platform";
            case 422 -> "TypeSafe rejected the classification request: " + shortBody(e);
            case 429 -> "TypeSafe rate limit reached; this classification can be retried";
            case 529 -> "TypeSafe is overloaded; this classification can be retried";
            default -> "TypeSafe returned HTTP " + status + ": " + shortBody(e);
        };
    }

    /**
     * A transport failure said in terms of what happened and what the reader can do about it.
     *
     * <p>Kept separate from {@link #describeHttpFailure} because the two are different events
     * with different owners: a status code is TypeSafe answering, a reset is TypeSafe not
     * answering. The previous wording, a bare {@code "Classification error: " + message},
     * surfaced raw socket vocabulary ({@code recvAddress(..) failed with error(-104)}) in a
     * workflow run, where it reads as a platform bug rather than as the provider going quiet.
     *
     * <p>It deliberately does NOT promise that nothing was charged or that nothing was sent
     * twice. A read timeout fires after the request was fully written, so the model may well
     * have processed it and billed for it. That alone settles it; a reassurance that can be
     * wrong is worse than none.
     *
     * <p>(The JDK's own POST replay is NOT the reason. A body serialised from an
     * {@code HttpEntity} carries no Content-Length, so {@code SimpleClientHttpRequest} puts the
     * connection in chunked streaming mode, and a streamed body cannot be replayed:
     * {@code sun.net.http.retryPost} does not apply here.)
     */
    private String describeTransportFailure(org.springframework.web.client.ResourceAccessException e) {
        String cause = rootCauseOf(e);
        return "TypeSafe did not answer: the connection to " + apiUrl
                + " failed before a response arrived (" + cause
                + "). This classification produced no result; whether the request reached the model"
                + " is not something this call can tell.";
    }

    /**
     * Deepest cause, which for a transport failure is the socket error that actually happened.
     *
     * <p>The walk is BOUNDED as well as self-cause guarded. Nothing in the JDK or Spring
     * transport stack builds a cause cycle, but this runs on the thread executing a classify
     * node: an unbounded walk would turn a theoretical malformed chain into a hung workflow,
     * and the depth costs nothing to cap.
     */
    private String rootCauseOf(Throwable e) {
        Throwable current = e;
        for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
            Throwable cause = current.getCause();
            if (cause == null || cause == current) {
                break;
            }
            current = cause;
        }
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }

    private String shortBody(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return "(no response body)";
        }
        return body.length() > 300 ? body.substring(0, 300) + "..." : body;
    }

    private ClassifyResponseDto failure(String error, String provider, String model,
                                         String sent, long durationMs) {
        return new ClassifyResponseDto(false, null, 0, null, error, durationMs, provider, model,
                0, 0, 0, null, null, sent, null, null);
    }

    /** A node configuration this endpoint cannot be asked about. */
    static class ClassifyInputException extends RuntimeException {
        ClassifyInputException(String message) {
            super(message);
        }
    }
}
