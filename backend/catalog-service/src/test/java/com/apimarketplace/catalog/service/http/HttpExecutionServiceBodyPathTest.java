package com.apimarketplace.catalog.service.http;

import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import com.apimarketplace.catalog.repository.ApiToolParameterRepository;
import com.apimarketplace.common.security.CredentialEncryptionService;
import com.apimarketplace.catalog.service.UserCredentialService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.when;

/**
 * Where a parameter's value lands in the request body.
 *
 * <p>The place is declared per parameter, in its {@code extras.bodyPath}, and it
 * is read by two different readers: this one, which BUILDS the request, and the
 * generation surface, which has to recognise the same field to offer the values
 * it accepts. They share one parser so the two can never disagree about a stray
 * space, and these tests pin the build side of that contract - the whole
 * catalog-service suite stayed green with the reader returning null, so nothing
 * else covers it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HttpExecutionService body paths")
class HttpExecutionServiceBodyPathTest {

    @Mock private ApiToolParameterRepository parameters;
    @Mock private UserCredentialService credentials;
    @Mock private CredentialEncryptionService encryption;
    @Mock private JdbcTemplate jdbc;
    @Mock private RestTemplate rest;

    private ObjectMapper mapper;
    private HttpExecutionService service;
    private static final UUID TOOL = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        lenient().when(encryption.decrypt(any())).thenAnswer(i -> i.getArgument(0));
        service = new HttpExecutionService(parameters, credentials, encryption, mapper, jdbc, rest, new ErrorPolicyEngine());
    }

    private static ApiToolParameterEntity bodyParam(String name, String extras) {
        ApiToolParameterEntity p = new ApiToolParameterEntity();
        p.setId(UUID.randomUUID());
        p.setApiToolId(TOOL);
        p.setName(name);
        p.setParameterType("body");
        p.setDataType("string");
        p.setExtras(extras);
        return p;
    }

    private static ApiToolEntity tool() {
        ApiToolEntity t = new ApiToolEntity();
        t.setId(TOOL);
        t.setMethod("POST");
        return t;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bodyFor(List<ApiToolParameterEntity> rows, String json) {
        when(parameters.findByApiToolId(TOOL)).thenReturn(rows);
        JsonNode params;
        try {
            params = mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("bad fixture", e);
        }
        return (Map<String, Object>) service.prepareRequestBody(tool(), params);
    }

    @Test
    @DisplayName("a declared body path nests the value where the provider wants it")
    void nestsByDeclaredPath() {
        Map<String, Object> body = bodyFor(
                List.of(bodyParam("voice_id", "{\"bodyPath\":\"video_inputs[0].voice.voice_id\"}")),
                "[{\"voice_id\":\"21m00\"}]");

        assertThat(body).containsKey("video_inputs");
        assertThat(body.toString()).contains("21m00");
        assertThat(body).doesNotContainKey("voice_id");
    }

    @Test
    @DisplayName("a padded body path is trimmed, so the field the generation surface matched is the field written")
    void trimsTheDeclaredPath() {
        // The two readers compare this string against a descriptor's write path,
        // which is trimmed as it is parsed. Untrimmed here, the request would
        // fill a field nobody offered values for.
        Map<String, Object> body = bodyFor(
                List.of(bodyParam("voice_id", "{\"bodyPath\":\"  voice.id  \"}")),
                "[{\"voice_id\":\"21m00\"}]");

        assertThat(body).containsKey("voice");
        assertThat(body).doesNotContainKey("  voice.id  ");
    }

    @Test
    @DisplayName("a blank body path is no path: the value keeps the parameter's own name")
    void aBlankPathFallsBackToTheParameterName() {
        // It used to land under the empty-string key, which no provider reads.
        Map<String, Object> body = bodyFor(
                List.of(bodyParam("voice_id", "{\"bodyPath\":\"\"}")),
                "[{\"voice_id\":\"21m00\"}]");

        assertThat(body).containsEntry("voice_id", "21m00");
        assertThat(body).doesNotContainKey("");
    }

    @Test
    @DisplayName("extras that declare no path, or cannot be read, leave the parameter flat")
    void noPathOrMalformedExtrasStayFlat() {
        assertThat(bodyFor(List.of(bodyParam("voice_id", "{}")), "[{\"voice_id\":\"a\"}]"))
                .containsEntry("voice_id", "a");
        assertThat(bodyFor(List.of(bodyParam("voice_id", null)), "[{\"voice_id\":\"b\"}]"))
                .containsEntry("voice_id", "b");
        assertThat(bodyFor(List.of(bodyParam("voice_id", "not json")), "[{\"voice_id\":\"c\"}]"))
                .containsEntry("voice_id", "c");
    }
}
