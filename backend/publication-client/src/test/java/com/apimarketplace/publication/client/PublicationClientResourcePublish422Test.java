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
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@code publishResource} (the MCP table / interface publish) surfaces a structured 422 refusal
 * typed, like {@code publishWorkflow}: a table over the publication row limit must reach the
 * agent as "this table is too large", not as "Failed to publish resource: 422 ...".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublicationClient.publishResource - structured 422 refusal mapping")
class PublicationClientResourcePublish422Test {

    private static final String BASE_URL = "http://localhost:8092";
    private static final String URL = BASE_URL + "/api/internal/publications/publish-resource";
    private static final String TENANT_ID = "auth0|tenant-test";

    @Mock private RestTemplate restTemplate;

    private PublicationClient publicationClient;

    @BeforeEach
    void setUp() {
        publicationClient = new PublicationClient(restTemplate, BASE_URL);
    }

    @Test
    @DisplayName("regression (budget): a PUBLICATION_SNAPSHOT_TOO_LARGE 422 becomes a typed exception with the code and sentence")
    void maps422ToTypedException() {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable Entity", new HttpHeaders(),
                        ("{\"error\":\"PUBLICATION_SNAPSHOT_TOO_LARGE\","
                                + "\"message\":\"Table 'Orders' has 6000 rows (max 5000 rows per published table).\","
                                + "\"maxTableRows\":5000}").getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        Throwable thrown = catchThrowable(() ->
                publicationClient.publishResource(Map.of("type", "TABLE"), TENANT_ID, null));

        assertThat(thrown).isInstanceOf(PublicationValidationException.class);
        PublicationValidationException e = (PublicationValidationException) thrown;
        assertThat(e.getErrorCode()).isEqualTo("PUBLICATION_SNAPSHOT_TOO_LARGE");
        assertThat(e.getMessage()).startsWith("Table 'Orders' has 6000 rows");
        assertThat(e.getBody()).containsEntry("maxTableRows", 5000);
    }

    @Test
    @DisplayName("any other failure keeps the generic wrap (the mapping is not a catch-all)")
    void otherFailuresKeepTheGenericWrap() {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        Throwable thrown = catchThrowable(() ->
                publicationClient.publishResource(Map.of("type", "TABLE"), TENANT_ID, null));

        assertThat(thrown).isNotInstanceOf(PublicationValidationException.class)
                .hasMessageStartingWith("Failed to publish resource: ");
    }
}
