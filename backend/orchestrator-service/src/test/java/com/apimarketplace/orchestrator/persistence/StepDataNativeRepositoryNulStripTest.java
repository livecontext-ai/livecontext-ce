package com.apimarketplace.orchestrator.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Funnel tests for {@code StepDataNativeRepository.toJson} - the serialization
 * point feeding the {@code ::jsonb} columns of the step-row INSERT
 * ({@code input_data}, {@code metadata}, merge-branch lists).
 *
 * <p>The worse sibling of the payload-blob loss: a U+0000 codepoint in
 * {@code resolved_params}/metadata killed the step-row INSERT itself
 * (SQLSTATE 22P05) - NO row at all, the step vanished from history. The strip
 * makes the row land with the codepoint gone; a LITERAL backslash-u0000 in
 * the data is preserved.
 */
@DisplayName("StepDataNativeRepository.toJson - U+0000 strip for step-row jsonb columns")
class StepDataNativeRepositoryNulStripTest {

    /** The NUL codepoint, built per convention via (char) 0 - never a source escape. */
    private static final String NUL = String.valueOf((char) 0);

    private StepDataNativeRepository repository;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repository = new StepDataNativeRepository(mock(JdbcTemplate.class), mapper);
    }

    @Test
    @DisplayName("input_data/metadata maps containing U+0000 serialize with the codepoint gone and surrounding text intact (a<NUL>b -> ab)")
    void stripsNulFromMapValues() throws Exception {
        Map<String, Object> inputData = new LinkedHashMap<>();
        inputData.put("query", "a" + NUL + "b");
        inputData.put("nested", Map.of("deep", List.of("x" + NUL + "y")));

        String json = repository.toJson(inputData);

        assertThat(json).doesNotContain(NUL);
        Map<?, ?> back = mapper.readValue(json, Map.class);
        assertThat(back.get("query")).isEqualTo("ab");
        assertThat(((List<?>) ((Map<?, ?>) back.get("nested")).get("deep")).get(0)).isEqualTo("xy");
    }

    @Test
    @DisplayName("a LITERAL backslash-u0000 in the data is NOT altered")
    void literalBackslashU0000Preserved() throws Exception {
        String literal = "\\" + "u0000";
        String json = repository.toJson(Map.of("k", literal));

        Map<?, ?> back = mapper.readValue(json, Map.class);
        assertThat(back.get("k")).isEqualTo(literal);
    }

    @Test
    @DisplayName("hit-free payloads serialize byte-identically to plain Jackson")
    void cleanPayloadUnaffected() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("k", "value");
        payload.put("n", 7);

        assertThat(repository.toJson(payload)).isEqualTo(mapper.writeValueAsString(payload));
    }

    @Test
    @DisplayName("null input stays null (column-level NULL)")
    void nullInput() {
        assertThat(repository.toJson(null)).isNull();
    }
}
