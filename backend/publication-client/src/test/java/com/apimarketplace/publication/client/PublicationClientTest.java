package com.apimarketplace.publication.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicationClient")
class PublicationClientTest {

    @Mock
    private RestTemplate restTemplate;

    private PublicationClient publicationClient;

    private static final String BASE_URL = "http://localhost:8092";
    private static final String TENANT_ID = "auth0|tenant-test";

    @BeforeEach
    void setUp() {
        publicationClient = new PublicationClient(restTemplate, BASE_URL);
    }

    @Nested
    @DisplayName("publishAgent - structured 422 refusal mapping")
    class PublishAgentValidationMapping {

        private static final String URL = BASE_URL + "/api/internal/publications/publish-agent";

        private void stub422(String body) {
            when(restTemplate.exchange(eq(URL), eq(HttpMethod.POST), any(HttpEntity.class),
                    any(ParameterizedTypeReference.class)))
                    .thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                            org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                            "Unprocessable Entity",
                            new org.springframework.http.HttpHeaders(),
                            body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            java.nio.charset.StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("422 with the {error, message, violations} body surfaces as a typed PublicationValidationException")
        void maps422ToTypedException() {
            stub422("{\"error\":\"AGENT_ALL_ACCESS_NOT_PUBLISHABLE\","
                    + "\"message\":\"This agent cannot be published because it has 'All' access on some resource types.\","
                    + "\"violations\":[{\"agentId\":\"a1\",\"agentName\":\"Support Copilot\",\"root\":true,"
                    + "\"families\":[\"tables\"]}]}");

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() ->
                    publicationClient.publishAgent(Map.of("agentConfigId", "a1"), TENANT_ID, null));

            assertThat(thrown).isInstanceOf(PublicationValidationException.class);
            PublicationValidationException e = (PublicationValidationException) thrown;
            assertThat(e.getErrorCode()).isEqualTo("AGENT_ALL_ACCESS_NOT_PUBLISHABLE");
            assertThat(e.getMessage()).contains("'All' access");
            assertThat(e.getBody().get("violations")).isInstanceOf(java.util.List.class);
            java.util.Map<?, ?> violation = (java.util.Map<?, ?>) ((java.util.List<?>) e.getBody().get("violations")).get(0);
            assertThat(violation.get("agentName")).isEqualTo("Support Copilot");
        }

        @Test
        @DisplayName("422 with a malformed (non-JSON) body degrades to the raw body as message, empty details")
        void malformed422BodyDegradesGracefully() {
            stub422("not json at all");

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() ->
                    publicationClient.publishAgent(Map.of("agentConfigId", "a1"), TENANT_ID, null));

            assertThat(thrown).isInstanceOf(PublicationValidationException.class);
            PublicationValidationException e = (PublicationValidationException) thrown;
            assertThat(e.getErrorCode()).isNull();
            assertThat(e.getMessage()).isEqualTo("not json at all");
            assertThat(e.getBody()).isEmpty();
        }

        @Test
        @DisplayName("a non-422 HTTP error keeps the legacy opaque RuntimeException (only validation refusals are typed)")
        void non422KeepsLegacyRuntimeException() {
            when(restTemplate.exchange(eq(URL), eq(HttpMethod.POST), any(HttpEntity.class),
                    any(ParameterizedTypeReference.class)))
                    .thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                            org.springframework.http.HttpStatus.BAD_REQUEST,
                            "Bad Request",
                            new org.springframework.http.HttpHeaders(),
                            "{\"error\":\"agentConfigId is required\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                            java.nio.charset.StandardCharsets.UTF_8));

            Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() ->
                    publicationClient.publishAgent(Map.of(), TENANT_ID, null));

            assertThat(thrown)
                    .isInstanceOf(RuntimeException.class)
                    .isNotInstanceOf(PublicationValidationException.class)
                    .hasMessageContaining("Failed to publish agent");
        }
    }

    @Nested
    @DisplayName("acquireAgentPublication")
    class AcquireAgentPublication {

        @Test
        @DisplayName("sets explicit organization header for scoped acquisition")
        void acquireAgentPublicationSetsExplicitOrganizationHeader() {
            UUID publicationId = UUID.randomUUID();
            String organizationId = "22222222-2222-4222-8222-222222222222";
            when(restTemplate.exchange(eq(BASE_URL + "/api/internal/publications/" + publicationId + "/acquire-agent"),
                    eq(HttpMethod.POST), any(HttpEntity.class), any(ParameterizedTypeReference.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("agentId", UUID.randomUUID().toString())));

            publicationClient.acquireAgentPublication(publicationId, TENANT_ID, organizationId);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(BASE_URL + "/api/internal/publications/" + publicationId + "/acquire-agent"),
                    eq(HttpMethod.POST), entityCaptor.capture(), any(ParameterizedTypeReference.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo(organizationId);
        }
    }

    @Nested
    @DisplayName("findStatusesByPublicationIds")
    class FindStatusesByPublicationIds {

        private static final String URL = BASE_URL + "/api/internal/publications/publication-statuses-by-ids";

        @Test
        @DisplayName("posts the publication ids and folds the response into a (pubId → status) map")
        void postsIdsAndParsesStatuses() {
            UUID activePub = UUID.randomUUID();
            UUID inactivePub = UUID.randomUUID();
            when(restTemplate.exchange(eq(URL), eq(HttpMethod.POST), any(HttpEntity.class),
                    any(ParameterizedTypeReference.class)))
                    .thenReturn(ResponseEntity.ok(Map.of(
                            activePub.toString(), "ACTIVE",
                            inactivePub.toString(), "INACTIVE")));

            Map<UUID, String> statuses =
                    publicationClient.findStatusesByPublicationIds(java.util.List.of(activePub, inactivePub), TENANT_ID);

            assertThat(statuses).containsEntry(activePub, "ACTIVE").containsEntry(inactivePub, "INACTIVE");

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(URL), eq(HttpMethod.POST), entityCaptor.capture(),
                    any(ParameterizedTypeReference.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) entityCaptor.getValue().getBody();
            assertThat(body).containsKey("publicationIds");
        }

        @Test
        @DisplayName("empty input short-circuits without an HTTP call")
        void emptyInputSkipsHttp() {
            Map<UUID, String> statuses =
                    publicationClient.findStatusesByPublicationIds(java.util.List.of(), TENANT_ID);

            assertThat(statuses).isEmpty();
            verify(restTemplate, org.mockito.Mockito.never()).exchange(
                    any(String.class), any(HttpMethod.class), any(HttpEntity.class),
                    any(ParameterizedTypeReference.class));
        }

        @Test
        @DisplayName("fail-open: an HTTP error yields an empty map (board falls open, never breaks)")
        void httpErrorFailsOpen() {
            when(restTemplate.exchange(eq(URL), eq(HttpMethod.POST), any(HttpEntity.class),
                    any(ParameterizedTypeReference.class)))
                    .thenThrow(new RuntimeException("publication-service down"));

            Map<UUID, String> statuses =
                    publicationClient.findStatusesByPublicationIds(java.util.List.of(UUID.randomUUID()), TENANT_ID);

            assertThat(statuses).isEmpty();
        }
    }

    @Nested
    @DisplayName("findResourcePublicationStatuses")
    class FindResourcePublicationStatuses {

        private static final String URL = BASE_URL + "/api/internal/publications/resource-publication-statuses";

        @Test
        @DisplayName("posts {type, resourceIds} and folds the response into a (resourceId → {status, reason}) map")
        void postsIdsAndParsesRefs() {
            when(restTemplate.exchange(eq(URL), eq(HttpMethod.POST), any(HttpEntity.class),
                    any(ParameterizedTypeReference.class)))
                    .thenReturn(ResponseEntity.ok(Map.of(
                            "10", Map.of("status", "ACTIVE"),
                            "11", Map.of("status", "REJECTED", "rejectionReason", "off-topic"))));

            Map<String, PublicationClient.ResourcePublicationStatusRef> refs =
                    publicationClient.findResourcePublicationStatuses("TABLE", java.util.List.of("10", "11"), TENANT_ID);

            assertThat(refs.get("10").status()).isEqualTo("ACTIVE");
            assertThat(refs.get("10").published()).isTrue();
            assertThat(refs.get("11").status()).isEqualTo("REJECTED");
            assertThat(refs.get("11").rejectionReason()).isEqualTo("off-topic");
            assertThat(refs.get("11").published()).isFalse();

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(URL), eq(HttpMethod.POST), entityCaptor.capture(),
                    any(ParameterizedTypeReference.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) entityCaptor.getValue().getBody();
            assertThat(body).containsEntry("type", "TABLE").containsKey("resourceIds");
        }

        @Test
        @DisplayName("empty input short-circuits without an HTTP call")
        void emptyInputSkipsHttp() {
            Map<String, PublicationClient.ResourcePublicationStatusRef> refs =
                    publicationClient.findResourcePublicationStatuses("TABLE", java.util.List.of(), TENANT_ID);

            assertThat(refs).isEmpty();
            verify(restTemplate, org.mockito.Mockito.never()).exchange(
                    any(String.class), any(HttpMethod.class), any(HttpEntity.class),
                    any(ParameterizedTypeReference.class));
        }

        @Test
        @DisplayName("fail-open: an HTTP error yields an empty map (the list renders no badge rather than breaking)")
        void httpErrorFailsOpen() {
            when(restTemplate.exchange(eq(URL), eq(HttpMethod.POST), any(HttpEntity.class),
                    any(ParameterizedTypeReference.class)))
                    .thenThrow(new RuntimeException("publication-service down"));

            Map<String, PublicationClient.ResourcePublicationStatusRef> refs =
                    publicationClient.findResourcePublicationStatuses("TABLE", java.util.List.of("10"), TENANT_ID);

            assertThat(refs).isEmpty();
        }
    }

    @Nested
    @DisplayName("findApplicationPublicationsByWorkflowIds")
    class FindApplicationPublicationsByWorkflowIds {

        private static final String URL =
                BASE_URL + "/api/internal/publications/application-publications-by-workflow-ids";

        @Test
        @DisplayName("posts the workflow ids and folds the response into a (workflowId → {pubId, status, showcase ids}) map")
        void postsIdsAndParsesRefs() {
            UUID wf = UUID.randomUUID();
            UUID pub = UUID.randomUUID();
            UUID iface = UUID.randomUUID();
            when(restTemplate.exchange(eq(URL), eq(HttpMethod.POST), any(HttpEntity.class),
                    any(ParameterizedTypeReference.class)))
                    .thenReturn(ResponseEntity.ok(Map.of(
                            wf.toString(), Map.of("publicationId", pub.toString(), "status", "ACTIVE",
                                    "showcaseInterfaceId", iface.toString(), "showcaseRunId", "run_show_1"))));

            Map<UUID, PublicationClient.ApplicationPublicationRef> refs =
                    publicationClient.findApplicationPublicationsByWorkflowIds(java.util.List.of(wf), TENANT_ID);

            assertThat(refs).containsKey(wf);
            assertThat(refs.get(wf).publicationId()).isEqualTo(pub);
            assertThat(refs.get(wf).status()).isEqualTo("ACTIVE");
            // Showcase ids round-trip so the board can render via the authenticated per-run path.
            assertThat(refs.get(wf).showcaseInterfaceId()).isEqualTo(iface);
            assertThat(refs.get(wf).showcaseRunId()).isEqualTo("run_show_1");

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(URL), eq(HttpMethod.POST), entityCaptor.capture(),
                    any(ParameterizedTypeReference.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) entityCaptor.getValue().getBody();
            assertThat(body).containsKey("workflowIds");
        }

        @Test
        @DisplayName("skips malformed rows (missing publicationId) without failing the batch")
        void skipsMalformedRows() {
            UUID good = UUID.randomUUID();
            UUID goodPub = UUID.randomUUID();
            UUID bad = UUID.randomUUID();
            java.util.Map<String, Map<String, String>> resp = new java.util.HashMap<>();
            resp.put(good.toString(), Map.of("publicationId", goodPub.toString(), "status", "ACTIVE"));
            resp.put(bad.toString(), Map.of("status", "ACTIVE")); // no publicationId
            when(restTemplate.exchange(eq(URL), eq(HttpMethod.POST), any(HttpEntity.class),
                    any(ParameterizedTypeReference.class)))
                    .thenReturn(ResponseEntity.ok(resp));

            Map<UUID, PublicationClient.ApplicationPublicationRef> refs =
                    publicationClient.findApplicationPublicationsByWorkflowIds(java.util.List.of(good, bad), TENANT_ID);

            assertThat(refs).containsOnlyKeys(good);
            // Pre-showcase rows (no showcase ids in the response) → null ref fields, not a parse failure.
            assertThat(refs.get(good).showcaseInterfaceId()).isNull();
            assertThat(refs.get(good).showcaseRunId()).isNull();
        }

        @Test
        @DisplayName("empty input short-circuits without an HTTP call")
        void emptyInputSkipsHttp() {
            Map<UUID, PublicationClient.ApplicationPublicationRef> refs =
                    publicationClient.findApplicationPublicationsByWorkflowIds(java.util.List.of(), TENANT_ID);

            assertThat(refs).isEmpty();
            verify(restTemplate, org.mockito.Mockito.never()).exchange(
                    any(String.class), any(HttpMethod.class), any(HttpEntity.class),
                    any(ParameterizedTypeReference.class));
        }

        @Test
        @DisplayName("fail-open: an HTTP error yields an empty map")
        void httpErrorFailsOpen() {
            when(restTemplate.exchange(eq(URL), eq(HttpMethod.POST), any(HttpEntity.class),
                    any(ParameterizedTypeReference.class)))
                    .thenThrow(new RuntimeException("publication-service down"));

            Map<UUID, PublicationClient.ApplicationPublicationRef> refs =
                    publicationClient.findApplicationPublicationsByWorkflowIds(
                            java.util.List.of(UUID.randomUUID()), TENANT_ID);

            assertThat(refs).isEmpty();
        }
    }

    @Nested
    @DisplayName("findPublicationVisibilitiesByWorkflowIds")
    class FindPublicationVisibilitiesByWorkflowIds {

        private static final String URL =
                BASE_URL + "/api/internal/publications/publication-visibilities-by-workflow-ids";

        @Test
        @DisplayName("posts the workflow ids and folds the response into a (workflowId → visibility) map")
        void postsIdsAndParsesVisibilities() {
            UUID publicWf = UUID.randomUUID();
            UUID privateWf = UUID.randomUUID();
            when(restTemplate.exchange(eq(URL), eq(HttpMethod.POST), any(HttpEntity.class),
                    any(ParameterizedTypeReference.class)))
                    .thenReturn(ResponseEntity.ok(Map.of(
                            publicWf.toString(), "PUBLIC",
                            privateWf.toString(), "PRIVATE")));

            Map<UUID, String> vis =
                    publicationClient.findPublicationVisibilitiesByWorkflowIds(
                            java.util.List.of(publicWf, privateWf), TENANT_ID);

            assertThat(vis).containsEntry(publicWf, "PUBLIC").containsEntry(privateWf, "PRIVATE");

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(URL), eq(HttpMethod.POST), entityCaptor.capture(),
                    any(ParameterizedTypeReference.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) entityCaptor.getValue().getBody();
            assertThat(body).containsKey("workflowIds");
        }

        @Test
        @DisplayName("empty input short-circuits without an HTTP call")
        void emptyInputSkipsHttp() {
            Map<UUID, String> vis =
                    publicationClient.findPublicationVisibilitiesByWorkflowIds(java.util.List.of(), TENANT_ID);

            assertThat(vis).isEmpty();
            verify(restTemplate, org.mockito.Mockito.never()).exchange(
                    any(String.class), any(HttpMethod.class), any(HttpEntity.class),
                    any(ParameterizedTypeReference.class));
        }

        @Test
        @DisplayName("fail-open: an HTTP error yields an empty map (boards fall open, never break)")
        void httpErrorFailsOpen() {
            when(restTemplate.exchange(eq(URL), eq(HttpMethod.POST), any(HttpEntity.class),
                    any(ParameterizedTypeReference.class)))
                    .thenThrow(new RuntimeException("publication-service down"));

            Map<UUID, String> vis =
                    publicationClient.findPublicationVisibilitiesByWorkflowIds(
                            java.util.List.of(UUID.randomUUID()), TENANT_ID);

            assertThat(vis).isEmpty();
        }
    }
}
