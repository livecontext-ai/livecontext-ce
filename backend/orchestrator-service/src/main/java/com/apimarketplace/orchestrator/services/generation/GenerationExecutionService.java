package com.apimarketplace.orchestrator.services.generation;

import com.apimarketplace.common.web.InternalGatewaySigner;
import com.apimarketplace.common.web.OrgContextHeaderForwarder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP client the {@code agent:generate} node uses to run one generation.
 *
 * <p><b>Why this is a thin client and not the generation logic itself.</b>
 * Everything that decides what a generation costs and whether it succeeds lives
 * in catalog-service: the model registry (which endpoint runs a model id), the
 * request builder (which unified parameters the model accepts, and the billable
 * quantity they imply), the credential resolution, the credit reservation, the
 * async polling and the asset storage. Orchestrator cannot read
 * {@code catalog.api_tools} at all, so re-deriving any of it here would mean a
 * second registry to keep in sync, and a second place a price could be computed
 * differently from the price actually charged.
 *
 * <p>So the node sends a model id and the unified parameters, and gets back the
 * finished result. In particular it never sends a quantity: the amount billed is
 * derived on the catalog side from the parameters it has already validated. A
 * quantity that travelled from here would be a quantity a workflow author could
 * set to zero.
 */
@Service
public class GenerationExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(GenerationExecutionService.class);

    static final String GENERATION_PATH = "/api/internal/catalog/generation/execute";

    /**
     * Shown when catalog-service answers 404 for the generation endpoint: it is
     * only registered when the installation turns generation on, so the node can
     * never work here until somebody does.
     */
    static final String GENERATION_UNAVAILABLE_MESSAGE =
        "Generation is not enabled on this installation, and producing the asset is this node's "
            + "whole purpose. Only the user or an administrator can turn it on - tell them this node "
            + "needs it, or remove the node. It will fail on every run here until it is enabled.";

    /**
     * Outcome of one generation.
     *
     * @param success true when an asset was produced
     * @param data    the generation result ({@code model}, {@code kind},
     *                {@code provider}, {@code file}, {@code billed_quantity},
     *                {@code billed_unit}, {@code provider_response}), empty on failure
     * @param error   why nothing was produced, null on success
     */
    public record GenerationResult(boolean success, Map<String, Object> data, String error) {

        public static GenerationResult failed(String error) {
            return new GenerationResult(false, Map.of(), error);
        }

        /**
         * A failure that still carries something the caller needs.
         *
         * <p>A FAILED generation can have been PAID for: billing commits before
         * the asset is fetched and stored, so a transient fetch failure comes
         * back with the provider's own short-lived link under {@code
         * asset_url}. The generation endpoint deliberately puts that link on a
         * failing response for exactly this reason, and dropping it here is how
         * a charged asset becomes unrecoverable, with only a sentence to show
         * for it.
         */
        public static GenerationResult failed(String error, Map<String, Object> data) {
            return new GenerationResult(false, data == null ? Map.of() : data, error);
        }

        /**
         * The provider's short-lived link to an asset that was produced and
         * charged for but never stored, or null when there is none.
         */
        public String recoverableAssetUrl() {
            Object url = data == null ? null : data.get("asset_url");
            if (url == null) return null;
            String text = String.valueOf(url).trim();
            return text.isEmpty() ? null : text;
        }
    }

    private final RestTemplate restTemplate;
    /**
     * Ordinary timeouts, for the calls that are NOT a generation.
     *
     * <p>{@code generationRestTemplate} carries a 25 minute read window, sized
     * for a provider finishing a video, and its own comment says no other call
     * should inherit it. Listing models is a plain read on a request an agent is
     * waiting on: a stalled catalog would hang the help for twenty-five minutes
     * rather than fail.
     */
    private final RestTemplate readRestTemplate;
    private final String catalogBaseUrl;

    /**
     * Signs the model listing. {@code /api/generation/models} is NOT among catalog-service's
     * gateway public paths (only {@code /api/internal/} is, which is why the generation call
     * itself needs no signature), so an unsigned read is refused 401 before it reaches the
     * controller, and that 401 was folded into "served, but no models".
     */
    private final String gatewaySecretKey;

    static final String MODELS_PATH = "/api/generation/models";
    static final String MODELS_PROVIDER_ID = "internal-orchestrator-generation-models";

    public GenerationExecutionService(
            @Qualifier("generationRestTemplate") RestTemplate restTemplate,
            // NAMED, because there are four RestTemplate beans and none is
            // @Primary: by-type injection is ambiguous and by-name matches the
            // BEAN name, not the parameter name, so an unqualified
            // 'readRestTemplate' resolves to nothing and the whole service
            // fails to start. Unit tests build this class by hand and cannot
            // see it; the context test can.
            @Qualifier("restTemplate") RestTemplate readRestTemplate,
            @Value("${orchestrator.catalog.base-url:http://localhost:8081}") String catalogBaseUrl,
            @Value("${gateway.filter.secret-key:${GATEWAY_SECRET_KEY:}}") String gatewaySecretKey) {
        this.restTemplate = restTemplate;
        this.readRestTemplate = readRestTemplate;
        this.catalogBaseUrl = catalogBaseUrl;
        this.gatewaySecretKey = gatewaySecretKey;
    }

    /**
     * Every generation model the platform offers.
     *
     * <p><b>A read, and free.</b> It exists here because the paid
     * {@code generation} tool is opt-in per agent, deliberately: a create spends
     * the customer's credits at the model's own rate. Listing spends nothing,
     * yet it was reachable only through that same gated tool, so an agent
     * without the opt-in could not learn a single model id. Since {@code model}
     * is the one required parameter of a generate node and its ids cannot be
     * guessed, that agent could not build the node at all, and had no way to say
     * why. Discovery therefore rides on the workflow tool, which every builder
     * already holds; spending still requires the opt-in.
     *
     * <p>Answers with an empty list rather than throwing when generation is not
     * served on this install (a self-hosted default answers 404 on the whole
     * surface): "there are none" is the truthful answer to a reader, and it must
     * not take the rest of the help down with it.
     *
     * @return the models, or empty. {@link #isGenerationServed()} tells the two
     *         empties apart: a 404 means this install does not serve generation
     *         at all, anything else means the catalogue could not be reached and
     *         no claim about the installation may be made from it.
     */
    /**
     * One answer, carrying both halves.
     *
     * <p>A record rather than a field read afterwards: this service is a
     * singleton and its callers are concurrent request threads, so "list, then
     * ask what that told us" was two unsynchronised reads of shared state. A
     * neighbouring request hitting a blip could hand this one a verdict about a
     * call it never made, and the answer built from it is a confident sentence
     * about the installation.
     *
     * @param models the models, empty when none could be listed
     * @param served TRUE it answered, FALSE it answered 404 (generation is off
     *               here), null it could not be reached and nothing is known
     */
    public record ModelCatalogue(List<Map<String, Object>> models, Boolean served) {}

    /** @deprecated use {@link #readModels()}, which cannot race. */
    @Deprecated
    public List<Map<String, Object>> listModels() {
        return readModels().models();
    }

    @SuppressWarnings("unchecked")
    public ModelCatalogue readModels() {
        try {
            String url = catalogBaseUrl + MODELS_PATH;
            // v1 then v2 (CASA LC-035), signed here because the shared read template carries no
            // v2 interceptor. The URL is fixed, so the URI signed is the URI sent. A blank secret
            // (verification off) leaves the call unsigned, as before.
            HttpHeaders headers = new HttpHeaders();
            InternalGatewaySigner.stamp(headers, MODELS_PROVIDER_ID, gatewaySecretKey);
            InternalGatewaySigner.stampV2(headers, HttpMethod.GET.name(), java.net.URI.create(url), gatewaySecretKey);
            ResponseEntity<Map> response = readRestTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            Map<String, Object> payload = response.getBody();
            if (payload == null || !(payload.get("models") instanceof List<?> models)) {
                return new ModelCatalogue(List.of(), Boolean.TRUE);
            }
            List<Map<String, Object>> rows = new ArrayList<>(models.size());
            for (Object row : models) {
                if (row instanceof Map<?, ?> m) rows.add((Map<String, Object>) m);
            }
            return new ModelCatalogue(rows, Boolean.TRUE);
        } catch (HttpStatusCodeException e) {
            // A 404 is the installation SAYING generation is off: the whole
            // surface is config-gated and unregistered here. That is a fact a
            // reader can act on, and it is not the same as a hiccup.
            logger.warn("[Generation] listing models answered {}: {}",
                    e.getStatusCode().value(), e.getMessage());
            return new ModelCatalogue(List.of(), e.getStatusCode().value() != 404);
        } catch (Exception e) {
            // Anything else says nothing about the installation.
            logger.warn("[Generation] could not list models for the workflow help: {}", e.getMessage());
            return new ModelCatalogue(List.of(), null);
        }
    }



    /**
     * Run one generation.
     *
     * @param tenantId         executing workflow's tenant, the owner the asset is stored under
     * @param runId            workflow run the credit debit is scoped to
     * @param nodeId           producing node key ({@code agent:<label>}), recorded on the debit
     * @param model            public generation model id
     * @param params           unified generation parameters, already resolved from templates
     * @param credentialSource {@code "user"} or {@code "platform"}, or null to let the
     *                         platform decide
     * @param credentialId     which of the author's OWN keys to run on, or null to run
     *                         on the account's default key for the provider. Honoured
     *                         only beside {@code "user"}, by the catalog.
     */
    @SuppressWarnings("unchecked")
    public GenerationResult generate(String tenantId, String runId, String nodeId,
                                      String model, Map<String, Object> params,
                                      String credentialSource, Long credentialId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("params", params == null ? Map.of() : params);
        if (credentialSource != null && !credentialSource.isBlank()) {
            body.put("credential_source", credentialSource);
        }
        // Sent whenever the node pinned one, on either branch. Which branch may
        // ignore it is the catalog's rule, and it is applied there, once: a
        // second copy of it here would be a second thing to keep true when that
        // rule changes.
        if (credentialId != null) {
            body.put("credential_id", credentialId);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (tenantId != null && !tenantId.isBlank()) {
            headers.set("X-User-ID", tenantId);
        }
        OrgContextHeaderForwarder.forward(headers);
        // RUN scope, the same shape CatalogToolsGateway sends for an ordinary
        // workflow tool call, so the credit debit lands on this run and this step.
        if (runId != null && !runId.isBlank()) {
            headers.set("X-Lc-Billing-Scope-Kind", "RUN");
            headers.set("X-Lc-Billing-Scope-Id", runId);
            if (nodeId != null && !nodeId.isBlank()) {
                headers.set("X-Lc-Billing-Step-Id", nodeId);
            }
        }

        try {
            ResponseEntity<Map> response = restTemplate.exchange(
                    catalogBaseUrl + GENERATION_PATH, HttpMethod.POST,
                    new HttpEntity<>(body, headers), Map.class);

            Map<String, Object> payload = response.getBody();
            if (payload == null) {
                return GenerationResult.failed("The generation service returned no response.");
            }
            if (!Boolean.TRUE.equals(payload.get("success"))) {
                Object error = payload.get("error");
                // The failing response may still carry data: see
                // GenerationResult.failed(String, Map). Reading it here is what
                // makes a paid-but-unstored asset recoverable.
                Object failureData = payload.get("data");
                return GenerationResult.failed(
                        error == null
                                ? "The generation failed without an explanation."
                                : String.valueOf(error),
                        failureData instanceof Map<?, ?> map ? (Map<String, Object>) map : null);
            }
            Object data = payload.get("data");
            if (!(data instanceof Map<?, ?> map)) {
                return GenerationResult.failed("The generation reported success but returned no result.");
            }
            return new GenerationResult(true, (Map<String, Object>) map, null);

        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 404) {
                logger.error("Generation endpoint absent on catalog-service (generation disabled)");
                return GenerationResult.failed(GENERATION_UNAVAILABLE_MESSAGE);
            }
            logger.error("Generation call failed: status={}, body={}", e.getStatusCode(),
                    com.apimarketplace.common.logging.PayloadLogSafety.describeText(e.getResponseBodyAsString(), 300));
            // The upstream sentence is kept, not replaced by the status code.
            // Every refusal this path can carry names its own cause and its own
            // remedy (add a size, use your own key, the plan excludes this), and
            // an HTTP number names none of them. Today the generation endpoint
            // answers 200 with success:false so this branch is rarely taken, but
            // the sibling catalog controller answers a real 402: if the two ever
            // converge, replacing the body here would delete every one of those
            // sentences at once.
            String detail = e.getResponseBodyAsString();
            return GenerationResult.failed(
                    detail == null || detail.isBlank()
                            ? "The generation service refused the call (HTTP "
                                    + e.getStatusCode().value() + ")."
                            : "The generation service refused the call (HTTP "
                                    + e.getStatusCode().value() + "): " + detail);
        } catch (RestClientException e) {
            // DO NOT SAY THIS WAS NOT CHARGED. The request left this process; a
            // read timeout, a reset or a dropped connection says only that the
            // ANSWER did not come back. The generation may well be running, and
            // billing commits on the catalog side, so a message implying nothing
            // happened invites a re-run that pays for a second one.
            logger.error("Generation call failed: {}", e.getMessage(), e);
            return GenerationResult.failed(
                    "The generation service could not be reached, so it is unknown whether this "
                            + "generation ran: " + e.getMessage()
                            + ". Do not run it again until that is checked, because a call that did "
                            + "start has already been charged.");
        }
    }
}
