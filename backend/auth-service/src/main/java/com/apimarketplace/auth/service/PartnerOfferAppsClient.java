package com.apimarketplace.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * auth-service to publication-service, for the applications a partner gives with an offer: which
 * of them may be offered (and how a card draws them), and installing one in a paying client's
 * workspace. A plain RestTemplate rather than the publication-client jar: that jar brings
 * auth-client with it, whose beans would be component-scanned into this service.
 */
@Service
public class PartnerOfferAppsClient {

    private static final Logger log = LoggerFactory.getLogger(PartnerOfferAppsClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** What one install try came to. */
    public enum Outcome {
        /** In the client's workspace now, or already was. */
        INSTALLED,
        /** Worth another try later: the service was down, or the client's new plan not active yet. */
        RETRY,
        /** Never going to work: the app was withdrawn, or the plan cannot run it. */
        REFUSED
    }

    public record Install(Outcome outcome, String reason) {}

    /** Installs: one clone can take a while. */
    private final RestTemplate installs;
    /** Card reads: an anonymous page waits on them, so they give up fast (the page then shows no apps). */
    private final RestTemplate cards;
    private final String baseUrl;

    @Autowired
    public PartnerOfferAppsClient(@Value("${services.publication-url:http://localhost:8092}") String baseUrl,
                                  @Value("${partner-offer.install-timeout-ms:60000}") int installTimeoutMs,
                                  @Value("${partner-offer.cards-timeout-ms:3000}") int cardsTimeoutMs) {
        // Installing clones the app's workflow, interfaces and tables: longer than the shared
        // client's 15 s on a big app. A timeout is not a failure either way (see install).
        this(http(5_000, installTimeoutMs), http(2_000, cardsTimeoutMs), baseUrl);
    }

    PartnerOfferAppsClient(RestTemplate installs, RestTemplate cards, String baseUrl) {
        this.installs = installs;
        this.cards = cards;
        this.baseUrl = baseUrl;
    }

    private static RestTemplate http(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        return new RestTemplate(factory);
    }

    /**
     * The partner's applications among {@code ids} an offer may give, in the order asked, each as
     * the fields a marketplace card draws ({@code id}, {@code title}, {@code publisherName},
     * {@code nodeIcons}...). Throws when publication-service cannot answer: the caller decides
     * whether that blocks (creating an offer) or not (showing one).
     */
    public List<Map<String, Object>> offerable(Long partnerUserId, List<String> ids) {
        if (partnerUserId == null || ids == null || ids.isEmpty()) return List.of();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of("publisherId", String.valueOf(partnerUserId), "ids", ids);
        Map<String, Object> response = cards.exchange(baseUrl + "/api/internal/publications/partner-offer-apps",
                HttpMethod.POST, new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<Map<String, Object>>() {}).getBody();
        List<Map<String, Object>> apps = new ArrayList<>();
        if (response != null && response.get("apps") instanceof List<?> raw) {
            for (Object app : raw) {
                if (app instanceof Map<?, ?> m && m.get("id") instanceof String) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> card = (Map<String, Object>) m;
                    apps.add(card);
                }
            }
        }
        return apps;
    }

    /**
     * Install one application in the client's workspace (their default one), as the marketplace
     * would: the client's own copy, counted against their plan. Never throws.
     */
    public Install install(UUID publicationId, Long clientUserId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-User-ID", String.valueOf(clientUserId));
        try {
            installs.exchange(baseUrl + "/api/internal/publications/" + publicationId + "/acquire",
                    HttpMethod.POST, new HttpEntity<>(headers), String.class);
            return new Install(Outcome.INSTALLED, null);
        } catch (HttpClientErrorException e) {
            return classify(e);
        } catch (RestClientException e) {
            // Down, or slower than the timeout. A timed-out install may still finish on the other
            // side: the next try then finds it "already acquired" and records it installed.
            log.warn("Partner offer install of {} for user {} deferred: {}", publicationId, clientUserId, e.getMessage());
            return new Install(Outcome.RETRY, UNREACHABLE + ": " + e.getClass().getSimpleName());
        }
    }

    /** No answer (a timeout, a refused connection): the install may still be running on the other side. */
    public static final String UNREACHABLE = "unreachable";
    /** The client's plan cannot hold one more app (its applications quota). */
    public static final String PLAN_LIMIT = "plan_limit";
    /** The app needs a feature the client's plan does not have. */
    public static final String PLAN_UPGRADE_REQUIRED = "plan_upgrade_required";

    /**
     * What publication-service's refusal of an install means for the delivery. The two "already
     * there" messages are publication-service's own wording, pinned on its side by
     * {@code WorkflowPublicationServiceAcquireValidationTest}: a change there must not turn an install that
     * succeeded into a failure here.
     */
    static Install classify(HttpClientErrorException e) {
        int status = e.getStatusCode().value();
        Map<?, ?> body = parse(e.getResponseBodyAsString());
        String error = body.get("error") instanceof String s ? s : "";
        String code = body.get("code") instanceof String c ? c : "";
        if (status == 400) {
            String lower = error.toLowerCase();
            // Already in their workspace (a replay, a second pod, a timed-out try that finished),
            // or theirs to begin with (a partner paying through their own link).
            if (lower.contains("already acquired") || lower.contains("cannot acquire your own")) {
                return new Install(Outcome.INSTALLED, null);
            }
            // The client's workspace could not be resolved (auth-service, asked back, did not answer):
            // passing, not a refusal of this client.
            if (lower.contains("organizationid required")) return new Install(Outcome.RETRY, "workspace_unresolved");
            return new Install(Outcome.REFUSED, "refused: " + error);
        }
        // The plan's applications quota, or a feature the plan lacks: right after the payment the
        // paid plan may not be switched on yet, so both are retried for a while before giving up.
        if (status == 409 && "PLAN_RESOURCE_LIMIT_EXCEEDED".equals(error)) return new Install(Outcome.RETRY, PLAN_LIMIT);
        if (status == 403 && "PLAN_UPGRADE_REQUIRED".equals(code)) return new Install(Outcome.RETRY, PLAN_UPGRADE_REQUIRED);
        if (status == 429) return new Install(Outcome.RETRY, "throttled");
        return new Install(Outcome.REFUSED, "refused " + status + ": " + (code.isEmpty() ? error : code));
    }

    private static Map<?, ?> parse(String raw) {
        try {
            return raw == null || raw.isBlank() ? Map.of() : JSON.readValue(raw, Map.class);
        } catch (Exception notJson) {
            return Map.of();
        }
    }
}
