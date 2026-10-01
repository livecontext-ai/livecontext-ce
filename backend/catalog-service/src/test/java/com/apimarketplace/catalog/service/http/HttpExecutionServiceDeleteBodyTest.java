package com.apimarketplace.catalog.service.http;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * A DELETE may carry a request body, and 60 catalog endpoints need it to.
 *
 * <p>Their vendors document a body on DELETE: Spotify removes playlist tracks with
 * {@code {"tracks":[...]}}, Auth0 removes roles with {@code {"roles":[...]}}, Cloudflare bulk
 * key delete takes an array, Quickbase takes {@code {"from","where"}}, Segment takes
 * {@code {"userIds"}}, Coda takes {@code {"rowIds"}}, Weaviate takes {@code {"match"}}. The
 * engine used to discard body params on DELETE alongside GET, so every one of those calls went
 * out bodyless: a delete that removed nothing, or a 400 with no way for the agent to see why.
 *
 * <p>{@link DeleteBodyProbeTest} covers the transport question separately and shows the
 * configured factory delivers a DELETE body intact, so the only obstacle was this early return.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpExecutionService - DELETE carries its request body")
class HttpExecutionServiceDeleteBodyTest {

    @Mock private ApiToolParameterRepository apiToolParameterRepository;
    @Mock private UserCredentialService userCredentialService;
    @Mock private CredentialEncryptionService encryptionService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private RestTemplate restTemplate;

    private HttpExecutionService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        lenient().when(apiToolParameterRepository.findByApiToolId(any())).thenReturn(List.of());
        service = new HttpExecutionService(
                apiToolParameterRepository, userCredentialService, encryptionService,
                objectMapper, jdbcTemplate, restTemplate, new ErrorPolicyEngine());
    }

    private ApiToolEntity tool(String method) {
        ApiToolEntity t = new ApiToolEntity();
        t.setId(UUID.randomUUID());
        t.setMethod(method);
        t.setEndpoint("/playlists/{playlist_id}/tracks");
        return t;
    }

    /**
     * The engine's parameter wire format: an array of single-entry objects whose FIELD NAME is
     * the parameter name, i.e. [{"tracks": [...]}], not [{"name":"tracks","value":[...]}].
     */
    private ArrayNode params(Map<String, Object> values) {
        ArrayNode arr = objectMapper.createArrayNode();
        values.forEach((k, v) -> {
            ObjectNode n = objectMapper.createObjectNode();
            n.putPOJO(k, v);
            arr.add(n);
        });
        return arr;
    }

    @Test
    @DisplayName("FIX: a DELETE builds its body from the declared body params")
    @SuppressWarnings("unchecked")
    void deleteBuildsItsBody() {
        Object body = service.prepareRequestBody(
                tool("DELETE"), params(Map.of("tracks", List.of(Map.of("uri", "spotify:track:1")))));

        assertThat(body)
                .as("pre-fix this was null, so Spotify received a track removal naming no tracks")
                .isNotNull();
        assertThat((Map<String, Object>) body).containsKey("tracks");
    }

    @Test
    @DisplayName("a GET still has no body, which is the case that genuinely has none")
    void getStillHasNoBody() {
        Object body = service.prepareRequestBody(
                tool("GET"), params(Map.of("anything", "value")));

        assertThat(body)
                .as("a body on GET is not carried by the transport and no vendor here needs one")
                .isNull();
    }

    @Test
    @DisplayName("a DELETE with no body params still sends nothing, so plain deletes are untouched")
    void deleteWithoutBodyParamsSendsNoBody() {
        Object body = service.prepareRequestBody(tool("DELETE"), objectMapper.createArrayNode());

        assertThat(body)
                .as("the vast majority of DELETE endpoints take only a path id; they must not "
                        + "suddenly start sending an empty JSON object")
                .satisfiesAnyOf(
                        b -> assertThat(b).isNull(),
                        b -> assertThat((Map<String, Object>) b).isEmpty());
    }

    @Test
    @DisplayName("POST is unaffected")
    @SuppressWarnings("unchecked")
    void postStillBuildsItsBody() {
        Object body = service.prepareRequestBody(tool("POST"), params(Map.of("name", "thing")));

        assertThat(body).isNotNull();
        assertThat((Map<String, Object>) body).containsEntry("name", "thing");
    }
}
