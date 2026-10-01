package com.apimarketplace.publication.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@code publishWorkflow} must surface a structured 422 refusal the same way
 * {@code publishAgent} does. Before this mapping the refusal fell into the generic
 * wrap, so the MCP publish tool reported "Failed to publish workflow: 422 ..." and the
 * agent could not tell that the PLAN was the problem (a custom API it must remove).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublicationClient.publishWorkflow - structured 422 refusal mapping")
class PublicationClientWorkflowPublish422Test {

    private static final String BASE_URL = "http://localhost:8092";
    private static final String URL = BASE_URL + "/api/internal/publications/publish";
    private static final String TENANT_ID = "auth0|tenant-test";

    @Mock private RestTemplate restTemplate;

    private PublicationClient publicationClient;

    @BeforeEach
    void setUp() {
        publicationClient = new PublicationClient(restTemplate, BASE_URL);
    }

    private void stub422(String body) {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable Entity", new HttpHeaders(),
                        body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a CUSTOM_API_NOT_PUBLISHABLE 422 becomes a typed exception carrying the code, sentence and APIs")
    void maps422ToTypedException() {
        stub422("{\"error\":\"CUSTOM_API_NOT_PUBLISHABLE\","
                + "\"message\":\"Custom APIs cannot be shared. This publication uses My Private API.\","
                + "\"customApis\":[{\"apiSlug\":\"my-private-api\",\"apiName\":\"My Private API\","
                + "\"toolIdentifiers\":[\"my-private-api/do-thing\"]}]}");

        Throwable thrown = catchThrowable(() ->
                publicationClient.publishWorkflow(Map.of("workflowId", "w"), TENANT_ID, null));

        assertThat(thrown).isInstanceOf(PublicationValidationException.class);
        PublicationValidationException e = (PublicationValidationException) thrown;
        assertThat(e.getErrorCode()).isEqualTo("CUSTOM_API_NOT_PUBLISHABLE");
        assertThat(e.getMessage()).contains("My Private API");
        List<?> customApis = (List<?>) e.getBody().get("customApis");
        assertThat(((Map<?, ?>) customApis.get(0)).get("apiName")).isEqualTo("My Private API");
    }

    @Test
    @DisplayName("a malformed 422 body degrades to the raw body as the message, never an opaque HTTP string")
    void malformed422BodyDegradesGracefully() {
        stub422("not json at all");

        Throwable thrown = catchThrowable(() ->
                publicationClient.publishWorkflow(Map.of("workflowId", "w"), TENANT_ID, null));

        assertThat(thrown).isInstanceOf(PublicationValidationException.class);
        assertThat(thrown).hasMessage("not json at all");
        assertThat(((PublicationValidationException) thrown).getErrorCode()).isNull();
    }

    @Test
    @DisplayName("a 400 still becomes an IllegalArgumentException (the 422 mapping does not swallow it)")
    void badRequestStillMapsToIllegalArgument() {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST, "Bad Request", new HttpHeaders(),
                        "{\"error\":\"Workflow has no plan to publish\"}".getBytes(StandardCharsets.UTF_8),
                        StandardCharsets.UTF_8));

        Throwable thrown = catchThrowable(() ->
                publicationClient.publishWorkflow(Map.of("workflowId", "w"), TENANT_ID, null));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Workflow has no plan to publish");
    }
}
