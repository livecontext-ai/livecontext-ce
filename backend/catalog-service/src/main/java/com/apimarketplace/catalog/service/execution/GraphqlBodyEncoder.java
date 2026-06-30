package com.apimarketplace.catalog.service.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Encodes a GraphQL request body when an endpoint declares
 * {@code execution.request.bodyType = "graphql"}.
 *
 * <p>The execution spec provides the static GraphQL contract:
 * <pre>
 * "execution": {
 *   "request": {
 *     "bodyType": "graphql",
 *     "graphql": {
 *       "query": "query GetUser($id: ID!) { user(id: $id) { name email } }",
 *       "operationName": "GetUser",        // optional
 *       "variables": { "version": "v2" }   // optional, frozen by author
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>At runtime, the encoder produces:
 * <pre>{ "query": "...", "operationName"?: "...", "variables"?: {...} }</pre>
 *
 * <p>Variable precedence: <strong>static &gt; runtime</strong>. The author can freeze a
 * variable in {@code execution.request.graphql.variables} (e.g. an API version, a tenant
 * id) and the agent cannot override it from {@code params[]}. This matches the convention
 * of other encoders in this package (multipart, raw_binary) where the author's contract
 * always wins over runtime payloads.
 *
 * <p>The Content-Type is forced to {@code application/json} by the caller (this encoder
 * just shapes the body Map).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GraphqlBodyEncoder {

    private final ObjectMapper objectMapper;

    /**
     * Encode a GraphQL body.
     *
     * @param query         the GraphQL operation source (required, non-blank)
     * @param operationName optional operation name (used when the document declares multiple ops)
     * @param runtimeVars   variables coming from the runtime params (may be null/empty)
     * @param graphqlCfg    the {@code execution.request.graphql} JsonNode (provides static
     *                      variables that override runtime values)
     * @return a Map ready to be JSON-serialized as the request body
     */
    public Map<String, Object> encode(String query,
                                      String operationName,
                                      Map<String, Object> runtimeVars,
                                      JsonNode graphqlCfg) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        if (operationName != null && !operationName.isBlank()) {
            body.put("operationName", operationName);
        }

        Map<String, Object> variables = new LinkedHashMap<>();
        if (runtimeVars != null) {
            variables.putAll(runtimeVars);
        }
        // Static variables declared in the execution spec override runtime values.
        // Rationale: an author who freezes `apiVersion: "v2"` in the spec must be sure the
        // agent cannot reroute the call by passing `apiVersion: "v1"` at runtime.
        if (graphqlCfg != null) {
            JsonNode staticVars = graphqlCfg.path("variables");
            if (staticVars != null && staticVars.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> it = staticVars.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    variables.put(e.getKey(), objectMapper.convertValue(e.getValue(), Object.class));
                }
            }
        }

        if (!variables.isEmpty()) {
            body.put("variables", variables);
        }

        if (log.isDebugEnabled()) {
            String preview = query.length() > 60 ? query.substring(0, 60) + "…" : query;
            log.debug("GraphqlBodyEncoder: encoded body - operationName={}, query='{}', variables={}",
                    operationName, preview.replace('\n', ' '), variables.keySet());
        }
        return body;
    }
}
