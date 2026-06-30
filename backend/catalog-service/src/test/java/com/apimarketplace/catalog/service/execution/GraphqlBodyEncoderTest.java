package com.apimarketplace.catalog.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link GraphqlBodyEncoder}.
 *
 * <p>The encoder shapes a GraphQL request body from:
 * <ul>
 *   <li>a query string (always present)</li>
 *   <li>an optional operationName</li>
 *   <li>runtime variables coming from the workflow params</li>
 *   <li>static variables declared by the API author in {@code execution.request.graphql.variables}</li>
 * </ul>
 *
 * <p>Critical invariant: <strong>static variables override runtime values</strong>. The
 * author can freeze a variable (apiVersion, tenantId, …) and the agent cannot bypass it.
 */
@DisplayName("GraphqlBodyEncoder")
class GraphqlBodyEncoderTest {

    private ObjectMapper mapper;
    private GraphqlBodyEncoder encoder;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        encoder = new GraphqlBodyEncoder(mapper);
    }

    private JsonNode cfg(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("merges runtime params with static variables - query + variables present")
    void mergesStaticAndRuntimeVariables() {
        JsonNode g = cfg("{\"variables\":{\"apiVersion\":\"v2\"}}");
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("userId", "u-42");

        Map<String, Object> body = encoder.encode(
                "query GetUser($userId: ID!, $apiVersion: String!) { user(id:$userId){id} }",
                null, runtime, g);

        assertThat(body).containsKey("query");
        assertThat(body).doesNotContainKey("operationName");
        assertThat(body).containsKey("variables");
        @SuppressWarnings("unchecked")
        Map<String, Object> vars = (Map<String, Object>) body.get("variables");
        assertThat(vars).containsEntry("userId", "u-42").containsEntry("apiVersion", "v2");
    }

    @Test
    @DisplayName("static variables override runtime values (author freeze wins)")
    void staticOverridesRuntimeVariables() {
        // Runtime tries to inject apiVersion=v1, but author has frozen v2 in the spec.
        JsonNode g = cfg("{\"variables\":{\"apiVersion\":\"v2\",\"tenantId\":\"frozen-tenant\"}}");
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("apiVersion", "v1");          // bypass attempt
        runtime.put("tenantId", "agent-tenant");  // bypass attempt
        runtime.put("userId", "u-42");            // legitimate runtime input

        Map<String, Object> body = encoder.encode("query Q { __typename }", null, runtime, g);
        @SuppressWarnings("unchecked")
        Map<String, Object> vars = (Map<String, Object>) body.get("variables");
        assertThat(vars).containsEntry("apiVersion", "v2");
        assertThat(vars).containsEntry("tenantId", "frozen-tenant");
        assertThat(vars).containsEntry("userId", "u-42");
    }

    @Test
    @DisplayName("operationName is propagated to the body when present and non-blank")
    void includesOperationNameWhenPresent() {
        Map<String, Object> body = encoder.encode("query Q { __typename }", "GetUser",
                Map.of(), cfg("{}"));
        assertThat(body).containsEntry("operationName", "GetUser");
    }

    @Test
    @DisplayName("operationName is omitted when null or blank")
    void omitsOperationNameWhenAbsent() {
        Map<String, Object> body = encoder.encode("query Q { __typename }", null, Map.of(), cfg("{}"));
        assertThat(body).doesNotContainKey("operationName");

        Map<String, Object> body2 = encoder.encode("query Q { __typename }", "  ", Map.of(), cfg("{}"));
        assertThat(body2).doesNotContainKey("operationName");
    }

    @Test
    @DisplayName("preserves nested objects and arrays in variables (input objects, enums, lists)")
    @SuppressWarnings("unchecked")
    void preservesNestedObjectsAndArraysInVariables() {
        // Static variables include a nested object and an array - must NOT be flattened.
        JsonNode g = cfg("{\"variables\":{" +
                "\"filter\":{\"status\":\"ACTIVE\",\"tags\":[\"a\",\"b\"]}," +
                "\"ids\":[1,2,3]" +
                "}}");
        Map<String, Object> body = encoder.encode("query Q { __typename }", null, Map.of(), g);
        Map<String, Object> vars = (Map<String, Object>) body.get("variables");

        Map<String, Object> filter = (Map<String, Object>) vars.get("filter");
        assertThat(filter).containsEntry("status", "ACTIVE");
        assertThat((List<Object>) filter.get("tags")).containsExactly("a", "b");
        assertThat((List<Object>) vars.get("ids")).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("no variables when neither runtime nor static provide any (optional in body)")
    void omitsVariablesWhenNonePresent() {
        Map<String, Object> body = encoder.encode("query Q { __typename }", null, null, cfg("{}"));
        assertThat(body).doesNotContainKey("variables");
        assertThat(body).containsOnlyKeys("query");
    }
}
