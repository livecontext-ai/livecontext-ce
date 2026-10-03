package com.apimarketplace.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@DisplayName("auth-service to publication-service, for the apps of a partner offer")
class PartnerOfferAppsClientTest {

    private static final UUID APP = UUID.fromString("6f1c0d2e-0000-4000-8000-00000000000a");
    private static final String ACQUIRE = "http://pub/api/internal/publications/" + APP + "/acquire";

    private MockRestServiceServer server;
    private MockRestServiceServer cardsServer;
    private PartnerOfferAppsClient client;

    @BeforeEach
    void setUp() {
        RestTemplate installs = new RestTemplate();
        RestTemplate cards = new RestTemplate();
        server = MockRestServiceServer.bindTo(installs).build();
        cardsServer = MockRestServiceServer.bindTo(cards).build();
        client = new PartnerOfferAppsClient(installs, cards, "http://pub");
    }

    @Test
    @DisplayName("offerable: asks for the partner's ids and keeps the cards that carry an id")
    void offerable() {
        cardsServer.expect(requestTo("http://pub/api/internal/publications/partner-offer-apps"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"publisherId\":\"42\",\"ids\":[\"" + APP + "\"]}"))
                .andRespond(withSuccess("{\"apps\":[{\"id\":\"" + APP + "\",\"title\":\"Invoice chaser\"},{\"title\":\"no id\"}]}",
                        MediaType.APPLICATION_JSON));

        assertThat(client.offerable(42L, List.of(APP.toString())))
                .singleElement().satisfies(card -> assertThat(card).containsEntry("title", "Invoice chaser"));
        cardsServer.verify();
    }

    @Test
    @DisplayName("offerable: nothing to ask, no call; a service that cannot answer throws for the caller to decide")
    void offerableEdges() {
        assertThat(client.offerable(42L, List.of())).isEmpty();
        assertThat(client.offerable(null, List.of(APP.toString()))).isEmpty();

        cardsServer.expect(requestTo("http://pub/api/internal/publications/partner-offer-apps")).andRespond(withServerError());
        assertThatThrownBy(() -> client.offerable(42L, List.of(APP.toString()))).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("regression: card reads (an anonymous page waits on them) and installs go through separate clients, so a slow install timeout never holds the page")
    void cardsAndInstallsAreSeparate() {
        cardsServer.expect(requestTo("http://pub/api/internal/publications/partner-offer-apps"))
                .andRespond(withSuccess("{\"apps\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ACQUIRE)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.offerable(42L, List.of(APP.toString()));
        client.install(APP, 7L);

        cardsServer.verify();
        server.verify();
    }

    @Test
    @DisplayName("install: acquires as the client (their default workspace) and reads a 2xx as installed")
    void installs() {
        server.expect(requestTo(ACQUIRE)).andExpect(method(HttpMethod.POST)).andExpect(header("X-User-ID", "7"))
                .andRespond(withSuccess("{\"workflowId\":\"w1\"}", MediaType.APPLICATION_JSON));

        assertThat(client.install(APP, 7L).outcome()).isEqualTo(PartnerOfferAppsClient.Outcome.INSTALLED);
        server.verify();
    }

    private PartnerOfferAppsClient.Install answer(HttpStatus status, String body) {
        server.reset();
        server.expect(requestTo(ACQUIRE)).andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body));
        return client.install(APP, 7L);
    }

    @Test
    @DisplayName("install: already in the workspace (a replay, a try that timed out but finished) or the partner's own counts as installed")
    void alreadyThere() {
        assertThat(answer(HttpStatus.BAD_REQUEST, "{\"error\":\"Publication already acquired\"}").outcome())
                .isEqualTo(PartnerOfferAppsClient.Outcome.INSTALLED);
        assertThat(answer(HttpStatus.BAD_REQUEST, "{\"error\":\"Cannot acquire your own publication\"}").outcome())
                .isEqualTo(PartnerOfferAppsClient.Outcome.INSTALLED);
    }

    @Test
    @DisplayName("install: a withdrawn app, an app this deployment cannot run, or an unknown one is refused for good, with the reason")
    void refused() {
        var inactive = answer(HttpStatus.BAD_REQUEST, "{\"error\":\"Publication is not active\"}");
        assertThat(inactive.outcome()).isEqualTo(PartnerOfferAppsClient.Outcome.REFUSED);
        assertThat(inactive.reason()).contains("Publication is not active");

        var ceOnly = answer(HttpStatus.FORBIDDEN, "{\"error\":\"self-hosted only\",\"code\":\"CE_EXCLUSIVE\"}");
        assertThat(ceOnly.outcome()).isEqualTo(PartnerOfferAppsClient.Outcome.REFUSED);
        assertThat(ceOnly.reason()).contains("CE_EXCLUSIVE");

        assertThat(answer(HttpStatus.NOT_FOUND, "not json").outcome()).isEqualTo(PartnerOfferAppsClient.Outcome.REFUSED);
    }

    @Test
    @DisplayName("regression: the plan's refusals right after payment (its app quota, a feature it lacks) and an unresolved workspace are tried again, the plan's ones named as such")
    void planAndWorkspaceRetried() {
        var quota = answer(HttpStatus.CONFLICT, "{\"error\":\"PLAN_RESOURCE_LIMIT_EXCEEDED\",\"resourceType\":\"APPLICATION\"}");
        assertThat(quota.outcome()).isEqualTo(PartnerOfferAppsClient.Outcome.RETRY);
        assertThat(quota.reason()).isEqualTo(PartnerOfferAppsClient.PLAN_LIMIT);

        var upgrade = answer(HttpStatus.FORBIDDEN, "{\"error\":\"needs Pro\",\"code\":\"PLAN_UPGRADE_REQUIRED\"}");
        assertThat(upgrade.outcome()).isEqualTo(PartnerOfferAppsClient.Outcome.RETRY);
        assertThat(upgrade.reason()).isEqualTo(PartnerOfferAppsClient.PLAN_UPGRADE_REQUIRED);

        var workspace = answer(HttpStatus.BAD_REQUEST,
                "{\"error\":\"organizationId required after V261 (tenantId=7, publicationId=x) - user has no default organization\"}");
        assertThat(workspace.outcome()).isEqualTo(PartnerOfferAppsClient.Outcome.RETRY);
    }

    @Test
    @DisplayName("install: throttling or a down service are tried again later")
    void retried() {

        assertThat(answer(HttpStatus.TOO_MANY_REQUESTS, "{}").outcome()).isEqualTo(PartnerOfferAppsClient.Outcome.RETRY);
        assertThat(answer(HttpStatus.INTERNAL_SERVER_ERROR, "{\"error\":\"clone failed\"}").outcome())
                .isEqualTo(PartnerOfferAppsClient.Outcome.RETRY);
        assertThat(answer(HttpStatus.BAD_GATEWAY, "").outcome()).isEqualTo(PartnerOfferAppsClient.Outcome.RETRY);
    }
}
