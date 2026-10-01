package com.apimarketplace.publication.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CatalogInternalClient} - the publish-time "is this tool backed by a custom API?"
 * lookup against catalog-service.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CatalogInternalClient.findCustomApiRefs")
class CatalogInternalClientTest {

    private static final String BASE_URL = "http://catalog:8081";
    private static final String PUBLISHER = "tenant-1";
    private static final String PUBLISHER_ORG = "org-7";
    private static final String EXPECTED_URL =
            BASE_URL + "/api/internal/catalog/workflow-inspector/custom-apis";

    @Mock private RestTemplate restTemplate;

    private CatalogInternalClient client;

    @BeforeEach
    void setUp() {
        client = new CatalogInternalClient(restTemplate, BASE_URL);
    }

    @Test
    @DisplayName("posts the identifiers to the internal endpoint and returns the custom APIs")
    void postsIdentifiersAndReturnsRefs() {
        Map<String, Object> ref = Map.of(
                "apiSlug", "my-api", "apiName", "My API",
                "toolIdentifiers", List.of("my-api/do-thing"));
        when(restTemplate.exchange(eq(EXPECTED_URL), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Map.of("customApis", List.of(ref))));

        List<Map<String, Object>> refs = client.findCustomApiRefs(List.of("my-api/do-thing"), PUBLISHER, PUBLISHER_ORG);

        assertThat(refs).containsExactly(ref);

        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq(EXPECTED_URL), eq(HttpMethod.POST), captor.capture(),
                any(ParameterizedTypeReference.class));
        assertThat(captor.getValue().getBody())
                .containsEntry("toolSlugs", List.of("my-api/do-thing"));
        assertThat(captor.getValue().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        // The publishing scope decides how widely catalog-service may match: its own APIs
        // on the slug prefix, anyone else's on an exact row.
        assertThat(captor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(PUBLISHER);
        assertThat(captor.getValue().getHeaders().getFirst("X-Organization-ID")).isEqualTo(PUBLISHER_ORG);
    }

    @Test
    @DisplayName("a personal-scope publish sends no organization header (no blank value to mis-read)")
    void personalScopeOmitsTheOrgHeader() {
        when(restTemplate.exchange(eq(EXPECTED_URL), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Map.of("customApis", List.of())));

        client.findCustomApiRefs(List.of("my-api/do-thing"), PUBLISHER, "   ");

        ArgumentCaptor<HttpEntity<Map<String, Object>>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(eq(EXPECTED_URL), eq(HttpMethod.POST), captor.capture(),
                any(ParameterizedTypeReference.class));
        assertThat(captor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(PUBLISHER);
        assertThat(captor.getValue().getHeaders().containsKey("X-Organization-ID")).isFalse();
    }

    @Test
    @DisplayName("an empty or null identifier list never reaches catalog-service")
    void emptyInputSkipsTheCall() {
        assertThat(client.findCustomApiRefs(List.of(), PUBLISHER, PUBLISHER_ORG)).isEmpty();
        assertThat(client.findCustomApiRefs(null, PUBLISHER, PUBLISHER_ORG)).isEmpty();
        verify(restTemplate, never()).exchange(any(String.class), any(), any(), any(ParameterizedTypeReference.class));
    }

    @Test
    @DisplayName("no custom API in the answer means an empty list, not a failure")
    void emptyAnswerReturnsEmptyList() {
        when(restTemplate.exchange(eq(EXPECTED_URL), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Map.of("customApis", List.of())));

        assertThat(client.findCustomApiRefs(List.of("github/get-user"), PUBLISHER, PUBLISHER_ORG)).isEmpty();
    }

    @Test
    @DisplayName("a malformed body (no customApis key) degrades to an empty list")
    void malformedBodyReturnsEmptyList() {
        when(restTemplate.exchange(eq(EXPECTED_URL), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Map.of("unexpected", "shape")));

        assertThat(client.findCustomApiRefs(List.of("github/get-user"), PUBLISHER, PUBLISHER_ORG)).isEmpty();
    }

    @Test
    @DisplayName("a null body degrades to an empty list")
    void nullBodyReturnsEmptyList() {
        when(restTemplate.exchange(eq(EXPECTED_URL), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(null));

        assertThat(client.findCustomApiRefs(List.of("github/get-user"), PUBLISHER, PUBLISHER_ORG)).isEmpty();
    }

    @Test
    @DisplayName("an unreachable catalog-service fails OPEN: the publish is not blocked by an outage")
    void transportErrorFailsOpen() {
        when(restTemplate.exchange(eq(EXPECTED_URL), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        assertThat(client.findCustomApiRefs(List.of("my-api/do-thing"), PUBLISHER, PUBLISHER_ORG)).isEmpty();
    }

    @Test
    @DisplayName("non-map entries in the answer are skipped instead of breaking the lookup")
    void nonMapEntriesAreSkipped() {
        Map<String, Object> ref = Map.of("apiSlug", "my-api", "apiName", "My API");
        when(restTemplate.exchange(eq(EXPECTED_URL), eq(HttpMethod.POST), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Map.of("customApis", List.of("garbage", ref))));

        assertThat(client.findCustomApiRefs(List.of("my-api/do-thing"), PUBLISHER, PUBLISHER_ORG)).containsExactly(ref);
    }
}
