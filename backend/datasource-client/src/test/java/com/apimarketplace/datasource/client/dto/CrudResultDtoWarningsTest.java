package com.apimarketplace.datasource.client.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The line the whole change rests on, and the one no other test can reach.
 *
 * <p>datasource-service has ALWAYS put {@code warnings} in the body of a CRUD response. The
 * orchestrator read that body into {@link CrudResultDto}, which did not declare the component, and
 * Jackson silently discarded it: a workflow's table step could store a value the platform had
 * already diagnosed as unusable and hand downstream nodes an output with nothing to say about it.
 * Every test above this layer mocks the client and hands itself a populated DTO, so all of them
 * stay green with the component removed. This one turns red.
 */
@DisplayName("CrudResultDto - warnings survive the wire")
class CrudResultDtoWarningsTest {

    /**
     * Configured the way Spring Boot configures the one RestTemplate actually uses: unknown
     * properties are IGNORED, not rejected. That setting is the whole reason the defect was silent
     * - a strict mapper would have thrown on the undeclared key and the missing component would
     * have been found the first time a warning was sent.
     */
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** A response body in the shape datasource-service's CrudResponse serialises. */
    private static final String SERVER_BODY = """
            {
              "operation": "create-row",
              "success": true,
              "message": "Successfully inserted 1 rows",
              "data": {
                "insertedIds": [7],
                "insertedCount": 1,
                "warnings": [
                  "video: File reference has no id and no URL - it cannot be displayed (storage key: 1/wf/run/clip.mp4)"
                ]
              }
            }
            """;

    @Test
    @DisplayName("A warning the server sent is readable on the deserialized result")
    void warningsAreDeserialized() throws Exception {
        CrudResultDto result = mapper.readValue(SERVER_BODY, CrudResultDto.class);

        assertThat(result.success()).isTrue();
        assertThat(result.data().warnings())
                .as("dropping this is the defect the change exists to fix")
                .containsExactly(
                        "video: File reference has no id and no URL - it cannot be displayed (storage key: 1/wf/run/clip.mp4)");
    }

    @Test
    @DisplayName("A response with no warnings key deserializes with a null list, not a failure")
    void absentWarningsIsNull() throws Exception {
        CrudResultDto result = mapper.readValue("""
                {"operation":"create-row","success":true,"message":"ok",
                 "data":{"insertedIds":[7],"insertedCount":1}}
                """, CrudResultDto.class);

        assertThat(result.data().warnings()).isNull();
        assertThat(result.data().insertedCount()).isEqualTo(1);
    }

    /**
     * The nine-argument constructor exists so callers written before warnings still compile. If it
     * ever stops defaulting the new component the compiler will not notice - the arity still fits.
     */
    @Test
    @DisplayName("The pre-warnings constructor still builds a result, with no warnings")
    void legacyConstructorDefaultsWarningsToNull() {
        CrudResultDto.ResultData data = new CrudResultDto.ResultData(
                null, null, null, null, java.util.List.of(7L), 1, null, null, null);

        assertThat(data.warnings()).isNull();
        assertThat(data.insertedCount()).isEqualTo(1);
    }
}
